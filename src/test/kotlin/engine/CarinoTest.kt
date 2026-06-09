package engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CarinoTest {

    private val tolerance = 1e-12

    @Test
    fun `golden two-period example matches the hand calculation`() {
        // METHODOLOGY.md "Multi-period linking (Cariño)" worked example:
        // (rp, rb) = (10%, 5%) then (-2%, 1%).
        val periods = listOf(PeriodReturns(0.10, 0.05), PeriodReturns(-0.02, 0.01))

        assertEquals(0.0780, linkGeometrically(periods.map { it.portfolio }), tolerance)
        assertEquals(0.0605, linkGeometrically(periods.map { it.benchmark }), tolerance)
        assertEquals(0.0175, cumulativeActiveReturn(periods), tolerance)

        assertEquals(0.930400312698, carinoCoefficient(0.10, 0.05), 1e-12)
        assertEquals(1.005101272356, carinoCoefficient(-0.02, 0.01), 1e-12)
        assertEquals(0.935255855097, carinoCoefficient(0.0780, 0.0605), 1e-12)

        val factors = carinoFactors(periods)
        assertEquals(0.994808327183, factors[0], 1e-12)
        assertEquals(1.074680545305, factors[1], 1e-12)

        val linkedActive = periods.zip(factors).sumOf { (p, f) -> f * (p.portfolio - p.benchmark) }
        assertEquals(0.0175, linkedActive, tolerance)

        // Sector-effect scaling from the same doc section: per-day sector totals
        // X = (+0.03, -0.01), Y = (+0.02, -0.02) decompose the per-day actives.
        val linkedX = factors[0] * 0.03 + factors[1] * -0.01
        val linkedY = factors[0] * 0.02 + factors[1] * -0.02
        assertEquals(+0.019097444362, linkedX, 1e-12)
        assertEquals(-0.001597444362, linkedY, 1e-12)
        assertEquals(0.0175, linkedX + linkedY, tolerance)
    }

    @Test
    fun `scaled period actives always sum to the geometric active return`() {
        val rng = Random(17)
        repeat(200) {
            val periods = List(rng.nextInt(2, 300)) {
                PeriodReturns(rng.nextDouble(-0.08, 0.08), rng.nextDouble(-0.08, 0.08))
            }
            val factors = carinoFactors(periods)
            val linked = periods.zip(factors).sumOf { (p, f) -> f * (p.portfolio - p.benchmark) }
            assertEquals(cumulativeActiveReturn(periods), linked, 1e-12)
        }
    }

    @Test
    fun `coefficient handles equal returns via the analytic limit`() {
        assertEquals(1.0 / 1.05, carinoCoefficient(0.05, 0.05), tolerance)
        // Zero active return on every day: factors are finite and linking yields zero.
        val flat = List(10) { PeriodReturns(0.01, 0.01) }
        val linked = flat.zip(carinoFactors(flat)).sumOf { (p, f) -> f * (p.portfolio - p.benchmark) }
        assertEquals(0.0, linked, tolerance)
    }

    @Test
    fun `coefficient is stable for nearly-equal returns`() {
        // A few-ulp difference must not trigger catastrophic cancellation in the log
        // subtraction (a day with rp ~ rb can still carry large offsetting sector
        // effects, all multiplied by this coefficient).
        assertEquals(1.0 / 1.05, carinoCoefficient(0.05, 0.05 + 1e-15), 1e-9)
        assertEquals(1.0 / 1.05, carinoCoefficient(0.05 + 1e-15, 0.05), 1e-9)
        // Just outside the tolerance window the exact formula must agree with the
        // limit form to the same order, so no discontinuity at the branch boundary.
        assertEquals(1.0 / 1.05, carinoCoefficient(0.05, 0.05 + 1e-8), 1e-7)
    }
}
