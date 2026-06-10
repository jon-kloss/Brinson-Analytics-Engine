package etl

import datagen.GICS_SECTORS
import java.nio.file.Path
import java.sql.Connection
import java.time.LocalDate

data class ImportResult(
    val portfolioId: Int,
    val name: String,
    val positionRows: Long,
    val tickers: Long,
    val newSecurities: Long,
    val from: LocalDate,
    val to: LocalDate,
    val flowDays: Long,
)

/**
 * Imports a user portfolio from a daily-holdings CSV into the star schema, after
 * which every downstream consumer (engine, report, API, dashboard) picks it up
 * with no further changes.
 *
 * CSV contract (header required, all six columns):
 *   date,ticker,sector,quantity,price,dividend
 * One row per ticker per trading day; quantity 0 for days a position isn't held;
 * `dividend` is per-share cash paid that day (blank/0 otherwise).
 *
 * Validations (the import is transactional — any failure leaves the DB untouched):
 *  - sectors must be GICS names; a ticker's sector must be consistent (and match
 *    the security master if the ticker already exists)
 *  - dates must lie on the trading calendar with no gaps in [min, max]
 *  - the matrix must be complete: every ticker on every date, no duplicates
 *  - prices strictly positive
 *
 * Derivations:
 *  - per-security total returns (price + dividend) / prior price - 1 are inserted
 *    into benchmark_returns for securities new to the universe, so attribution
 *    has a return source (the benchmark itself is unchanged — weights are not touched)
 *  - external cash flows are ESTIMATED from share changes valued at the prior
 *    close: CF_t = sum_i (q_t - q_(t-1)) * p_(t-1). Value-neutral rebalances net
 *    to ~zero; deposits/withdrawals surface. This matches the engine's
 *    beginning-of-day flow convention (METHODOLOGY.md), with the documented
 *    caveat that real flows are rarely pro-rata, so weight-based and MV-based
 *    daily returns may differ slightly on flow days.
 */
fun importPortfolio(conn: Connection, csvFile: Path, name: String): ImportResult {
    val path = csvFile.toAbsolutePath().toString().replace("'", "''")
    conn.autoCommit = false
    try {
        conn.createStatement().use { st ->
            fun count(sql: String): Long =
                st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }

            st.execute(
                """CREATE OR REPLACE TEMP TABLE staging AS
                   SELECT * FROM read_csv('$path', header = true, columns = {
                     'date': 'DATE', 'ticker': 'VARCHAR', 'sector': 'VARCHAR',
                     'quantity': 'DOUBLE', 'price': 'DOUBLE', 'dividend': 'DOUBLE'
                   })""",
            )

            val rows = count("SELECT count(*) FROM staging")
            require(rows > 0) { "CSV contains no data rows" }

            val sectorList = GICS_SECTORS.joinToString(",") { "'${it.replace("'", "''")}'" }
            val badSectors = st.executeQuery(
                "SELECT DISTINCT sector FROM staging WHERE sector IS NULL OR sector NOT IN ($sectorList) LIMIT 5",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) ?: "<null>") } }
            require(badSectors.isEmpty()) {
                "unknown sectors: $badSectors — valid GICS sectors are: $GICS_SECTORS"
            }
            require(count("SELECT count(*) FROM staging WHERE date IS NULL OR ticker IS NULL OR quantity IS NULL OR price IS NULL OR price <= 0") == 0L) {
                "every row needs a date, ticker, quantity, and a strictly positive price"
            }
            require(count("SELECT count(*) FROM (SELECT date, ticker FROM staging GROUP BY 1, 2 HAVING count(*) > 1)") == 0L) {
                "duplicate (date, ticker) rows"
            }
            require(count(
                """SELECT count(*) FROM (SELECT DISTINCT date FROM staging) s
                   LEFT JOIN (SELECT DISTINCT date FROM benchmark_returns) c USING (date)
                   WHERE c.date IS NULL""",
            ) == 0L) { "some dates are not on the trading calendar (weekends, or outside the data range)" }
            require(count(
                """SELECT count(*) FROM (
                     SELECT DISTINCT date FROM benchmark_returns
                     WHERE date BETWEEN (SELECT min(date) FROM staging) AND (SELECT max(date) FROM staging)
                   ) c LEFT JOIN (SELECT DISTINCT date FROM staging) s USING (date)
                   WHERE s.date IS NULL""",
            ) == 0L) { "calendar gaps: every trading day between the first and last date must be present" }
            val nDates = count("SELECT count(DISTINCT date) FROM staging")
            val nTickers = count("SELECT count(DISTINCT ticker) FROM staging")
            require(rows == nDates * nTickers) {
                "incomplete matrix: every ticker must appear on every date " +
                    "($nTickers tickers x $nDates dates = ${nDates * nTickers}, got $rows rows; " +
                    "use quantity 0 for days a position isn't held)"
            }
            require(count("SELECT count(*) FROM (SELECT ticker FROM staging GROUP BY ticker HAVING count(DISTINCT sector) > 1)") == 0L) {
                "a ticker appears with more than one sector"
            }
            require(count(
                """SELECT count(*) FROM (SELECT DISTINCT s.ticker FROM staging s
                   JOIN securities x ON x.ticker = s.ticker AND x.sector <> s.sector)""",
            ) == 0L) { "a ticker already exists in the security master with a different sector" }

            // Security mapping: reuse ids for known tickers, mint dense new ids for the rest.
            st.execute(
                """CREATE OR REPLACE TEMP TABLE secmap AS
                   WITH t AS (SELECT DISTINCT ticker, sector FROM staging),
                   m AS (SELECT t.ticker, t.sector, x.security_id AS existing_id
                         FROM t LEFT JOIN securities x ON x.ticker = t.ticker),
                   base AS (SELECT coalesce(max(security_id), 0) AS b FROM securities)
                   SELECT ticker, sector, existing_id,
                          coalesce(existing_id,
                            (SELECT b FROM base)
                              + row_number() OVER (PARTITION BY (existing_id IS NULL) ORDER BY ticker)) AS sid
                   FROM m""",
            )
            val newSecs = count("SELECT count(*) FROM secmap WHERE existing_id IS NULL")
            st.execute(
                """INSERT INTO securities
                   SELECT sid, ticker, sector, 'EQUITY' FROM secmap WHERE existing_id IS NULL""",
            )

            val pfId = (count("SELECT coalesce(max(portfolio_id), 0) FROM portfolios") + 1).toInt()
            val safeName = name.replace("'", "''")
            st.execute(
                """INSERT INTO portfolios
                   SELECT $pfId, '$safeName', (SELECT min(date) FROM staging), 'USD'""",
            )
            st.execute(
                """INSERT INTO positions_daily
                   SELECT s.date, $pfId, m.sid, s.quantity, s.price, s.quantity * s.price
                   FROM staging s JOIN secmap m USING (ticker)""",
            )
            // Total returns for securities new to the universe (attribution's return source).
            st.execute(
                """INSERT INTO benchmark_returns
                   SELECT date, sid, ret FROM (
                     SELECT s.date, m.sid,
                            (s.price + coalesce(s.dividend, 0))
                              / lag(s.price) OVER (PARTITION BY s.ticker ORDER BY s.date) - 1 AS ret
                     FROM staging s JOIN secmap m USING (ticker)
                     WHERE m.existing_id IS NULL
                   ) WHERE ret IS NOT NULL""",
            )
            // Estimated external flows: share changes valued at the prior close.
            st.execute(
                """INSERT INTO transactions
                   SELECT date, $pfId, NULL, 'CASHFLOW', 0.0, cf FROM (
                     SELECT date, sum(dq * p_prev) AS cf FROM (
                       SELECT s.date,
                              s.quantity - lag(s.quantity) OVER (PARTITION BY s.ticker ORDER BY s.date) AS dq,
                              lag(s.price) OVER (PARTITION BY s.ticker ORDER BY s.date) AS p_prev
                       FROM staging s
                     ) WHERE dq IS NOT NULL GROUP BY date
                   ) WHERE abs(cf) > 0.01""",
            )
            val flowDays = count("SELECT count(*) FROM transactions WHERE portfolio_id = $pfId AND type = 'CASHFLOW'")
            val (from, to) = st.executeQuery("SELECT min(date), max(date) FROM staging").use { rs ->
                rs.next(); rs.getDate(1).toLocalDate() to rs.getDate(2).toLocalDate()
            }
            conn.commit()
            return ImportResult(pfId, name, rows, nTickers, newSecs, from, to, flowDays)
        }
    } catch (e: Exception) {
        conn.rollback()
        throw e
    } finally {
        conn.autoCommit = true
    }
}
