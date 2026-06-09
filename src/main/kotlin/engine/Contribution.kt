package engine

/** A single holding's weight and return over one period. */
data class HoldingPeriod(val id: String, val weight: Double, val periodReturn: Double)

/** c_i = w_i * r_i; sum(c_i) = r_portfolio for a single period. */
fun contribution(holding: HoldingPeriod): Double = holding.weight * holding.periodReturn

fun contributions(holdings: List<HoldingPeriod>): Map<String, Double> =
    holdings.associate { it.id to contribution(it) }
