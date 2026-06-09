package engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Engine-level golden tests: the exact numbers hand-calculated in
 * docs/METHODOLOGY.md "Worked golden example".
 */
class GoldenExampleTest {

    private val tolerance = 1e-12

    @Test
    fun `TWR sub-period returns match the hand calculation`() {
        assertEquals(0.01, subPeriodReturn(4000.0, 4040.0, 0.0), tolerance)
        assertEquals(90.0 / 5060.0, subPeriodReturn(4040.0, 5150.0, 1020.0), tolerance)
        assertEquals(35.0 / 5150.0, subPeriodReturn(5150.0, 5185.0, 0.0), tolerance)
    }

    @Test
    fun `linked TWR equals 3537 over 101200`() {
        val twr = linkGeometrically(listOf(0.01, 90.0 / 5060.0, 35.0 / 5150.0))
        assertEquals(3537.0 / 101200.0, twr, tolerance)
        // Pins the decimal literal printed in METHODOLOGY.md, not just the fraction.
        assertEquals(0.0349505928853754, twr, tolerance)
    }

    @Test
    fun `no annualization below one year`() {
        assertNull(annualizedOrNull(3537.0 / 101200.0, 3))
    }

    @Test
    fun `Brinson-Fachler effects match the hand calculation`() {
        val sectors = listOf(
            SectorPeriod("Tech", portfolioWeight = 0.50, benchmarkWeight = 0.60, portfolioReturn = 0.00, benchmarkReturn = 0.025),
            SectorPeriod("Energy", portfolioWeight = 0.50, benchmarkWeight = 0.40, portfolioReturn = 0.02, benchmarkReturn = 0.02),
        )
        assertEquals(0.023, benchmarkReturn(sectors), tolerance)
        assertEquals(0.010, portfolioReturn(sectors), tolerance)

        val effects = brinsonFachler(sectors).associateBy { it.sector }
        val tech = effects.getValue("Tech")
        assertEquals(-0.0002, tech.allocation, tolerance)
        assertEquals(-0.0150, tech.selection, tolerance)
        assertEquals(+0.0025, tech.interaction, tolerance)
        val energy = effects.getValue("Energy")
        assertEquals(-0.0003, energy.allocation, tolerance)
        assertEquals(0.0, energy.selection, tolerance)
        assertEquals(0.0, energy.interaction, tolerance)

        val sum = brinsonFachler(sectors).sumOf { it.total }
        assertEquals(-0.013, sum, tolerance)
        assertEquals(portfolioReturn(sectors) - benchmarkReturn(sectors), sum, tolerance)
    }

    @Test
    fun `contributions match the hand calculation`() {
        val holdings = listOf(
            HoldingPeriod("AAA", 0.25, 0.05),
            HoldingPeriod("BBB", 0.25, -0.05),
            HoldingPeriod("CCC", 0.25, 0.02),
            HoldingPeriod("DDD", 0.25, 0.02),
        )
        val c = contributions(holdings)
        assertEquals(+0.0125, c.getValue("AAA"), tolerance)
        assertEquals(-0.0125, c.getValue("BBB"), tolerance)
        assertEquals(+0.0050, c.getValue("CCC"), tolerance)
        assertEquals(+0.0050, c.getValue("DDD"), tolerance)
        assertEquals(0.01, c.values.sum(), tolerance)
    }
}
