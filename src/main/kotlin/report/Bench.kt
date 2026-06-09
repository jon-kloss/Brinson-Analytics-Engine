package report

import java.nio.file.Path
import java.sql.Connection
import java.time.LocalDate
import kotlin.math.abs
import queries.AttributionRow
import queries.naiveAttribution
import queries.optimizedAttribution

data class BenchResult(val label: String, val medianMillis: Long, val runsMillis: List<Long>)

private fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - start) / 1_000_000
}

private fun bench(label: String, warmup: Int, runs: Int, echo: (String) -> Unit, block: () -> Any?): BenchResult {
    repeat(warmup) { block() }
    val times = (1..runs).map {
        val (_, ms) = timed(block)
        echo("    $label run $it/$runs: ${ms} ms")
        ms
    }
    return BenchResult(label, times.sorted()[times.size / 2], times)
}

fun maxAbsDiff(a: List<AttributionRow>, b: List<AttributionRow>): Double {
    require(a.size == b.size) { "result sizes differ: ${a.size} vs ${b.size}" }
    var maxDiff = 0.0
    for ((x, y) in a.zip(b)) {
        require(x.portfolioId == y.portfolioId && x.sector == y.sector) { "row keys differ: $x vs $y" }
        maxDiff = maxOf(
            maxDiff,
            abs(x.allocation - y.allocation),
            abs(x.selection - y.selection),
            abs(x.interaction - y.interaction),
        )
    }
    return maxDiff
}

fun runBench(
    conn: Connection,
    parquetDir: Path?,
    runs: Int,
    echo: (String) -> Unit,
) {
    val (from, to) = conn.createStatement().use { st ->
        st.executeQuery("SELECT min(date), max(date) FROM benchmark_returns").use { rs ->
            rs.next()
            rs.getDate(1).toLocalDate() to rs.getDate(2).toLocalDate()
        }
    }
    val positionRows = conn.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM positions_daily").use { rs -> rs.next(); rs.getLong(1) }
    }
    echo("Benchmark: full-range attribution, all portfolios, $from .. $to")
    echo("positions_daily rows: %,d".format(positionRows))
    echo("")

    echo("Verifying the two paths agree before timing them...")
    val naiveResult = naiveAttribution(conn, from, to)
    val optimizedResult = optimizedAttribution(conn, from, to)
    val diff = maxAbsDiff(naiveResult, optimizedResult)
    check(diff < 1e-8) { "naive and optimized results diverge: max abs diff $diff" }
    echo("  OK - identical results (max abs effect diff: %.2e)".format(diff))
    echo("")

    val results = mutableListOf<BenchResult>()
    echo("Timing (1 warmup + $runs timed runs each, median reported):")
    results += bench("naive (JDBC transfer + JVM hash aggregation)", 1, runs, echo) {
        naiveAttribution(conn, from, to)
    }
    results += bench("optimized (single-pass SQL in DuckDB)", 1, runs, echo) {
        optimizedAttribution(conn, from, to)
    }
    if (parquetDir != null) {
        val source = "read_parquet('${parquetDir.resolve("positions_daily.parquet")}')"
        results += bench("optimized (SQL directly over Parquet)", 1, runs, echo) {
            optimizedAttribution(conn, from, to, positionsSource = source)
        }
    }

    echo("")
    echo("| Variant | Median | Runs |")
    echo("|---|---|---|")
    val baseline = results.first().medianMillis.toDouble()
    for (r in results) {
        val speedup = if (r === results.first()) "" else " (%.1fx faster)".format(baseline / r.medianMillis)
        echo("| ${r.label} | ${r.medianMillis} ms$speedup | ${r.runsMillis.joinToString(", ")} |")
    }
    echo("")
    val runtime = Runtime.getRuntime()
    echo(
        "Hardware: ${runtime.availableProcessors()} cores, JVM max heap %.1f GB, %s %s"
            .format(
                runtime.maxMemory() / 1e9,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
            ),
    )
}
