package engine

import kotlin.math.pow

/**
 * Sub-period return with beginning-of-day cash flow convention (w = 1).
 * See METHODOLOGY.md "Time-Weighted Return".
 */
fun subPeriodReturn(mvBegin: Double, mvEnd: Double, externalFlow: Double = 0.0): Double =
    (mvEnd - mvBegin - externalFlow) / (mvBegin + externalFlow)

/** Geometric linking: TWR = prod(1 + r_t) - 1. */
fun linkGeometrically(periodReturns: List<Double>): Double {
    var growth = 1.0
    for (r in periodReturns) growth *= 1.0 + r
    return growth - 1.0
}

/** Annualized only for periods >= 1 calendar year; null otherwise. */
fun annualizedOrNull(cumulativeReturn: Double, calendarDays: Long): Double? =
    if (calendarDays >= 365) (1.0 + cumulativeReturn).pow(365.25 / calendarDays) - 1.0
    else null
