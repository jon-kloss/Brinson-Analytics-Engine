package queries

import java.sql.Connection
import java.time.LocalDate

/**
 * The optimized path: one set-based, single-pass SQL statement. All of the attribution
 * math from METHODOLOGY.md is pushed into DuckDB — the JVM only reads back
 * (portfolios x sectors) result rows.
 *
 * `positionsSource` is normally the positions_daily table but can be a
 * read_parquet(...) expression to scan the Parquet file directly.
 */
fun attributionSql(
    from: LocalDate,
    to: LocalDate,
    perDay: Boolean,
    positionsSource: String = "positions_daily",
    portfolioFilter: Int? = null,
): String {
    val pfWhere = portfolioFilter?.let { "AND portfolio_id = $it" } ?: ""
    val pfSpine = portfolioFilter?.let { "WHERE portfolio_id = $it" } ?: ""
    val finalSelect = if (perDay) {
        """
        SELECT portfolio_id, date, sector, wp, wb, rp, rb_i, rb_total,
               allocation, selection, interaction
        FROM daily
        ORDER BY portfolio_id, date, sector
        """
    } else {
        """
        SELECT portfolio_id, sector,
               avg(wp) AS avg_wp, avg(wb) AS avg_wb,
               sum(allocation) AS allocation,
               sum(selection)  AS selection,
               sum(interaction) AS interaction
        FROM daily
        GROUP BY portfolio_id, sector
        ORDER BY portfolio_id, sector
        """
    }
    return """
    WITH sec_ret AS (
        SELECT date, security_id, ret
        FROM benchmark_returns
        WHERE date BETWEEN DATE '$from' AND DATE '$to'
    ),
    -- Prior-close market value per holding = the attribution weight basis
    -- (METHODOLOGY.md Conventions §4). The lag runs over full history up to the
    -- period end so the first in-range day still sees its prior close.
    pos_lag AS (
        SELECT portfolio_id, security_id, date,
               lag(market_value) OVER (PARTITION BY portfolio_id, security_id ORDER BY date) AS basis
        FROM $positionsSource
        WHERE date <= DATE '$to' $pfWhere
    ),
    port_sector AS (
        SELECT p.portfolio_id, p.date, s.sector,
               sum(p.basis)         AS basis,
               sum(p.basis * r.ret) AS basis_ret
        FROM pos_lag p
        JOIN securities s USING (security_id)
        JOIN sec_ret r ON r.security_id = p.security_id AND r.date = p.date
        WHERE p.basis IS NOT NULL AND p.date >= DATE '$from'
        GROUP BY ALL
    ),
    port_sector_w AS (
        SELECT portfolio_id, date, sector,
               basis / sum(basis) OVER (PARTITION BY portfolio_id, date) AS wp,
               basis_ret / basis AS rp
        FROM port_sector
    ),
    bench_sector AS (
        SELECT b.date, s.sector,
               sum(b.weight)         AS wb,
               sum(b.weight * r.ret) / sum(b.weight) AS rb_i
        FROM benchmark_weights b
        JOIN securities s USING (security_id)
        JOIN sec_ret r ON r.security_id = b.security_id AND r.date = b.date
        GROUP BY ALL
    ),
    bench_sector_tot AS (
        SELECT date, sector, wb, rb_i,
               sum(wb * rb_i) OVER (PARTITION BY date)
                   / sum(wb) OVER (PARTITION BY date) AS rb_total
        FROM bench_sector
    ),
    -- Benchmark (date, sector) grid x portfolios: sectors the portfolio does not hold
    -- still produce an allocation effect (wp = 0; rp defaults to rb_i so S = I = 0).
    spine AS (
        SELECT pf.portfolio_id, b.date, b.sector, b.wb, b.rb_i, b.rb_total
        FROM (SELECT portfolio_id FROM portfolios $pfSpine) pf
        CROSS JOIN bench_sector_tot b
    ),
    daily AS (
        SELECT
            sp.portfolio_id, sp.date, sp.sector,
            coalesce(p.wp, 0.0)    AS wp,
            sp.wb,
            coalesce(p.rp, sp.rb_i) AS rp,
            sp.rb_i, sp.rb_total,
            (coalesce(p.wp, 0.0) - sp.wb) * (sp.rb_i - sp.rb_total)          AS allocation,
            sp.wb * (coalesce(p.rp, sp.rb_i) - sp.rb_i)                      AS selection,
            (coalesce(p.wp, 0.0) - sp.wb) * (coalesce(p.rp, sp.rb_i) - sp.rb_i) AS interaction
        FROM spine sp
        LEFT JOIN port_sector_w p USING (portfolio_id, date, sector)
    )
    $finalSelect
    """.trimIndent()
}

fun optimizedAttribution(
    conn: Connection,
    from: LocalDate,
    to: LocalDate,
    positionsSource: String = "positions_daily",
    portfolioFilter: Int? = null,
): List<AttributionRow> =
    conn.createStatement().use { st ->
        st.executeQuery(attributionSql(from, to, perDay = false, positionsSource, portfolioFilter)).use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        AttributionRow(
                            portfolioId = rs.getInt(1),
                            sector = rs.getString(2),
                            avgPortfolioWeight = rs.getDouble(3),
                            avgBenchmarkWeight = rs.getDouble(4),
                            allocation = rs.getDouble(5),
                            selection = rs.getDouble(6),
                            interaction = rs.getDouble(7),
                        ),
                    )
                }
            }
        }
    }

fun optimizedAttributionDaily(
    conn: Connection,
    from: LocalDate,
    to: LocalDate,
    portfolioFilter: Int? = null,
): List<DailySectorRow> =
    conn.createStatement().use { st ->
        st.executeQuery(attributionSql(from, to, perDay = true, portfolioFilter = portfolioFilter)).use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        DailySectorRow(
                            portfolioId = rs.getInt(1),
                            date = rs.getDate(2).toLocalDate(),
                            sector = rs.getString(3),
                            wp = rs.getDouble(4),
                            wb = rs.getDouble(5),
                            rp = rs.getDouble(6),
                            rbI = rs.getDouble(7),
                            rbTotal = rs.getDouble(8),
                            allocation = rs.getDouble(9),
                            selection = rs.getDouble(10),
                            interaction = rs.getDouble(11),
                        ),
                    )
                }
            }
        }
    }
