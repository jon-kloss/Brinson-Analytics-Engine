package etl

import java.nio.file.Files
import java.sql.Connection
import java.time.LocalDate

/** How often a model portfolio is brought back to its target weights. */
enum class Rebalance { NONE, MONTHLY, QUARTERLY }

/**
 * Builds a model portfolio — the front-office workflow platforms call
 * "model-based investing": pick securities, assign target weights, and let the
 * platform maintain the allocation. The result lands through [importPortfolio],
 * so it gets the same validation, flow estimation, and transactional semantics
 * as an uploaded account, and every downstream consumer picks it up unchanged.
 *
 * Mechanics:
 *  - Each security's daily total returns (benchmark_returns) are compounded into
 *    a price index starting at 100 on the first calendar date. Position market
 *    values therefore move exactly with the returns attribution will use.
 *  - Day one buys each target weight's share of a nominal $1,000,000.
 *  - Between rebalances quantities are constant and weights drift with returns.
 *  - On rebalance dates (first trading day of the month/quarter) quantities are
 *    reset to target weights sized at the PRIOR CLOSE — the industry practice of
 *    sizing orders on yesterday's close, and the same valuation the importer uses
 *    to estimate flows, so sells fund buys exactly: the estimated external flow
 *    is zero by construction and TWR needs no flow adjustment. (The engine's
 *    pre-trade weight basis means the new allocation earns returns from the
 *    following day, mirroring the documented one-day flow convention.)
 *
 * Weights may be given as fractions (sum ~ 1) or percents (sum ~ 100); they are
 * normalized to sum to exactly 1. Only securities with full-history returns are
 * eligible (see [eligibleSecurities]) — a model needs a return for every day.
 */
fun buildModelPortfolio(
    conn: Connection,
    name: String,
    holdings: List<Pair<String, Double>>,
    rebalance: Rebalance,
): ImportResult {
    require(holdings.isNotEmpty()) { "a model needs at least one holding" }
    require(holdings.size <= 50) { "at most 50 holdings (got ${holdings.size})" }
    val dup = holdings.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
    require(dup.isEmpty()) { "duplicate tickers: ${dup.sorted()}" }
    require(holdings.all { it.second > 0 }) { "every weight must be positive" }

    val raw = holdings.sumOf { it.second }
    val sum = if (raw in 90.0..110.0) raw / 100.0 else raw // percents or fractions
    require(sum in 0.999..1.001) {
        "weights must sum to 1 (or 100%): got ${holdings.sumOf { it.second }}"
    }
    val weights = holdings.map { (t, w) -> t to (if (raw in 90.0..110.0) w / 100.0 else w) / sum }

    val dates = conn.createStatement().use { st ->
        st.executeQuery("SELECT DISTINCT date FROM benchmark_returns ORDER BY 1").use { rs ->
            buildList<LocalDate> { while (rs.next()) add(rs.getDate(1).toLocalDate()) }
        }
    }
    require(dates.size >= 2) { "the database has no return history to build a model on" }
    val dateIdx = dates.withIndex().associate { (i, d) -> d to i }

    // Returns + sector per chosen ticker. PreparedStatement binds: tickers are
    // user-supplied strings (see the literal-interpolation warning in queries/).
    val marks = weights.joinToString(",") { "?" }
    val rets = HashMap<String, DoubleArray>()
    val sector = HashMap<String, String>()
    conn.prepareStatement(
        """SELECT s.ticker, s.sector, r.date, r.ret
           FROM securities s JOIN benchmark_returns r USING (security_id)
           WHERE s.ticker IN ($marks)""",
    ).use { ps ->
        weights.forEachIndexed { i, (t, _) -> ps.setString(i + 1, t) }
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val t = rs.getString(1)
                sector[t] = rs.getString(2)
                rets.getOrPut(t) { DoubleArray(dates.size) { Double.NaN } }[
                    dateIdx.getValue(rs.getDate(3).toLocalDate()),
                ] = rs.getDouble(4)
            }
        }
    }
    val unknown = weights.map { it.first }.filter { it !in sector }
    require(unknown.isEmpty()) { "unknown tickers: ${unknown.sorted()}" }
    // The first calendar date's return looks back before the data starts; a model
    // only compounds from day two, so coverage is required on dates[1..].
    val partial = weights.map { it.first }
        .filter { t -> (1 until dates.size).any { rets.getValue(t)[it].isNaN() } }
    require(partial.isEmpty()) {
        "no full-history returns for: ${partial.sorted()} — models can only use " +
            "securities covering the whole calendar (see /api/securities)"
    }

    // Total-return price index (base 100) and the quantity path.
    val tickers = weights.map { it.first }
    val w = weights.map { it.second }.toDoubleArray()
    val px = Array(tickers.size) { i ->
        DoubleArray(dates.size).also { p ->
            p[0] = 100.0
            for (t in 1 until dates.size) p[t] = p[t - 1] * (1.0 + rets.getValue(tickers[i])[t])
        }
    }
    val initialValue = 1_000_000.0
    val qty = Array(tickers.size) { DoubleArray(dates.size) }
    for (i in tickers.indices) qty[i][0] = w[i] * initialValue / px[i][0]
    for (t in 1 until dates.size) {
        val boundary = when (rebalance) {
            Rebalance.NONE -> false
            Rebalance.MONTHLY -> dates[t].month != dates[t - 1].month
            Rebalance.QUARTERLY -> dates[t].month != dates[t - 1].month &&
                (dates[t].monthValue - 1) % 3 == 0
        }
        if (boundary) {
            var value = 0.0 // marked at the prior close, where the trades happen
            for (i in tickers.indices) value += qty[i][t - 1] * px[i][t - 1]
            for (i in tickers.indices) qty[i][t] = w[i] * value / px[i][t - 1]
        } else {
            for (i in tickers.indices) qty[i][t] = qty[i][t - 1]
        }
    }

    // Compile to the importer's holdings contract; it owns validation + commit.
    val csv = StringBuilder("date,ticker,sector,quantity,price,dividend\n")
    for (t in dates.indices) {
        for (i in tickers.indices) {
            csv.append(dates[t]).append(',').append(tickers[i]).append(',')
                .append(sector.getValue(tickers[i])).append(',')
                .append(qty[i][t]).append(',').append(px[i][t]).append(",0\n")
        }
    }
    val tmp = Files.createTempFile("brinson-model", ".csv")
    try {
        tmp.toFile().writeText(csv.toString())
        return importPortfolio(conn, tmp, name)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/**
 * Securities a model may hold: those with a return on every calendar date after
 * the first (the synthetic benchmark universe qualifies; securities introduced
 * by a partial-range CSV import do not). Returned as (ticker, sector).
 */
fun eligibleSecurities(conn: Connection): List<Pair<String, String>> =
    conn.createStatement().use { st ->
        st.executeQuery(
            """SELECT s.ticker, s.sector
               FROM securities s
               JOIN benchmark_returns r ON r.security_id = s.security_id
                AND r.date > (SELECT min(date) FROM benchmark_returns)
               GROUP BY s.ticker, s.sector
               HAVING count(*) = (SELECT count(DISTINCT date) - 1 FROM benchmark_returns)
               ORDER BY s.ticker""",
        ).use { rs ->
            buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
        }
    }
