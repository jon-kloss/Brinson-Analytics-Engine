package queries

import datagen.DataGenerator
import datagen.GenParams
import engine.subPeriodReturn
import etl.loadFromParquet
import etl.openDatabase
import etl.openInMemory
import etl.writeParquet
import java.nio.file.Files
import java.sql.Connection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-style tests on generated data at small scale, run through the full
 * generate -> Parquet -> DuckDB ETL. The invariants must hold for *any* seed; a few
 * are exercised here.
 */
class PipelinePropertyTest {

    private fun withPipeline(seed: Long, block: (Connection, GenParams) -> Unit) {
        val params = GenParams(seed = seed, securities = 80, portfolios = 8, tradingDays = 30, scale = 0.06)
        val parquetDir = Files.createTempDirectory("brinson-pq")
        val dbPath = Files.createTempDirectory("brinson-db").resolve("test.duckdb")
        openInMemory().use { mem ->
            DataGenerator(params).generateInto(mem)
            writeParquet(mem, parquetDir)
        }
        openDatabase(dbPath).use { conn ->
            loadFromParquet(conn, parquetDir)
            block(conn, params)
        }
    }

    @Test
    fun `per-day attribution effects sum to active return for every portfolio and day`() {
        for (seed in listOf(1L, 42L, 99L)) {
            withPipeline(seed) { conn, params ->
                val gen = DataGenerator(params)
                val from = gen.dates[1]
                val to = gen.dates.last()
                val byPfDay = optimizedAttributionDaily(conn, from, to)
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
        withPipeline(42L) { conn, params ->
            val gen = DataGenerator(params)
            val from = gen.dates[1]
            val to = gen.dates.last()
            val attributionByPfDay = optimizedAttributionDaily(conn, from, to)
                .groupBy { it.portfolioId to it.date }
            for (pf in 1..params.portfolios) {
                for (v in dailyValuations(conn, pf, from, to)) {
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
            withPipeline(seed) { conn, params ->
                val gen = DataGenerator(params)
                val naive = naiveAttribution(conn, gen.dates[1], gen.dates.last())
                val optimized = optimizedAttribution(conn, gen.dates[1], gen.dates.last())
                assertEquals(optimized.size, naive.size)
                for ((n, o) in naive.zip(optimized)) {
                    assertEquals(o.portfolioId, n.portfolioId)
                    assertEquals(o.sector, n.sector)
                    assertTrue(abs(o.avgPortfolioWeight - n.avgPortfolioWeight) < 1e-9)
                    assertTrue(abs(o.avgBenchmarkWeight - n.avgBenchmarkWeight) < 1e-9)
                    assertTrue(abs(o.allocation - n.allocation) < 1e-9, "allocation differs: $n vs $o")
                    assertTrue(abs(o.selection - n.selection) < 1e-9, "selection differs: $n vs $o")
                    assertTrue(abs(o.interaction - n.interaction) < 1e-9, "interaction differs: $n vs $o")
                }
            }
        }
    }

    @Test
    fun `generated benchmark weights sum to one every day`() {
        withPipeline(42L) { conn, _ ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT date, sum(weight) FROM benchmark_weights GROUP BY date").use { rs ->
                    while (rs.next()) assertEquals(1.0, rs.getDouble(2), 1e-9)
                }
            }
        }
    }
}
