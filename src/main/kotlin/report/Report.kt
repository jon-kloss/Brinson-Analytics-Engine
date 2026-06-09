package report

import engine.annualizedOrNull
import engine.linkGeometrically
import engine.subPeriodReturn
import java.sql.Connection
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import queries.dailyValuations
import queries.benchmarkDailyReturns
import queries.optimizedAttribution
import queries.securityContributions

private fun pct(x: Double): String = "%+.2f%%".format(x * 100)
private fun bps(x: Double): String = "%+7.1f".format(x * 10_000)

fun buildReport(conn: Connection, portfolioId: Int, from: LocalDate, to: LocalDate): String {
    val sb = StringBuilder()

    val valuations = dailyValuations(conn, portfolioId, from, to)
    require(valuations.isNotEmpty()) { "No data for portfolio $portfolioId in $from..$to" }
    val portReturns = valuations.map { subPeriodReturn(it.mvBegin, it.mvEnd, it.externalFlow) }
    val twr = linkGeometrically(portReturns)
    val benchReturns = benchmarkDailyReturns(conn, valuations.first().date, to)
    val benchTwr = linkGeometrically(benchReturns)
    val calendarDays = ChronoUnit.DAYS.between(valuations.first().date.minusDays(1), to)

    val name = conn.createStatement().use { st ->
        st.executeQuery("SELECT name FROM portfolios WHERE portfolio_id = $portfolioId").use { rs ->
            if (rs.next()) rs.getString(1) else "Portfolio $portfolioId"
        }
    }

    sb.appendLine("=".repeat(78))
    sb.appendLine("$name  |  ${valuations.first().date} .. $to  (${valuations.size} trading days)")
    sb.appendLine("=".repeat(78))
    sb.appendLine()
    fun twrLine(label: String, r: Double) {
        val ann = annualizedOrNull(r, calendarDays)
        sb.appendLine(
            "  %-22s %10s%s".format(
                label, pct(r),
                if (ann != null) "   (annualized %s)".format(pct(ann)) else "",
            ),
        )
    }
    twrLine("TWR (portfolio):", twr)
    twrLine("TWR (benchmark):", benchTwr)
    sb.appendLine("  %-22s %10s   (geometric: %s)".format(
        "Active return:", pct(twr - benchTwr), pct((1 + twr) / (1 + benchTwr) - 1),
    ))
    sb.appendLine()

    val rows = optimizedAttribution(conn, valuations.first().date, to, portfolioFilter = portfolioId)
    sb.appendLine("Brinson-Fachler attribution by sector  (sum of daily effects, in bps)")
    sb.appendLine("-".repeat(78))
    sb.appendLine(
        "  %-24s %6s %6s %9s %9s %9s %9s".format(
            "Sector", "wp", "wb", "Alloc", "Select", "Interact", "Total",
        ),
    )
    for (r in rows.sortedByDescending { it.total }) {
        sb.appendLine(
            "  %-24s %5.1f%% %5.1f%% %9s %9s %9s %9s".format(
                r.sector, r.avgPortfolioWeight * 100, r.avgBenchmarkWeight * 100,
                bps(r.allocation), bps(r.selection), bps(r.interaction), bps(r.total),
            ),
        )
    }
    sb.appendLine("-".repeat(78))
    sb.appendLine(
        "  %-24s %6s %6s %9s %9s %9s %9s".format(
            "TOTAL", "100%", "100%",
            bps(rows.sumOf { it.allocation }), bps(rows.sumOf { it.selection }),
            bps(rows.sumOf { it.interaction }), bps(rows.sumOf { it.total }),
        ),
    )
    sb.appendLine("  (totals equal the arithmetic sum of daily active returns; see METHODOLOGY.md)")
    sb.appendLine()

    val contribs = securityContributions(conn, portfolioId, valuations.first().date, to)
    sb.appendLine("Top contributors (sum of daily w*r, in bps)")
    sb.appendLine("-".repeat(78))
    for (c in contribs.take(5)) {
        sb.appendLine("  %-8s %-24s %9s".format(c.ticker, c.sector, bps(c.contribution)))
    }
    sb.appendLine("  ...")
    for (c in contribs.takeLast(3)) {
        sb.appendLine("  %-8s %-24s %9s".format(c.ticker, c.sector, bps(c.contribution)))
    }
    return sb.toString()
}
