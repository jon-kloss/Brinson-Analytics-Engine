package engine

import kotlin.math.abs
import kotlin.math.ln

/** One period's portfolio and benchmark return (the attribution return series). */
data class PeriodReturns(val portfolio: Double, val benchmark: Double)

/**
 * Cariño log-linking coefficient: k(a, b) = (ln(1+a) - ln(1+b)) / (a - b),
 * with the analytic limit 1/(1+a) as b -> a.
 * See METHODOLOGY.md "Multi-period linking (Cariño)".
 */
fun carinoCoefficient(a: Double, b: Double): Double {
    require(a > -1.0 && b > -1.0) { "returns must exceed -100%: a=$a, b=$b" }
    // Tolerance branch, not exact equality: for |a - b| of a few ulps the log
    // subtraction cancels catastrophically and can corrupt the coefficient by tens
    // of percent. The midpoint limit form is accurate to O((a-b)^2) in this window.
    return if (abs(a - b) < 1e-9) 2.0 / (2.0 + a + b)
    else (ln(1.0 + a) - ln(1.0 + b)) / (a - b)
}

/**
 * Per-period Cariño scaling factors k_t / k. Multiplying each period's attribution
 * effects by its factor makes the effects sum exactly to the geometric active return
 * R_p - R_b (cumulative portfolio return minus cumulative benchmark return), because
 *
 *   sum_t k_t * (rp_t - rb_t) = sum_t [ln(1+rp_t) - ln(1+rb_t)]
 *                             = ln(1+R_p) - ln(1+R_b) = k * (R_p - R_b).
 */
fun carinoFactors(periods: List<PeriodReturns>): List<Double> {
    var growthP = 1.0
    var growthB = 1.0
    for (p in periods) {
        growthP *= 1.0 + p.portfolio
        growthB *= 1.0 + p.benchmark
    }
    val k = carinoCoefficient(growthP - 1.0, growthB - 1.0)
    return periods.map { carinoCoefficient(it.portfolio, it.benchmark) / k }
}

fun cumulativeActiveReturn(periods: List<PeriodReturns>): Double =
    linkGeometrically(periods.map { it.portfolio }) - linkGeometrically(periods.map { it.benchmark })
