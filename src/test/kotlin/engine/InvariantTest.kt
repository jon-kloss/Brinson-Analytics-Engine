package engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvariantTest {

    @Test
    fun `attribution effects always sum to active return`() {
        val rng = Random(7)
        repeat(200) {
            val sectorCount = rng.nextInt(2, 12)
            val wpRaw = DoubleArray(sectorCount) { rng.nextDouble(0.01, 1.0) }
            val wbRaw = DoubleArray(sectorCount) { rng.nextDouble(0.01, 1.0) }
            val sectors = (0 until sectorCount).map { i ->
                SectorPeriod(
                    sector = "S$i",
                    portfolioWeight = wpRaw[i] / wpRaw.sum(),
                    benchmarkWeight = wbRaw[i] / wbRaw.sum(),
                    portfolioReturn = rng.nextDouble(-0.1, 0.1),
                    benchmarkReturn = rng.nextDouble(-0.1, 0.1),
                )
            }
            val sum = brinsonFachler(sectors).sumOf { it.total }
            val active = portfolioReturn(sectors) - benchmarkReturn(sectors)
            assertEquals(active, sum, 1e-10)
        }
    }

    @Test
    fun `contributions always sum to portfolio return`() {
        val rng = Random(11)
        repeat(200) {
            val n = rng.nextInt(2, 50)
            val raw = DoubleArray(n) { rng.nextDouble(0.01, 1.0) }
            val holdings = (0 until n).map { i ->
                HoldingPeriod("H$i", raw[i] / raw.sum(), rng.nextDouble(-0.1, 0.1))
            }
            val portfolioReturn = holdings.sumOf { it.weight * it.periodReturn }
            assertEquals(portfolioReturn, contributions(holdings).values.sum(), 1e-10)
        }
    }

    @Test
    fun `TWR with zero cash flows equals simple cumulative return`() {
        val rng = Random(13)
        repeat(100) {
            val days = rng.nextInt(2, 300)
            val mv = DoubleArray(days + 1)
            mv[0] = rng.nextDouble(1e5, 1e7)
            for (t in 1..days) mv[t] = mv[t - 1] * (1.0 + rng.nextDouble(-0.05, 0.05))
            val twr = linkGeometrically((1..days).map { subPeriodReturn(mv[it - 1], mv[it], 0.0) })
            assertEquals(mv[days] / mv[0] - 1.0, twr, 1e-10)
        }
    }

    @Test
    fun `annualization applies only at one year or beyond`() {
        assertTrue(annualizedOrNull(0.10, 364) == null)
        val twoYear = annualizedOrNull(0.21, 731)
        assertTrue(twoYear != null && abs(twoYear - (Math.pow(1.21, 365.25 / 731.0) - 1.0)) < 1e-12)
    }
}
