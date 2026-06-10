import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import datagen.DataGenerator
import datagen.GenParams
import etl.Rebalance
import etl.TABLES
import etl.buildModelPortfolio
import etl.importPortfolio
import etl.loadFromParquet
import etl.openDatabase
import etl.openInMemory
import etl.rowCount
import etl.writeParquet
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import report.buildHtmlReport
import report.buildReport
import report.printQueryPlan
import report.runBench
import report.serve
import report.writeDashboard

class Brinson : CliktCommand(name = "brinson") {
    override fun run() = Unit
}

class Generate : CliktCommand(name = "generate") {
    private val scale by option(help = "Holdings scale factor; 1.0 -> >10M position rows").double().default(1.0)
    private val seed by option(help = "Random seed").long().default(42L)
    private val portfolios by option().int().default(50)
    private val securities by option().int().default(500)
    private val days by option(help = "Trading days (~504 = 2 years)").int().default(504)
    private val out by option(help = "Parquet output directory").default("data/parquet")

    override fun run() {
        val params = GenParams(
            seed = seed, securities = securities, portfolios = portfolios,
            tradingDays = days, scale = scale,
        )
        echo("Generating: $portfolios portfolios x ~${params.meanHoldings} holdings x $days days (seed $seed)")
        openInMemory().use { conn ->
            val started = System.currentTimeMillis()
            DataGenerator(params).generateInto(conn)
            echo("Simulated in ${System.currentTimeMillis() - started} ms; writing Parquet to $out")
            writeParquet(conn, Path.of(out))
            for (t in TABLES) echo("  %-18s %,12d rows".format(t, rowCount(conn, t)))
        }
    }
}

class Load : CliktCommand(name = "load") {
    private val parquet by option(help = "Parquet input directory").default("data/parquet")
    private val db by option(help = "DuckDB database file").default("data/brinson.duckdb")

    override fun run() {
        openDatabase(Path.of(db)).use { conn ->
            loadFromParquet(conn, Path.of(parquet))
            echo("Loaded into $db:")
            for (t in TABLES) echo("  %-18s %,12d rows".format(t, rowCount(conn, t)))
        }
    }
}

class Report : CliktCommand(name = "report") {
    private val db by option().default("data/brinson.duckdb")
    private val portfolio by option(help = "Portfolio id").int().default(7)
    private val from by option(help = "Start date (default: full history)")
    private val to by option(help = "End date (default: full history)")
    private val html by option(help = "Also write an HTML waterfall chart to this path")

    override fun run() {
        openDatabase(Path.of(db)).use { conn ->
            val (minDate, maxDate) = conn.createStatement().use { st ->
                st.executeQuery("SELECT min(date), max(date) FROM benchmark_returns").use { rs ->
                    rs.next()
                    rs.getDate(1).toLocalDate() to rs.getDate(2).toLocalDate()
                }
            }
            val fromDate = from?.let(LocalDate::parse) ?: minDate
            val toDate = to?.let(LocalDate::parse) ?: maxDate
            // buildReport returns its attribution rows so the HTML chart reuses them
            // instead of re-running the query.
            val output = buildReport(conn, portfolio, fromDate, toDate)
            echo(output.text)
            html?.let { path ->
                val target = Path.of(path).toAbsolutePath()
                target.parent?.let(Files::createDirectories)
                target.toFile().writeText(
                    buildHtmlReport(output.portfolioName, output.from, output.to, output.attribution),
                )
                echo("HTML waterfall written to $path")
            }
        }
    }
}

class Bench : CliktCommand(name = "bench") {
    private val db by option().default("data/brinson.duckdb")
    private val parquet by option(help = "If set, also benchmark SQL directly over Parquet").default("data/parquet")
    private val runs by option().int().default(5)
    private val explain by option(help = "Print EXPLAIN ANALYZE for the optimized query instead of timing").flag()

    override fun run() {
        openDatabase(Path.of(db)).use { conn ->
            if (explain) {
                printQueryPlan(conn, ::echo)
                return
            }
            val parquetDir = Path.of(parquet).takeIf { it.toFile().isDirectory }
            if (parquetDir == null) {
                echo("note: Parquet directory '$parquet' not found — skipping the Parquet-direct variant")
            }
            runBench(conn, parquetDir, runs, ::echo)
        }
    }
}

class Dashboard : CliktCommand(name = "dashboard") {
    private val db by option().default("data/brinson.duckdb")
    private val out by option(help = "Output directory for the static site").default("docs/dashboard")

    override fun run() {
        openDatabase(Path.of(db)).use { conn ->
            writeDashboard(conn, Path.of(out), ::echo)
        }
    }
}

class Import : CliktCommand(name = "import") {
    private val csv by option(help = "Daily-holdings CSV: date,ticker,sector,quantity,price,dividend").required()
    private val name by option(help = "Portfolio display name").required()
    private val db by option().default("data/brinson.duckdb")

    override fun run() {
        openDatabase(Path.of(db)).use { conn ->
            val r = importPortfolio(conn, Path.of(csv), name)
            echo("Imported '${r.name}' as portfolio ${r.portfolioId}:")
            echo("  %,d position rows | %d tickers (%d new securities) | %s .. %s | %d estimated flow days"
                .format(r.positionRows, r.tickers, r.newSecurities, r.from, r.to, r.flowDays))
            echo("Note: flows are estimated from share changes at the prior close; real flows are")
            echo("rarely pro-rata, so attribution vs TWR may differ slightly on flow days (METHODOLOGY.md).")
            echo("Re-run 'brinson dashboard' (or restart 'serve') to surface it.")
        }
    }
}

class Model : CliktCommand(name = "model") {
    private val name by option(help = "Portfolio display name").required()
    private val holdings by option(
        help = "Target weights as TICKER=WEIGHT pairs (fractions or percents), e.g. SEC001=60,SEC002=40",
    ).required()
    private val rebalance by option(help = "none | monthly | quarterly").default("monthly")
    private val db by option().default("data/brinson.duckdb")

    override fun run() {
        val parsed = holdings.split(",").map { pair ->
            val parts = pair.split("=")
            require(parts.size == 2 && parts[1].toDoubleOrNull() != null) {
                "bad holding '$pair' — expected TICKER=WEIGHT"
            }
            parts[0].trim() to parts[1].toDouble()
        }
        val freq = Rebalance.entries.firstOrNull { it.name.equals(rebalance, ignoreCase = true) }
            ?: throw IllegalArgumentException("bad --rebalance '$rebalance' — use none, monthly, or quarterly")
        openDatabase(Path.of(db)).use { conn ->
            val r = buildModelPortfolio(conn, name, parsed, freq)
            echo("Built model '${r.name}' as portfolio ${r.portfolioId}:")
            echo("  %,d position rows | %d holdings | %s .. %s | rebalance: %s | %d flow days (0 = self-financing)"
                .format(r.positionRows, r.tickers, r.from, r.to, rebalance.lowercase(), r.flowDays))
            echo("Re-run 'brinson dashboard' (or restart 'serve') to surface it.")
        }
    }
}

class Serve : CliktCommand(name = "serve") {
    private val db by option().default("data/brinson.duckdb")
    private val port by option(help = "Listen port (default: \$PORT or 8080)").int()
        .default(System.getenv("PORT")?.toIntOrNull() ?: 8080)

    override fun run() {
        serve(Path.of(db), port, ::echo)
        // The JDK HttpServer runs on non-daemon executor threads; block forever.
        Thread.currentThread().join()
    }
}

fun main(args: Array<String>) =
    Brinson().subcommands(Generate(), Load(), Report(), Bench(), Dashboard(), Serve(), Import(), Model()).main(args)
