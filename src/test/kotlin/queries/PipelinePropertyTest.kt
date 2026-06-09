package queries

import datagen.DataGenerator
import datagen.GenParams
import datagen.tradingCalendar
import engine.subPeriodReturn
import etl.loadFromParquet
import etl.openDatabase
import etl.openInMemory
import etl.writeParquet
import java.nio.file.Files
import java.sql.Connection
import java.time.LocalDate
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property-style tests on generated data at small scale, run through the full
 * generate -> Parquet -> DuckDB ETL. The invariants must hold for *any* seed; a few
 * are exercised here.
 */
class PipelinePropertyTest {

    @OptIn(ExperimentalPathApi::class)
    private fun withPipeline(seed: Long, block: (Connection, GenParams, List<LocalDate>) -> Unit) {
        val params = GenParams(seed = seed, securities = 80, portfolios = 8, tradingDays = 30, scale = 0.06)
        val calendar = tradingCalendar(params.startDate, params.tradingDays)
        val parquetDir = Files.createTempDirectory("brinson-pq")
        val dbDir = Files.createTempDirectory("brinson-db")
        try {
            openInMemory().use { mem ->
                DataGenerator(params).generateInto(mem)
                writeParquet(mem, parquetDir)
            }
            openDatabase(dbDir.resolve("test.duckdb")).use { conn ->
                loadFromParquet(conn, parquetDir)
                block(conn, params, calendar)
            }
        } finally {
            parquetDir.deleteRecursively()
            dbDir.deleteRecursively()
        }
    }

    @Test
    fun `per-day attribution effects sum to active return for every portfolio and day`() {
        for (seed in listOf(1L, 42L, 99L)) {
            withPipeline(seed) { conn, params, calendar ->
                val byPfDay = optimizedAttributionDaily(conn, calendar[1], calendar.last())
                    .groupBy { it.portfolioId to it.date }
                assertEquals(params.portfolios * params.tradingDays, byPfDay.size)
                for ((key, rows) in byPfDay) {
                    val rp = rows.sumOf { it.wp * it.rp }
                    val rb = rows.first().rbTotal
                    val sumEffects = rows.sumOf { it.allocation + it.selection + it.interaction }
                    assertEquals(rp - rb, sumEffects, 1e-10, "invariant broken at $key (seed $seed)")
                    assertEquals(1.0, rows.sumOf { it.wp }, 1e-10)
                    assertEquals(1.0, rows.sumOf { it.wb }, 1e-10)
                }
            }
        }
    }

    @Test
    fun `weight-based attribution return equals MV-based TWR sub-period return`() {
        // Holds because the generator invests external flows pro-rata at the prior close
        // (METHODOLOGY.md Conventions §4).
        withPipeline(42L) { conn, params, calendar ->
            val attributionByPfDay = optimizedAttributionDaily(conn, calendar[1], calendar.last())
                .groupBy { it.portfolioId to it.date }
            for (pf in 1..params.portfolios) {
                for (v in dailyValuations(conn, pf, calendar[1], calendar.last())) {
                    val twrReturn = subPeriodReturn(v.mvBegin, v.mvEnd, v.externalFlow)
                    val weightReturn = attributionByPfDay.getValue(pf to v.date).sumOf { it.wp * it.rp }
                    assertEquals(twrReturn, weightReturn, 1e-9, "mismatch pf=$pf ${v.date}")
                }
            }
        }
    }

    @Test
    fun `naive and optimized attribution produce identical results`() {
        for (seed in listOf(1L, 42L)) {
            withPipeline(seed) { conn, _, calendar ->
                val naive = naiveAttribution(conn, calendar[1], calendar.last())
                val optimized = optimizedAttribution(conn, calendar[1], calendar.last())
                assertEquals(optimized.size, naive.size)
                for ((n, o) in naive.zip(optimized)) {
                    assertEquals(o.portfolioId, n.portfolioId)
                    assertEquals(o.sector, n.sector)
                    assertEquals(o.avgPortfolioWeight, n.avgPortfolioWeight, 1e-9)
                    assertEquals(o.avgBenchmarkWeight, n.avgBenchmarkWeight, 1e-9)
                    assertEquals(o.allocation, n.allocation, 1e-9, "allocation differs in ${o.sector}")
                    assertEquals(o.selection, n.selection, 1e-9, "selection differs in ${o.sector}")
                    assertEquals(o.interaction, n.interaction, 1e-9, "interaction differs in ${o.sector}")
                }
            }
        }
    }

    @Test
    fun `Carino-linked report effects reconcile to the geometric active return`() {
        withPipeline(42L) { conn, params, calendar ->
            for (pf in 1..params.portfolios) {
                val daily = optimizedAttributionDaily(conn, calendar[1], calendar.last(), portfolioFilter = pf)
                    .groupBy { it.date }.toSortedMap().values
                val periods = daily.map { rows ->
                    engine.PeriodReturns(rows.sumOf { it.wp * it.rp }, rows.first().rbTotal)
                }
                val linked = report.carinoLinkedAttribution(conn, calendar[1], calendar.last(), pf)
                val totalLinked = linked.sumOf { it.total }
                assertEquals(engine.cumulativeActiveReturn(periods), totalLinked, 1e-10, "pf=$pf")
            }
        }
    }

    @Test
    fun `generated benchmark weights sum to one every day`() {
        withPipeline(42L) { conn, _, _ ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT date, sum(weight) FROM benchmark_weights GROUP BY date").use { rs ->
                    while (rs.next()) assertEquals(1.0, rs.getDouble(2), 1e-9)
                }
            }
        }
    }
}
