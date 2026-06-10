package etl

import datagen.DataGenerator
import datagen.GenParams
import datagen.tradingCalendar
import engine.linkGeometrically
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import queries.optimizedAttributionDaily

class ModelTest {

    // 60 trading days spans three calendar months from 2024-01-02, so monthly
    // rebalancing fires at least twice.
    private val params = GenParams(seed = 7L, securities = 30, portfolios = 1, tradingDays = 60, scale = 0.05)
    private val calendar = tradingCalendar(params.startDate, params.tradingDays)

    @OptIn(ExperimentalPathApi::class)
    private fun withDb(block: (Connection) -> Unit) {
        val tmp = Files.createTempDirectory("brinson-model")
        try {
            openInMemory().use { mem ->
                DataGenerator(params).generateInto(mem)
                writeParquet(mem, tmp.resolve("pq"))
            }
            openDatabase(tmp.resolve("d.duckdb")).use { conn ->
                loadFromParquet(conn, tmp.resolve("pq"))
                block(conn)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun count(conn: Connection, sql: String): Long =
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    private fun securityReturns(conn: Connection, ticker: String): Map<String, Double> =
        conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT r.date, r.ret FROM benchmark_returns r
                   JOIN securities s USING (security_id) WHERE s.ticker = '$ticker' ORDER BY 1""",
            ).use { rs ->
                buildMap { while (rs.next()) put(rs.getDate(1).toString(), rs.getDouble(2)) }
            }
        }

    @Test
    fun `single-ticker buy-and-hold reproduces the security's compounded return exactly`() {
        withDb { conn ->
            val r = buildModelPortfolio(conn, "One Stock", listOf("SEC001" to 1.0), Rebalance.NONE)
            assertEquals(2, r.portfolioId)
            assertEquals(params.tradingDays.toLong(), r.positionRows) // dates x 1 ticker
            assertEquals(0, r.flowDays) // self-financing: no estimated external flows
            assertEquals(0, r.newSecurities) // models only reference existing securities

            // The model's daily portfolio return must BE the security's return, and
            // the linked TWR its compounded return, from the second calendar date on
            // (the first has no prior close to weight from).
            val rets = securityReturns(conn, "SEC001")
            val daily = optimizedAttributionDaily(conn, calendar[2], calendar.last(), portfolioFilter = 2)
            val rp = daily.groupBy { it.date }.toSortedMap().map { (d, rows) ->
                val x = rows.sumOf { it.wp * it.rp }
                assertEquals(rets.getValue(d.toString()), x, 1e-12, "rp on $d")
                x
            }
            val expected = (2 until calendar.size).fold(1.0) { acc, i ->
                acc * (1.0 + rets.getValue(calendar[i].toString()))
            } - 1.0
            assertEquals(expected, linkGeometrically(rp), 1e-12)
        }
    }

    @Test
    fun `monthly rebalance restores target weights at the prior close`() {
        withDb { conn ->
            val targets = listOf("SEC001" to 0.5, "SEC002" to 0.3, "SEC003" to 0.2)
            val r = buildModelPortfolio(conn, "Three Stock Model", targets, Rebalance.MONTHLY)
            assertEquals(0, r.flowDays)
            assertEquals(params.tradingDays * 3L, r.positionRows)

            // Rebalance orders are sized on the prior close: on the month's first
            // trading day the new quantities valued at the prior close hit the
            // targets exactly (the engine's pre-trade weight basis catches up the
            // next day — same one-day convention as estimated flows).
            val rebalanceDate = (1 until calendar.size)
                .first { calendar[it].month != calendar[it - 1].month }
                .let { calendar[it] }
            conn.createStatement().use { st ->
                st.executeQuery(
                    """WITH px AS (
                         SELECT security_id, quantity, date,
                                lag(price) OVER (PARTITION BY security_id ORDER BY date) AS p_prev
                         FROM positions_daily WHERE portfolio_id = ${r.portfolioId}
                       )
                       SELECT s.ticker, quantity * p_prev / sum(quantity * p_prev) OVER ()
                       FROM px JOIN securities s USING (security_id)
                       WHERE date = DATE '$rebalanceDate'""",
                ).use { rs ->
                    val seen = HashMap<String, Double>()
                    while (rs.next()) seen[rs.getString(1)] = rs.getDouble(2)
                    for ((t, w) in targets) {
                        assertEquals(w, seen.getValue(t), 1e-12, "weight of $t on $rebalanceDate")
                    }
                }
            }

            // Between rebalances weights drift: the day before the rebalance they
            // should not all still equal the targets.
            val driftDates = conn.createStatement().use { st ->
                st.executeQuery(
                    """WITH basis AS (
                         SELECT security_id, date,
                                lag(market_value) OVER (PARTITION BY security_id ORDER BY date) AS b
                         FROM positions_daily WHERE portfolio_id = ${r.portfolioId}
                       ), w AS (
                         SELECT date, b / sum(b) OVER (PARTITION BY date) AS w, s.ticker
                         FROM basis JOIN securities s USING (security_id) WHERE b IS NOT NULL
                       )
                       SELECT count(DISTINCT date) FROM w WHERE abs(w - 0.5) > 1e-6 AND ticker = 'SEC001'""",
                ).use { rs -> rs.next(); rs.getLong(1) }
            }
            assertTrue(driftDates > 0, "weights should drift between rebalances")

            // And the model satisfies the engine's per-day attribution invariant.
            val byDay = optimizedAttributionDaily(conn, calendar[2], calendar.last(), portfolioFilter = r.portfolioId)
                .groupBy { it.date }
            assertTrue(byDay.isNotEmpty())
            for ((day, rows) in byDay) {
                val effects = rows.sumOf { it.allocation + it.selection + it.interaction }
                val active = rows.sumOf { it.wp * it.rp } - rows.first().rbTotal
                assertTrue(abs(effects - active) < 1e-10, "invariant on $day")
            }
        }
    }

    @Test
    fun `weights may be percents and rebalance may be quarterly`() {
        withDb { conn ->
            val r = buildModelPortfolio(
                conn, "Percent Model", listOf("SEC004" to 60.0, "SEC005" to 40.0), Rebalance.QUARTERLY,
            )
            assertEquals(0, r.flowDays)
            assertEquals(2, r.tickers.toInt())
        }
    }

    @Test
    fun `invalid models are rejected before anything is written`() {
        withDb { conn ->
            val before = count(conn, "SELECT count(*) FROM portfolios")
            fun rejected(msg: String, block: () -> Unit) {
                val e = assertFailsWith<IllegalArgumentException>(message = msg, block = block)
                assertTrue(e.message != null, msg)
            }
            rejected("empty") { buildModelPortfolio(conn, "X", emptyList(), Rebalance.NONE) }
            rejected("unknown ticker") {
                buildModelPortfolio(conn, "X", listOf("NOPE" to 1.0), Rebalance.NONE)
            }
            rejected("bad sum") {
                buildModelPortfolio(conn, "X", listOf("SEC001" to 0.4, "SEC002" to 0.2), Rebalance.NONE)
            }
            rejected("duplicate") {
                buildModelPortfolio(conn, "X", listOf("SEC001" to 0.5, "SEC001" to 0.5), Rebalance.NONE)
            }
            rejected("negative") {
                buildModelPortfolio(conn, "X", listOf("SEC001" to 1.5, "SEC002" to -0.5), Rebalance.NONE)
            }
            assertEquals(before, count(conn, "SELECT count(*) FROM portfolios"))
            assertEquals(0, count(conn, "SELECT count(*) FROM positions_daily WHERE portfolio_id > ${params.portfolios}"))
        }
    }

    @Test
    fun `eligible securities excludes partial-history tickers`() {
        withDb { conn ->
            val eligible = eligibleSecurities(conn)
            assertEquals(params.securities, eligible.size)
            assertTrue(eligible.all { it.first.startsWith("SEC") })
            // A partial-range import mints a security that a model must not use.
            val days = calendar.subList(5, 10)
            val csv = StringBuilder("date,ticker,sector,quantity,price,dividend\n")
            days.forEachIndexed { i, d -> csv.append("$d,PART,Energy,10,${100.0 + i},0\n") }
            val f = Files.createTempFile("part", ".csv")
            try {
                f.toFile().writeText(csv.toString())
                importPortfolio(conn, f, "Partial")
            } finally {
                Files.deleteIfExists(f)
            }
            assertEquals(eligible, eligibleSecurities(conn)) // PART is not eligible
            assertFailsWith<IllegalArgumentException> {
                buildModelPortfolio(conn, "X", listOf("PART" to 1.0), Rebalance.NONE)
            }
        }
    }
}
