package etl

import datagen.DataGenerator
import datagen.GenParams
import datagen.tradingCalendar
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import queries.optimizedAttributionDaily

class ImportTest {

    private val params = GenParams(seed = 42L, securities = 40, portfolios = 2, tradingDays = 15, scale = 0.05)
    private val calendar = tradingCalendar(params.startDate, params.tradingDays)

    @OptIn(ExperimentalPathApi::class)
    private fun withDb(block: (Connection, Path) -> Unit) {
        val tmp = Files.createTempDirectory("brinson-import")
        try {
            openInMemory().use { mem ->
                DataGenerator(params).generateInto(mem)
                writeParquet(mem, tmp.resolve("pq"))
            }
            openDatabase(tmp.resolve("d.duckdb")).use { conn ->
                loadFromParquet(conn, tmp.resolve("pq"))
                block(conn, tmp)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Two tickers over calendar days 1..10, AAPL quantity jumps on day 6 (a deposit). */
    private fun goldenCsv(): String {
        val days = calendar.subList(1, 11)
        val sb = StringBuilder("date,ticker,sector,quantity,price,dividend\n")
        days.forEachIndexed { i, d ->
            val qa = if (i >= 5) 150.0 else 100.0
            val pa = 100.0 + i // AAPL price walks 100..109
            sb.append("$d,AAPL,Information Technology,$qa,$pa,0\n")
            val px = 50.0 + i * 0.5 // XOM 50..54.5; dividend on day 4
            val div = if (i == 3) 0.25 else 0.0
            sb.append("$d,XOM,Energy,200,$px,$div\n")
        }
        return sb.toString()
    }

    private fun write(tmp: Path, text: String): Path =
        tmp.resolve("pf.csv").also { it.toFile().writeText(text) }

    @Test
    fun `import lands a portfolio that satisfies the engine invariants`() {
        withDb { conn, tmp ->
            val r = importPortfolio(conn, write(tmp, goldenCsv()), "My Account")
            assertEquals(3, r.portfolioId)
            assertEquals(20, r.positionRows)
            assertEquals(2, r.tickers)
            assertEquals(2, r.newSecurities) // AAPL/XOM are not in the synthetic universe
            assertEquals(calendar[1], r.from)
            assertEquals(calendar[10], r.to)
            // The day-6 AAPL jump: 50 extra shares at the prior close (104) = 5,200.
            assertEquals(1, r.flowDays)
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT amount FROM transactions WHERE portfolio_id = 3 AND type = 'CASHFLOW'",
                ).use { rs ->
                    rs.next()
                    assertEquals(50.0 * 104.0, rs.getDouble(1), 1e-9)
                }
                // Derived total returns: one per ticker per day after the first (9 x 2),
                // including the dividend on XOM's day 4: (51.5 + 0.25)/51.0 - 1.
                st.executeQuery(
                    "SELECT count(*) FROM benchmark_returns br JOIN securities s USING (security_id) WHERE s.ticker IN ('AAPL','XOM')",
                ).use { rs -> rs.next(); assertEquals(18L, rs.getLong(1)) }
                st.executeQuery(
                    """SELECT ret FROM benchmark_returns br JOIN securities s USING (security_id)
                       WHERE s.ticker = 'XOM' AND br.date = DATE '${calendar[4]}'""",
                ).use { rs -> rs.next(); assertEquals((51.5 + 0.25) / 51.0 - 1.0, rs.getDouble(1), 1e-12) }
            }
            // The imported portfolio obeys the per-day attribution invariant.
            val byDay = optimizedAttributionDaily(conn, calendar[2], calendar[10], portfolioFilter = 3)
                .groupBy { it.date }
            assertTrue(byDay.isNotEmpty())
            for ((day, rows) in byDay) {
                val rp = rows.sumOf { it.wp * it.rp }
                val rb = rows.first().rbTotal
                val sum = rows.sumOf { it.allocation + it.selection + it.interaction }
                assertEquals(rp - rb, sum, 1e-10, "invariant broken at $day")
            }
            // And TWR machinery works end to end.
            val vals = queries.dailyValuations(conn, 3, calendar[1], calendar[10])
            assertEquals(9, vals.size)
            vals.forEach { v -> engine.subPeriodReturn(v.mvBegin, v.mvEnd, v.externalFlow) }
        }
    }

    @Test
    fun `re-import reuses the security master without duplicating returns`() {
        withDb { conn, tmp ->
            importPortfolio(conn, write(tmp, goldenCsv()), "First")
            val r2 = importPortfolio(conn, write(tmp, goldenCsv()), "Second")
            assertEquals(4, r2.portfolioId)
            assertEquals(0, r2.newSecurities)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM securities WHERE ticker = 'AAPL'")
                    .use { rs -> rs.next(); assertEquals(1L, rs.getLong(1)) }
                st.executeQuery(
                    "SELECT count(*) FROM benchmark_returns br JOIN securities s USING (security_id) WHERE s.ticker = 'AAPL'",
                ).use { rs -> rs.next(); assertEquals(9L, rs.getLong(1)) }
            }
        }
    }

    @Test
    fun `rejections leave the database untouched`() {
        withDb { conn, tmp ->
            fun count(table: String): Long = conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
            }
            val before = listOf("portfolios", "securities", "positions_daily", "benchmark_returns").map(::count)

            // unknown sector
            assertFailsWith<IllegalArgumentException> {
                importPortfolio(conn, write(tmp, goldenCsv().replace("Energy", "Petroleum")), "x")
            }
            // calendar gap: drop one full day
            val gap = goldenCsv().lineSequence().filterNot { it.startsWith(calendar[5].toString()) }
                .joinToString("\n")
            assertFailsWith<IllegalArgumentException> { importPortfolio(conn, write(tmp, gap), "x") }
            // incomplete matrix: drop a single ticker-day
            val hole = goldenCsv().lineSequence().filterNot { it.startsWith("${calendar[5]},XOM") }
                .joinToString("\n")
            assertFailsWith<IllegalArgumentException> { importPortfolio(conn, write(tmp, hole), "x") }
            // non-positive price (109.0 is AAPL's final price and appears nowhere else)
            assertFailsWith<IllegalArgumentException> {
                importPortfolio(conn, write(tmp, goldenCsv().replace(",109.0,", ",-5,")), "x")
            }

            val after = listOf("portfolios", "securities", "positions_daily", "benchmark_returns").map(::count)
            assertEquals(before, after, "failed imports must roll back completely")
        }
    }
}
