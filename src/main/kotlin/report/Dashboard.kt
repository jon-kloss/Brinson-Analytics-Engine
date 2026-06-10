package report

import engine.annualizedTrackingError
import engine.informationRatio
import engine.linkGeometrically
import engine.maxDrawdown
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.LocalDate
import queries.SecurityContribution
import queries.optimizedAttributionDaily
import queries.securityContributions

/**
 * Bakes every portfolio's performance, Cariño-linked attribution, risk metrics,
 * contributors, and weekly sector weights into a self-contained static site
 * suitable for GitHub Pages: data.json (built here) plus the design assets
 * (index.html, styles.css, dashboard.js) bundled as classpath resources.
 *
 * Date-range presets, theme, hero KPI, and expand-to-modal interactions all run
 * client-side in dashboard.js against the baked daily return series, using the
 * formulas documented in METHODOLOGY.md.
 */
fun writeDashboard(conn: Connection, outDir: Path, echo: (String) -> Unit) {
    Files.createDirectories(outDir)
    val json = buildDashboardJson(conn)
    outDir.resolve("data.json").toFile().writeText(json)
    for (asset in listOf("index.html", "styles.css", "dashboard.js", "guide.html")) {
        val resource = object {}.javaClass.getResourceAsStream("/dashboard/$asset")
            ?: error("missing bundled dashboard resource: $asset")
        resource.use { outDir.resolve(asset).toFile().writeBytes(it.readBytes()) }
    }
    echo("Dashboard written to $outDir (data.json: %,d bytes)".fmt(json.length))
}

private fun jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

private fun num(x: Double): String {
    require(x.isFinite()) { "non-finite value in dashboard data: $x" }
    return "%.8f".fmt(x)
}

/** Compact sector labels for chart axes/legends, where full GICS names collide. */
private val SECTOR_SHORT = mapOf(
    "Information Technology" to "Info Tech",
    "Communication Services" to "Comm. Svcs.",
    "Consumer Discretionary" to "Cons. Disc.",
    "Consumer Staples" to "Cons. Staples",
)

fun buildDashboardJson(conn: Connection): String {
    val (from, to) = conn.createStatement().use { st ->
        st.executeQuery("SELECT min(date), max(date) FROM benchmark_returns").use { rs ->
            rs.next()
            rs.getDate(1).toLocalDate() to rs.getDate(2).toLocalDate()
        }
    }
    val names = conn.createStatement().use { st ->
        st.executeQuery("SELECT portfolio_id, name FROM portfolios ORDER BY 1").use { rs ->
            buildMap<Int, String> { while (rs.next()) put(rs.getInt(1), rs.getString(2)) }
        }
    }

    // One pass over the big query for all portfolios; everything below is in-memory.
    val daily = optimizedAttributionDaily(conn, from, to)
    val dates = daily.asSequence().map { it.date }.distinct().sorted().toList()
    val dateIndex = dates.withIndex().associate { (i, d) -> d to i }
    val sectors = daily.asSequence().map { it.sector }.distinct().sorted().toList()
    val byPf = daily.groupBy { it.portfolioId }.toSortedMap()

    // Benchmark daily return is portfolio-independent (rb_total per date).
    val rb = DoubleArray(dates.size)
    for (r in byPf.values.first()) rb[dateIndex.getValue(r.date)] = r.rbTotal

    // Weekly sampling for the sector-weight area chart keeps data.json small.
    val weekIdx = dates.indices.step(5).toList()

    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"from\":${jsonString(from.toString())},\"to\":${jsonString(to.toString())},")
    sb.append("\"dates\":[").append(dates.joinToString(",") { jsonString(it.toString()) }).append("],")
    sb.append("\"sectors\":[").append(sectors.joinToString(",") { jsonString(it) }).append("],")
    sb.append("\"sectorsShort\":[")
        .append(sectors.joinToString(",") { jsonString(SECTOR_SHORT[it] ?: it) }).append("],")
    sb.append("\"weekIdx\":[").append(weekIdx.joinToString(",")).append("],")
    sb.append("\"rb\":[").append(rb.joinToString(",") { num(it) }).append("],")
    sb.append("\"portfolios\":[")

    byPf.entries.forEachIndexed { pfIdx, (pf, rows) ->
        val byDate = rows.groupBy { it.date }.toSortedMap()
        val rp = DoubleArray(dates.size)
        // wp per sector per date, for the weekly weight chart.
        val wp = Array(sectors.size) { DoubleArray(dates.size) }
        val sectorIdx = sectors.withIndex().associate { (i, s) -> s to i }
        for ((date, dayRows) in byDate) {
            val t = dateIndex.getValue(date)
            rp[t] = dayRows.sumOf { it.wp * it.rp }
            for (r in dayRows) wp[sectorIdx.getValue(r.sector)][t] = r.wp
        }
        val linked = linkDailyRows(byDate.values, pf).associateBy { it.sector }
        val active = rp.zip(rb.asList()) { p, b -> p - b }
        val contribs = securityContributions(conn, pf, from, to)

        if (pfIdx > 0) sb.append(",")
        sb.append("{\"id\":$pf,\"name\":${jsonString(names[pf] ?: "Portfolio $pf")},")
        sb.append("\"rp\":[").append(rp.joinToString(",") { num(it) }).append("],")
        sb.append("\"risk\":{")
        sb.append("\"twr\":${num(linkGeometrically(rp.asList()))},")
        sb.append("\"benchTwr\":${num(linkGeometrically(rb.asList()))},")
        sb.append("\"te\":${num(annualizedTrackingError(active))},")
        sb.append("\"ir\":${informationRatio(active)?.let { num(it) } ?: "null"},")
        sb.append("\"maxDd\":${num(maxDrawdown(rp.asList()))}},")
        sb.append("\"attribution\":[")
        sb.append(
            sectors.joinToString(",") { s ->
                val r = linked.getValue(s)
                "{\"wp\":${num(r.avgPortfolioWeight)},\"wb\":${num(r.avgBenchmarkWeight)}," +
                    "\"a\":${num(r.allocation)},\"s\":${num(r.selection)},\"i\":${num(r.interaction)}}"
            },
        )
        sb.append("],")
        fun contribJson(list: List<SecurityContribution>) =
            list.joinToString(",") {
                "{\"t\":${jsonString(it.ticker)},\"s\":${jsonString(it.sector)},\"c\":${num(it.contribution)}}"
            }
        sb.append("\"top\":[").append(contribJson(contribs.take(8))).append("],")
        sb.append("\"bottom\":[").append(contribJson(contribs.takeLast(5).reversed())).append("],")
        sb.append("\"weights\":[")
        sb.append(
            sectors.indices.joinToString(",") { si ->
                "[" + weekIdx.joinToString(",") { num(wp[si][it]) } + "]"
            },
        )
        sb.append("]}")
    }
    sb.append("]}")
    return sb.toString()
}
