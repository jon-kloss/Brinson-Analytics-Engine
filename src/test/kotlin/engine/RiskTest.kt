package engine

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskTest {

    @Test
    fun `tracking error matches the hand calculation`() {
        // METHODOLOGY.md "Risk metrics": stdev([1%, -1%, 1%, -1%]) = 1.1547%,
        // annualized by sqrt(252).
        assertEquals(0.183303027798, annualizedTrackingError(listOf(0.01, -0.01, 0.01, -0.01)), 1e-12)
    }

    @Test
    fun `information ratio matches the hand calculation`() {
        val active = listOf(0.002, 0.001, 0.003, 0.000)
        assertEquals(0.020493901532, annualizedTrackingError(active), 1e-12)
        assertEquals(18.444511378727, informationRatio(active)!!, 1e-10)
        // IR is dimensionally mean/sd * sqrt(P): verify the identity directly.
        val mean = active.average()
        val sd = sqrt(active.sumOf { (it - mean) * (it - mean) } / (active.size - 1))
        assertEquals(mean / sd * sqrt(252.0), informationRatio(active)!!, 1e-12)
    }

    @Test
    fun `information ratio is null for a perfect tracker`() {
        assertNull(informationRatio(listOf(0.0, 0.0, 0.0)))
    }

    @Test
    fun `max drawdown matches the hand calculation`() {
        // Curve 1.00 -> 1.10 -> 0.99 -> 1.05: trough/peak = 0.99/1.10 - 1 = -10%.
        val returns = listOf(0.10, -0.10, 0.0606060606060606)
        assertEquals(-0.10, maxDrawdown(returns), 1e-12)
        assertEquals(0.0, maxDrawdown(listOf(0.01, 0.02, 0.0)), 1e-12)
    }

    @Test
    fun `max drawdown is never positive and bounded by minus one`() {
        val rng = Random(23)
        repeat(100) {
            val returns = List(rng.nextInt(2, 300)) { rng.nextDouble(-0.08, 0.08) }
            val dd = maxDrawdown(returns)
            assertTrue(dd <= 0.0 && dd > -1.0, "dd=$dd")
        }
    }
}
