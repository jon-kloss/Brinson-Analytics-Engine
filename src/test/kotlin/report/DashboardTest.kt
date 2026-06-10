package report

import datagen.DataGenerator
import datagen.GenParams
import etl.loadFromParquet
import etl.openDatabase
import etl.openInMemory
import etl.writeParquet
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardTest {

    @OptIn(ExperimentalPathApi::class)
    private fun smallScaleJson(): String {
        val params = GenParams(seed = 42L, securities = 60, portfolios = 4, tradingDays = 25, scale = 0.05)
        val tmp = Files.createTempDirectory("brinson-dash")
        try {
            openInMemory().use { mem ->
                DataGenerator(params).generateInto(mem)
                writeParquet(mem, tmp.resolve("pq"))
            }
            return openDatabase(tmp.resolve("d.duckdb")).use { conn ->
                loadFromParquet(conn, tmp.resolve("pq"))
                buildDashboardJson(conn)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `dashboard json is structurally sound and locale-stable`() {
        val saved = Locale.getDefault()
        val json = try {
            Locale.setDefault(Locale.GERMANY) // decimal commas must not leak into JSON
            smallScaleJson()
        } finally {
            Locale.setDefault(saved)
        }
        for (key in listOf("\"dates\"", "\"sectors\"", "\"sectorsShort\"", "\"rb\"", "\"portfolios\"", "\"risk\"", "\"attribution\"", "\"weights\"")) {
            assertContains(json, key)
        }
        assertFalse(json.contains("NaN") || json.contains("Infinity"), "non-finite values leaked into JSON")
        // A locale leak renders %.8f as e.g. "0,00123456": a comma followed by 8 digits.
        // (Plain \d,\d would false-positive on ordinary array separators like "1,0.5".)
        assertFalse(Regex("""\d,\d{8}""").containsMatchIn(json), "decimal commas leaked into JSON")
        assertEquals(json.count { it == '{' }, json.count { it == '}' }, "unbalanced braces")
        assertEquals(json.count { it == '[' }, json.count { it == ']' }, "unbalanced brackets")
        assertEquals(4, Regex("\"id\":").findAll(json).count())
    }

    @Test
    fun `writeDashboard emits both site files`() {
        val params = GenParams(seed = 1L, securities = 40, portfolios = 2, tradingDays = 15, scale = 0.05)
        val tmp = Files.createTempDirectory("brinson-dash-site")
        try {
            openInMemory().use { mem ->
                DataGenerator(params).generateInto(mem)
                writeParquet(mem, tmp.resolve("pq"))
            }
            openDatabase(tmp.resolve("d.duckdb")).use { conn ->
                loadFromParquet(conn, tmp.resolve("pq"))
                writeDashboard(conn, tmp.resolve("site")) {}
            }
            assertTrue(tmp.resolve("site/index.html").toFile().length() > 500)
            assertTrue(tmp.resolve("site/styles.css").toFile().length() > 1000)
            assertTrue(tmp.resolve("site/dashboard.js").toFile().length() > 1000)
            assertTrue(tmp.resolve("site/data.json").toFile().length() > 1000)
            assertContains(tmp.resolve("site/index.html").toFile().readText(), "data.json")
            assertContains(tmp.resolve("site/dashboard.js").toFile().readText(), "sectorsShort")
        } finally {
            @OptIn(ExperimentalPathApi::class)
            tmp.deleteRecursively()
        }
    }
}
