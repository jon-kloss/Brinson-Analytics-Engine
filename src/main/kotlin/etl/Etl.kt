package etl

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

val TABLES = listOf(
    "securities", "portfolios", "positions_daily",
    "transactions", "benchmark_weights", "benchmark_returns",
)

fun openInMemory(): Connection = DriverManager.getConnection("jdbc:duckdb:")

fun openDatabase(dbPath: Path): Connection {
    Files.createDirectories(dbPath.toAbsolutePath().parent)
    return DriverManager.getConnection("jdbc:duckdb:$dbPath")
}

/** Stage 2 of the pipeline: dump every generated table to Parquet (DuckDB-native COPY). */
fun writeParquet(conn: Connection, dir: Path) {
    Files.createDirectories(dir)
    conn.createStatement().use { st ->
        for (table in TABLES) {
            // Sort the big fact table so Parquet row groups are clustered for the
            // window-function scan (portfolio, security, date).
            val source = if (table == "positions_daily") {
                "(SELECT * FROM positions_daily ORDER BY portfolio_id, security_id, date)"
            } else {
                table
            }
            st.execute(
                "COPY $source TO '${dir.resolve("$table.parquet")}' (FORMAT PARQUET, COMPRESSION ZSTD)",
            )
        }
    }
}

/** Stage 3: load the Parquet files into a persistent DuckDB database file. */
fun loadFromParquet(conn: Connection, parquetDir: Path) {
    conn.createStatement().use { st ->
        for (table in TABLES) {
            st.execute(
                "CREATE OR REPLACE TABLE $table AS SELECT * FROM read_parquet('${parquetDir.resolve("$table.parquet")}')",
            )
        }
    }
}

fun rowCount(conn: Connection, table: String): Long =
    conn.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM $table").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }
