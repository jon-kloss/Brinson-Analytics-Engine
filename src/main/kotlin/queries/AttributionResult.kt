package queries

import java.time.LocalDate

/** Brinson-Fachler effects summed over the period, with average weights for display. */
data class AttributionRow(
    val portfolioId: Int,
    val sector: String,
    val avgPortfolioWeight: Double,
    val avgBenchmarkWeight: Double,
    val allocation: Double,
    val selection: Double,
    val interaction: Double,
) {
    val total: Double get() = allocation + selection + interaction
}

/** Single-day, single-sector attribution detail (used by the property tests and report). */
data class DailySectorRow(
    val portfolioId: Int,
    val date: LocalDate,
    val sector: String,
    val wp: Double,
    val wb: Double,
    val rp: Double,
    val rbI: Double,
    val rbTotal: Double,
    val allocation: Double,
    val selection: Double,
    val interaction: Double,
)
