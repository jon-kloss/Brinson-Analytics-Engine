import java.sql.DriverManager

fun main(args: Array<String>) {
    // Placeholder entrypoint (Milestone 1): prove the DuckDB JDBC driver works.
    DriverManager.getConnection("jdbc:duckdb:").use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT version()").use { rs ->
                rs.next()
                println("DuckDB ${rs.getString(1)} ready. Args: ${args.joinToString()}")
            }
        }
    }
}
