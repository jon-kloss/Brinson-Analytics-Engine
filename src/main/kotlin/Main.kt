import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import datagen.DataGenerator
import datagen.GenParams
import etl.TABLES
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
import report.runBench

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

    override fun run() {
        val parquetDir = Path.of(parquet).takeIf { it.toFile().isDirectory }
        if (parquetDir == null) {
            echo("note: Parquet directory '$parquet' not found — skipping the Parquet-direct variant")
        }
        openDatabase(Path.of(db)).use { conn ->
            runBench(conn, parquetDir, runs, ::echo)
        }
    }
}

fun main(args: Array<String>) =
    Brinson().subcommands(Generate(), Load(), Report(), Bench()).main(args)
