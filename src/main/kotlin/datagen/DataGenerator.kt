package datagen

import java.sql.Connection
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import org.duckdb.DuckDBConnection

val GICS_SECTORS = listOf(
    "Energy", "Materials", "Industrials", "Consumer Discretionary", "Consumer Staples",
    "Health Care", "Financials", "Information Technology", "Communication Services",
    "Utilities", "Real Estate",
)

data class GenParams(
    val seed: Long = 42L,
    val securities: Int = 500,
    val portfolios: Int = 50,
    /** Number of return days; day 0 is an extra inception snapshot. ~504 = 2 years. */
    val tradingDays: Int = 504,
    /** Scales holdings per portfolio. At 1.0, positions_daily exceeds 10M rows. */
    val scale: Double = 1.0,
    val startDate: LocalDate = LocalDate.of(2024, 1, 2),
) {
    /** Mean holdings per portfolio: ~420 at scale 1.0 -> 50 * 504 * ~420 ≈ 10.6M rows. */
    val meanHoldings: Int get() = (420.0 * scale).roundToInt().coerceIn(4, securities)
}

/**
 * The weekday calendar used by the generator: index 0 is the inception snapshot date,
 * indices 1..tradingDays are return days. Pure function of its arguments — callers that
 * only need the dates (tests, range defaults) should use this instead of constructing a
 * DataGenerator, whose initialization runs the full market simulation.
 */
fun tradingCalendar(startDate: LocalDate, tradingDays: Int): List<LocalDate> =
    generateSequence(startDate) { d ->
        var next = d.plusDays(1)
        while (next.dayOfWeek == DayOfWeek.SATURDAY || next.dayOfWeek == DayOfWeek.SUNDAY) {
            next = next.plusDays(1)
        }
        next
    }.take(tradingDays + 1).toList()

/**
 * Simulates the market once (prices via geometric Brownian motion with sector-correlated
 * drift, occasional dividends, a buy-and-hold benchmark index), then simulates each
 * portfolio's holdings through time and appends everything straight into DuckDB tables.
 *
 * Per-day portfolio mechanics (these keep the METHODOLOGY.md conventions exact):
 *  1. External flows arrive at the beginning of day and are invested pro-rata at the
 *     prior close (all quantities scale by the same factor).
 *  2. Dividends are reinvested in the paying security at that day's close.
 *  3. Rebalance trades execute at that day's close, value-neutral.
 *  4. The end-of-day snapshot is written to positions_daily.
 */
class DataGenerator(private val params: GenParams) {

    private val rng = Random(params.seed)
    private val n = params.securities
    private val days = params.tradingDays

    val dates: List<LocalDate> = tradingCalendar(params.startDate, days)

    private val sectorOf = IntArray(n) { it % GICS_SECTORS.size }
    private val sectorDailyDrift = DoubleArray(GICS_SECTORS.size) { rng.nextDouble(0.02, 0.14) / 252.0 }

    /** prices[t][i], t = 0..days */
    private val prices: Array<DoubleArray> = simulatePrices()

    /** dps[t][i]: per-share dividend paid on day t (t = 1..days), mostly zero. */
    private val dps: Array<DoubleArray> = simulateDividends()

    /** total return of security i over day t: (p_t + d_t) / p_(t-1) - 1 */
    private val rets: Array<DoubleArray> = Array(days + 1) { t ->
        if (t == 0) DoubleArray(n)
        else DoubleArray(n) { i -> (prices[t][i] + dps[t][i]) / prices[t - 1][i] - 1.0 }
    }

    /** benchWeights[t][i]: beginning-of-day benchmark weight for day t (t = 1..days). */
    private val benchWeights: Array<DoubleArray> = simulateBenchmark()

    private fun gaussian(): Double {
        // Box-Muller; kotlin.random has no nextGaussian.
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun simulatePrices(): Array<DoubleArray> {
        val p = Array(days + 1) { DoubleArray(n) }
        for (i in 0 until n) p[0][i] = exp(ln(60.0) + 0.9 * gaussian()).coerceIn(2.0, 2000.0)
        val idioVol = DoubleArray(n) { rng.nextDouble(0.008, 0.020) }
        for (t in 1..days) {
            val marketShock = 0.007 * gaussian()
            val sectorShock = DoubleArray(GICS_SECTORS.size) { 0.008 * gaussian() }
            for (i in 0 until n) {
                val logRet = sectorDailyDrift[sectorOf[i]] + marketShock +
                    sectorShock[sectorOf[i]] + idioVol[i] * gaussian()
                p[t][i] = p[t - 1][i] * exp(logRet)
            }
        }
        return p
    }

    private fun simulateDividends(): Array<DoubleArray> {
        val d = Array(days + 1) { DoubleArray(n) }
        val isPayer = BooleanArray(n) { rng.nextDouble() < 0.6 }
        val payOffset = IntArray(n) { rng.nextInt(63) }
        for (t in 1..days) {
            for (i in 0 until n) {
                if (isPayer[i] && t % 63 == payOffset[i]) {
                    d[t][i] = 0.006 * prices[t - 1][i] // ~0.6% quarterly yield
                }
            }
        }
        return d
    }

    private fun simulateBenchmark(): Array<DoubleArray> {
        val w = Array(days + 1) { DoubleArray(n) }
        var sum = 0.0
        for (i in 0 until n) {
            w[1][i] = exp(1.0 * gaussian()) // market-cap-ish dispersion
            sum += w[1][i]
        }
        for (i in 0 until n) w[1][i] /= sum
        // Buy-and-hold index: weights drift with total returns, renormalized daily.
        for (t in 2..days) {
            var s = 0.0
            for (i in 0 until n) {
                w[t][i] = w[t - 1][i] * (1.0 + rets[t - 1][i])
                s += w[t][i]
            }
            for (i in 0 until n) w[t][i] /= s
        }
        return w
    }

    fun generateInto(conn: Connection) {
        createTables(conn)
        val duck = conn.unwrap(DuckDBConnection::class.java)
        appendSecurities(duck)
        appendPortfoliosAndPositions(duck)
        appendBenchmark(duck)
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE OR REPLACE TABLE securities (
                    security_id INTEGER NOT NULL, ticker VARCHAR NOT NULL,
                    sector VARCHAR NOT NULL, asset_class VARCHAR NOT NULL);
                CREATE OR REPLACE TABLE portfolios (
                    portfolio_id INTEGER NOT NULL, name VARCHAR NOT NULL,
                    inception_date DATE NOT NULL, base_currency VARCHAR NOT NULL);
                CREATE OR REPLACE TABLE positions_daily (
                    date DATE NOT NULL, portfolio_id INTEGER NOT NULL, security_id INTEGER NOT NULL,
                    quantity DOUBLE NOT NULL, price DOUBLE NOT NULL, market_value DOUBLE NOT NULL);
                -- amount = signed cash impact on the portfolio's implicit cash account:
                --   CASHFLOW: the external flow (+contribution / -withdrawal); quantity 0
                --   DIVIDEND: cash received (+); quantity = shares added by reinvestment
                --   SELL:     proceeds (+);     quantity = -shares sold
                --   BUY:      cash spent (-);   quantity = +shares bought
                -- Only CASHFLOW rows are external flows; the TWR engine reads only those.
                CREATE OR REPLACE TABLE transactions (
                    date DATE NOT NULL, portfolio_id INTEGER NOT NULL, security_id INTEGER,
                    type VARCHAR NOT NULL, quantity DOUBLE NOT NULL, amount DOUBLE NOT NULL);
                CREATE OR REPLACE TABLE benchmark_weights (
                    date DATE NOT NULL, security_id INTEGER NOT NULL, weight DOUBLE NOT NULL);
                CREATE OR REPLACE TABLE benchmark_returns (
                    date DATE NOT NULL, security_id INTEGER NOT NULL, ret DOUBLE NOT NULL);
                """,
            )
        }
    }

    private fun appendSecurities(duck: DuckDBConnection) {
        duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "securities").use { app ->
            for (i in 0 until n) {
                app.beginRow()
                    .append(i + 1)
                    .append("SEC%03d".format(i + 1))
                    .append(GICS_SECTORS[sectorOf[i]])
                    .append("EQUITY")
                    .endRow()
            }
        }
    }

    private fun appendBenchmark(duck: DuckDBConnection) {
        duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "benchmark_weights").use { app ->
            for (t in 1..days) for (i in 0 until n) {
                app.beginRow().append(dates[t]).append(i + 1).append(benchWeights[t][i]).endRow()
            }
        }
        duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "benchmark_returns").use { app ->
            for (t in 1..days) for (i in 0 until n) {
                app.beginRow().append(dates[t]).append(i + 1).append(rets[t][i]).endRow()
            }
        }
    }

    private fun appendPortfoliosAndPositions(duck: DuckDBConnection) {
        duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "portfolios").use { app ->
            for (p in 1..params.portfolios) {
                app.beginRow().append(p).append("Portfolio %03d".format(p))
                    .append(dates[0]).append("USD").endRow()
            }
        }
        val posApp = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "positions_daily")
        val txApp = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "transactions")
        posApp.use { pos ->
            txApp.use { tx ->
                for (p in 1..params.portfolios) simulatePortfolio(p, pos, tx)
            }
        }
    }

    private fun simulatePortfolio(
        portfolioId: Int,
        pos: org.duckdb.DuckDBAppender,
        tx: org.duckdb.DuckDBAppender,
    ) {
        // Tilted security selection: each portfolio overweights 3 favorite sectors,
        // which produces persistent nonzero allocation effects.
        val favorites = (0 until GICS_SECTORS.size).shuffled(rng).take(3).toSet()
        val count = (params.meanHoldings * rng.nextDouble(0.85, 1.15)).roundToInt()
            .coerceIn(4, n)
        val drawKey = DoubleArray(n) { i ->
            rng.nextDouble() / (if (sectorOf[i] in favorites) 2.5 else 1.0)
        }
        val held = (0 until n).sortedBy { drawKey[it] }.take(count).sorted()

        val qty = DoubleArray(held.size) { k ->
            val targetValue = exp(ln(100_000.0) + 0.8 * gaussian())
            targetValue / prices[0][held[k]]
        }

        fun snapshot(t: Int) {
            for (k in held.indices) {
                val i = held[k]
                pos.beginRow().append(dates[t]).append(portfolioId).append(i + 1)
                    .append(qty[k]).append(prices[t][i]).append(qty[k] * prices[t][i]).endRow()
            }
        }
        snapshot(0) // inception snapshot

        for (t in 1..days) {
            // 1. External flow at beginning of day, invested pro-rata at the prior close.
            if (rng.nextDouble() < 0.05) {
                var bodBasis = 0.0
                for (k in held.indices) bodBasis += qty[k] * prices[t - 1][held[k]]
                val cf = bodBasis * rng.nextDouble(-0.02, 0.05)
                val g = 1.0 + cf / bodBasis
                for (k in held.indices) qty[k] *= g
                tx.beginRow().append(dates[t]).append(portfolioId).appendNull()
                    .append("CASHFLOW").append(0.0).append(cf).endRow()
            }
            // 2. Dividends reinvested at this day's close.
            for (k in held.indices) {
                val i = held[k]
                val d = dps[t][i]
                if (d > 0.0) {
                    val cash = qty[k] * d
                    val newShares = cash / prices[t][i]
                    qty[k] += newShares
                    tx.beginRow().append(dates[t]).append(portfolioId).append(i + 1)
                        .append("DIVIDEND").append(newShares).append(cash).endRow()
                }
            }
            // 3. Occasional value-neutral rebalance at this day's close.
            if (rng.nextDouble() < 0.10 && held.size >= 2) {
                val a = rng.nextInt(held.size)
                var b = rng.nextInt(held.size)
                while (b == a) b = rng.nextInt(held.size)
                val value = 0.25 * qty[a] * prices[t][held[a]]
                val soldShares = value / prices[t][held[a]]
                val boughtShares = value / prices[t][held[b]]
                qty[a] -= soldShares
                qty[b] += boughtShares
                tx.beginRow().append(dates[t]).append(portfolioId).append(held[a] + 1)
                    .append("SELL").append(-soldShares).append(value).endRow()
                tx.beginRow().append(dates[t]).append(portfolioId).append(held[b] + 1)
                    .append("BUY").append(boughtShares).append(-value).endRow()
            }
            // 4. End-of-day snapshot.
            snapshot(t)
        }
    }
}
