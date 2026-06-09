package engine

import kotlin.math.sqrt

/**
 * Annualized tracking error: sample standard deviation of daily active returns
 * (portfolio minus benchmark), scaled by sqrt(252).
 * See METHODOLOGY.md "Risk metrics".
 */
fun annualizedTrackingError(dailyActive: List<Double>, periodsPerYear: Int = 252): Double {
    require(dailyActive.size >= 2) { "need at least 2 observations, got ${dailyActive.size}" }
    val mean = dailyActive.average()
    val variance = dailyActive.sumOf { (it - mean) * (it - mean) } / (dailyActive.size - 1)
    return sqrt(variance) * sqrt(periodsPerYear.toDouble())
}

/**
 * Information ratio: annualized arithmetic mean active return over annualized
 * tracking error (mean * P / (sd * sqrt(P)) = mean / sd * sqrt(P)).
 * Null when tracking error is zero (a portfolio that perfectly tracks).
 */
fun informationRatio(dailyActive: List<Double>, periodsPerYear: Int = 252): Double? {
    val te = annualizedTrackingError(dailyActive, periodsPerYear)
    if (te == 0.0) return null
    return dailyActive.average() * periodsPerYear / te
}

/**
 * Maximum drawdown of the compounded return path: the most negative
 * peak-to-trough ratio, expressed as a negative fraction (0.0 = no drawdown).
 */
fun maxDrawdown(dailyReturns: List<Double>): Double {
    var growth = 1.0
    var peak = 1.0
    var maxDd = 0.0
    for (r in dailyReturns) {
        growth *= 1.0 + r
        if (growth > peak) peak = growth
        val dd = growth / peak - 1.0
        if (dd < maxDd) maxDd = dd
    }
    return maxDd
}
