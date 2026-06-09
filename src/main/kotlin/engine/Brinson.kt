package engine

/**
 * Single-period sector-level inputs to Brinson-Fachler attribution.
 * Weights are prior-close market-value weights (METHODOLOGY.md, Conventions §4).
 * Portfolio weights must sum to 1; benchmark weights are normalized internally.
 */
data class SectorPeriod(
    val sector: String,
    val portfolioWeight: Double, // wp_i
    val benchmarkWeight: Double, // wb_i
    val portfolioReturn: Double, // rp_i
    val benchmarkReturn: Double, // rb_i
)

data class SectorEffects(
    val sector: String,
    val allocation: Double,
    val selection: Double,
    val interaction: Double,
) {
    val total: Double get() = allocation + selection + interaction
}

/**
 * Brinson-Fachler effects for one period:
 *   A_i = (wp_i - wb_i) * (rb_i - rb)
 *   S_i = wb_i * (rp_i - rb_i)
 *   I_i = (wp_i - wb_i) * (rp_i - rb_i)
 * Invariant: sum(A_i + S_i + I_i) = rp_total - rb_total.
 */
fun brinsonFachler(sectors: List<SectorPeriod>): List<SectorEffects> {
    // Normalized, matching the SQL path (bench_sector_tot): weights that do not sum to
    // exactly 1 yield the correct benchmark mean instead of a silently skewed one.
    val rbTotal = sectors.sumOf { it.benchmarkWeight * it.benchmarkReturn } /
        sectors.sumOf { it.benchmarkWeight }
    return sectors.map { s ->
        SectorEffects(
            sector = s.sector,
            allocation = (s.portfolioWeight - s.benchmarkWeight) * (s.benchmarkReturn - rbTotal),
            selection = s.benchmarkWeight * (s.portfolioReturn - s.benchmarkReturn),
            interaction = (s.portfolioWeight - s.benchmarkWeight) * (s.portfolioReturn - s.benchmarkReturn),
        )
    }
}

fun portfolioReturn(sectors: List<SectorPeriod>): Double =
    sectors.sumOf { it.portfolioWeight * it.portfolioReturn }

fun benchmarkReturn(sectors: List<SectorPeriod>): Double =
    sectors.sumOf { it.benchmarkWeight * it.benchmarkReturn }
