package queries

import engine.linkGeometrically
import engine.subPeriodReturn
import etl.openInMemory
import java.sql.Connection
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The METHODOLOGY.md golden example pushed through the actual SQL paths: the engine must
 * reproduce the hand-calculated numbers end to end, not just in pure Kotlin.
 */
class GoldenSqlTest {

    private val tolerance = 1e-12
    private val day0: LocalDate = LocalDate.parse("2024-01-02")
    private val day1: LocalDate = LocalDate.parse("2024-01-03")
    private val day3: LocalDate = LocalDate.parse("2024-01-05")

    private val conn: Connection = openInMemory().also { c ->
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE securities AS FROM (VALUES
                    (1, 'AAA', 'Tech', 'EQUITY'), (2, 'BBB', 'Tech', 'EQUITY'),
                    (3, 'CCC', 'Energy', 'EQUITY'), (4, 'DDD', 'Energy', 'EQUITY')
                ) t(security_id, ticker, sector, asset_class);

                CREATE TABLE portfolios AS FROM (VALUES
                    (1, 'Golden', DATE '2024-01-02', 'USD')
                ) t(portfolio_id, name, inception_date, base_currency);

                CREATE TABLE positions_daily AS
                SELECT date, 1 AS portfolio_id, security_id, quantity, price,
                       quantity * price AS market_value
                FROM (VALUES
                    (DATE '2024-01-02', 1, 100.0, 10.00), (DATE '2024-01-02', 2, 50.0, 20.00),
                    (DATE '2024-01-02', 3, 200.0,  5.00), (DATE '2024-01-02', 4, 25.0, 40.00),
                    (DATE '2024-01-03', 1, 100.0, 10.50), (DATE '2024-01-03', 2, 50.0, 19.00),
                    (DATE '2024-01-03', 3, 200.0,  5.10), (DATE '2024-01-03', 4, 25.0, 40.80),
                    (DATE '2024-01-04', 1, 100.0, 11.00), (DATE '2024-01-04', 2, 50.0, 19.00),
                    (DATE '2024-01-04', 3, 400.0,  5.25), (DATE '2024-01-04', 4, 25.0, 40.00),
                    (DATE '2024-01-05', 1, 100.0, 11.11), (DATE '2024-01-05', 2, 50.0, 19.38),
                    (DATE '2024-01-05', 3, 400.0,  5.20), (DATE '2024-01-05', 4, 25.0, 41.00)
                ) t(date, security_id, quantity, price);

                CREATE TABLE transactions AS FROM (VALUES
                    (DATE '2024-01-04', 1, NULL::INTEGER, 'CASHFLOW', 0.0, 1020.0)
                ) t(date, portfolio_id, security_id, type, quantity, amount);

                CREATE TABLE benchmark_weights AS FROM (VALUES
                    (DATE '2024-01-03', 1, 0.45), (DATE '2024-01-03', 2, 0.15),
                    (DATE '2024-01-03', 3, 0.25), (DATE '2024-01-03', 4, 0.15)
                ) t(date, security_id, weight);

                CREATE TABLE benchmark_returns AS FROM (VALUES
                    (DATE '2024-01-03', 1,  0.05), (DATE '2024-01-03', 2, -0.05),
                    (DATE '2024-01-03', 3,  0.02), (DATE '2024-01-03', 4,  0.02)
                ) t(date, security_id, ret);
                """,
            )
        }
    }

    @AfterTest
    fun tearDown() = conn.close()

    @Test
    fun `TWR through SQL valuations matches the hand calculation`() {
        val valuations = dailyValuations(conn, 1, day1, day3)
        assertEquals(3, valuations.size)
        val returns = valuations.map { subPeriodReturn(it.mvBegin, it.mvEnd, it.externalFlow) }
        assertEquals(0.01, returns[0], tolerance)
        assertEquals(90.0 / 5060.0, returns[1], tolerance)
        assertEquals(35.0 / 5150.0, returns[2], tolerance)
        assertEquals(3537.0 / 101200.0, linkGeometrically(returns), tolerance)
    }

    @Test
    fun `attribution through SQL matches the hand calculation`() {
        val rows = optimizedAttributionDaily(conn, day1, day1).associateBy { it.sector }
        assertEquals(2, rows.size)

        val tech = rows.getValue("Tech")
        assertEquals(0.50, tech.wp, tolerance)
        assertEquals(0.60, tech.wb, tolerance)
        assertEquals(0.000, tech.rp, tolerance)
        assertEquals(0.025, tech.rbI, tolerance)
        assertEquals(0.023, tech.rbTotal, tolerance)
        assertEquals(-0.0002, tech.allocation, tolerance)
        assertEquals(-0.0150, tech.selection, tolerance)
        assertEquals(+0.0025, tech.interaction, tolerance)

        val energy = rows.getValue("Energy")
        assertEquals(0.50, energy.wp, tolerance)
        assertEquals(0.40, energy.wb, tolerance)
        assertEquals(-0.0003, energy.allocation, tolerance)
        assertEquals(0.0, energy.selection, tolerance)
        assertEquals(0.0, energy.interaction, tolerance)

        val sumOfEffects = rows.values.sumOf { it.allocation + it.selection + it.interaction }
        assertEquals(-0.013, sumOfEffects, tolerance)
    }

    @Test
    fun `naive path reproduces the same golden numbers`() {
        val rows = naiveAttribution(conn, day1, day1).associateBy { it.sector }
        assertEquals(-0.0002, rows.getValue("Tech").allocation, tolerance)
        assertEquals(-0.0150, rows.getValue("Tech").selection, tolerance)
        assertEquals(+0.0025, rows.getValue("Tech").interaction, tolerance)
        assertEquals(-0.0003, rows.getValue("Energy").allocation, tolerance)
    }

    @Test
    fun `contributions through SQL match the hand calculation`() {
        val c = securityContributions(conn, 1, day1, day1).associateBy { it.ticker }
        assertEquals(+0.0125, c.getValue("AAA").contribution, tolerance)
        assertEquals(-0.0125, c.getValue("BBB").contribution, tolerance)
        assertEquals(+0.0050, c.getValue("CCC").contribution, tolerance)
        assertEquals(+0.0050, c.getValue("DDD").contribution, tolerance)
        assertEquals(0.01, c.values.sumOf { it.contribution }, tolerance)
    }
}
