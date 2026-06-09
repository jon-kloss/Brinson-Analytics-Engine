package engine

/** A single holding's weight and return over one period. */
data class HoldingPeriod(val id: String, val weight: Double, val periodReturn: Double)

/** c_i = w_i * r_i; sum(c_i) = r_portfolio for a single period. */
fun contribution(holding: HoldingPeriod): Double = holding.weight * holding.periodReturn

fun contributions(holdings: List<HoldingPeriod>): Map<String, Double> =
    // Aggregate duplicate ids rather than associate-overwriting them, which would silently
    // drop a contribution and break sum(c_i) = r_portfolio.
    holdings.groupingBy { it.id }.fold(0.0) { acc, h -> acc + contribution(h) }
