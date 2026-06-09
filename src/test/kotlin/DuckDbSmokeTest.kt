import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class DuckDbSmokeTest {

    @Test
    fun `embedded DuckDB answers a query`() {
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 21 * 2").use { rs ->
                    rs.next()
                    assertEquals(42, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `DuckDB aggregates over a generated series`() {
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT sum(i) FROM range(1, 101) t(i)").use { rs ->
                    rs.next()
                    assertEquals(5050L, rs.getLong(1))
                }
            }
        }
    }
}
