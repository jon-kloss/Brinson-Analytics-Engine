package report

import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import queries.AttributionRow

class HtmlReportTest {

    private val rows = listOf(
        AttributionRow(1, "Tech", 0.5, 0.6, -0.0002, -0.0150, 0.0025),
        AttributionRow(1, "Energy", 0.5, 0.4, -0.0003, 0.0, 0.0),
    )

    private fun render() = buildHtmlReport(
        "Portfolio 001", LocalDate.parse("2024-01-03"), LocalDate.parse("2024-01-05"), rows,
    )

    @Test
    fun `waterfall bars stack sector totals into the active return`() {
        val html = render()
        // Energy total = -3 bps (larger), Tech total = -127 bps; sorted descending,
        // Energy [0, -3], Tech [-3, -130], summary bar [0, -130].
        assertContains(html, "[0.0000, -3.0000]")
        assertContains(html, "[-3.0000, -130.0000]")
        assertContains(html, "[0, -130.0000]")
    }

    @Test
    fun `output is locale-independent`() {
        val saved = Locale.getDefault()
        try {
            // A decimal-comma locale must not corrupt the JS arrays the browser parses.
            Locale.setDefault(Locale.GERMANY)
            val html = render()
            assertContains(html, "[0.0000, -3.0000]")
            assertFalse(Regex("""\[\d+,\d{4}""").containsMatchIn(html), "decimal commas leaked into JS data")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `text content is escaped`() {
        val html = buildHtmlReport(
            "<script>alert(1)</script>", LocalDate.parse("2024-01-03"), LocalDate.parse("2024-01-05"),
            listOf(AttributionRow(1, "R&D \"Sector\"", 1.0, 1.0, 0.0, 0.0, 0.0)),
        )
        assertFalse(html.contains("<script>alert"), "unescaped HTML in title")
        assertContains(html, "&lt;script&gt;alert")
        assertContains(html, "R&amp;D")
        assertTrue(html.contains("\\\"Sector\\\"") || html.contains("&quot;Sector&quot;"))
    }
}
