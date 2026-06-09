package queries

import java.sql.Connection
import java.time.LocalDate

data class DailyValuation(
    val date: LocalDate,
    /** The trading day mvBegin was observed on (the prior close) — the true period start. */
    val beginDate: LocalDate,
    val mvBegin: Double,
    val mvEnd: Double,
    val externalFlow: Double,
)

/**
 * Daily market values and net external flows for one portfolio — the inputs to the TWR
 * sub-period formula. Only CASHFLOW transactions are external; dividends are income.
 */
fun dailyValuations(
    conn: Connection,
    portfolioId: Int,
    from: LocalDate,
    to: LocalDate,
): List<DailyValuation> {
    val sql = """
        WITH mv AS (
            SELECT date, sum(market_value) AS mv
            FROM positions_daily
            WHERE portfolio_id = $portfolioId AND date <= DATE '$to'
            GROUP BY date
        ),
        flows AS (
            SELECT date, sum(amount) AS cf
            FROM transactions
            WHERE portfolio_id = $portfolioId AND type = 'CASHFLOW' AND date <= DATE '$to'
            GROUP BY date
        )
        SELECT m.date, lag(m.date) OVER (ORDER BY m.date) AS begin_date,
               lag(m.mv) OVER (ORDER BY m.date) AS mv_begin, m.mv AS mv_end,
               coalesce(f.cf, 0.0) AS cf
        FROM mv m
        LEFT JOIN flows f USING (date)
        QUALIFY mv_begin IS NOT NULL AND m.date >= DATE '$from'
        ORDER BY m.date
    """.trimIndent()
    return conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        DailyValuation(
                            date = rs.getDate(1).toLocalDate(),
                            beginDate = rs.getDate(2).toLocalDate(),
                            mvBegin = rs.getDouble(3),
                            mvEnd = rs.getDouble(4),
                            externalFlow = rs.getDouble(5),
                        ),
                    )
                }
            }
        }
    }
}

/** Benchmark daily total returns over the range: rb_t = sum(wb * rb) / sum(wb). */
fun benchmarkDailyReturns(conn: Connection, from: LocalDate, to: LocalDate): List<Double> {
    val sql = """
        SELECT b.date, sum(b.weight * r.ret) / sum(b.weight) AS rb
        FROM benchmark_weights b
        JOIN benchmark_returns r USING (date, security_id)
        WHERE b.date BETWEEN DATE '$from' AND DATE '$to'
        GROUP BY b.date
        ORDER BY b.date
    """.trimIndent()
    return conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            buildList { while (rs.next()) add(rs.getDouble(2)) }
        }
    }
}

data class SecurityContribution(
    val ticker: String,
    val sector: String,
    val contribution: Double,
)

/** Per-security contribution c_i = w_i * r_i, summed daily over the range. */
fun securityContributions(
    conn: Connection,
    portfolioId: Int,
    from: LocalDate,
    to: LocalDate,
): List<SecurityContribution> {
    val sql = """
        WITH pos_lag AS (
            SELECT security_id, date,
                   lag(market_value) OVER (PARTITION BY security_id ORDER BY date) AS basis
            FROM positions_daily
            WHERE portfolio_id = $portfolioId AND date <= DATE '$to'
        ),
        daily AS (
            SELECT p.date, p.security_id,
                   p.basis * r.ret / sum(p.basis) OVER (PARTITION BY p.date) AS contrib
            FROM pos_lag p
            JOIN benchmark_returns r ON r.security_id = p.security_id AND r.date = p.date
            WHERE p.basis IS NOT NULL AND p.date >= DATE '$from'
        )
        SELECT s.ticker, s.sector, sum(d.contrib) AS contribution
        FROM daily d
        JOIN securities s USING (security_id)
        GROUP BY ALL
        ORDER BY contribution DESC
    """.trimIndent()
    return conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            buildList {
                while (rs.next()) {
                    add(SecurityContribution(rs.getString(1), rs.getString(2), rs.getDouble(3)))
                }
            }
        }
    }
}
