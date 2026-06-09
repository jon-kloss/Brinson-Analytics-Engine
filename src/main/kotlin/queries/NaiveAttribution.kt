package queries

import java.sql.Connection
import java.time.LocalDate

/**
 * The naive path, kept deliberately ORM-shaped: pull the entire positions_daily fact
 * table over JDBC into the JVM and aggregate row-at-a-time in hash maps. This is the
 * honest "before" picture — the math is identical to the optimized SQL variant, the
 * difference is *where* the aggregation happens.
 */
fun naiveAttribution(
    conn: Connection,
    from: LocalDate,
    to: LocalDate,
    portfolioFilter: Int? = null,
): List<AttributionRow> {
    val sectorOf = fetchSectors(conn)

    // (date, security) -> daily total return. ~250k entries at full scale.
    val rets = HashMap<Long, Double>()
    conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT date, security_id, ret FROM benchmark_returns " +
                "WHERE date BETWEEN DATE '$from' AND DATE '$to'",
        ).use { rs ->
            while (rs.next()) {
                rets[dateSecKey(rs.getDate(1).toLocalDate(), rs.getInt(2))] = rs.getDouble(3)
            }
        }
    }

    // Benchmark side: per (date, sector) weight and weighted return.
    class BenchAgg(var wb: Double = 0.0, var wbRet: Double = 0.0)

    val bench = HashMap<LocalDate, HashMap<String, BenchAgg>>()
    conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT date, security_id, weight FROM benchmark_weights " +
                "WHERE date BETWEEN DATE '$from' AND DATE '$to'",
        ).use { rs ->
            while (rs.next()) {
                val date = rs.getDate(1).toLocalDate()
                val sec = rs.getInt(2)
                val w = rs.getDouble(3)
                val r = rets[dateSecKey(date, sec)] ?: continue
                val agg = bench.getOrPut(date) { HashMap() }.getOrPut(sectorOf[sec]!!) { BenchAgg() }
                agg.wb += w
                agg.wbRet += w * r
            }
        }
    }

    // Portfolio side: stream the big fact table ordered by (portfolio, security, date)
    // and track each holding's previous-day market value (the weight basis) by hand.
    class PortAgg(var basis: Double = 0.0, var basisRet: Double = 0.0)

    val port = HashMap<Int, HashMap<LocalDate, HashMap<String, PortAgg>>>()
    val pfWhere = portfolioFilter?.let { "AND portfolio_id = $it" } ?: ""
    conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT portfolio_id, security_id, date, market_value FROM positions_daily " +
                "WHERE date <= DATE '$to' $pfWhere " +
                "ORDER BY portfolio_id, security_id, date",
        ).use { rs ->
            var prevPf = -1
            var prevSec = -1
            var prevMv = Double.NaN
            while (rs.next()) {
                val pf = rs.getInt(1)
                val sec = rs.getInt(2)
                val date = rs.getDate(3).toLocalDate()
                val mv = rs.getDouble(4)
                if (pf == prevPf && sec == prevSec && !prevMv.isNaN() && date >= from) {
                    val r = rets[dateSecKey(date, sec)]
                    if (r != null) {
                        val agg = port.getOrPut(pf) { HashMap() }
                            .getOrPut(date) { HashMap() }
                            .getOrPut(sectorOf[sec]!!) { PortAgg() }
                        agg.basis += prevMv
                        agg.basisRet += prevMv * r
                    }
                }
                prevPf = pf
                prevSec = sec
                prevMv = mv
            }
        }
    }

    // Assemble Brinson-Fachler effects over the benchmark (date, sector) spine.
    val portfolioIds = fetchPortfolioIds(conn, portfolioFilter)
    class Sums(
        var wp: Double = 0.0, var wb: Double = 0.0,
        var a: Double = 0.0, var s: Double = 0.0, var i: Double = 0.0, var days: Int = 0,
    )

    val sums = HashMap<Int, HashMap<String, Sums>>()
    for ((date, sectors) in bench) {
        var rbTotalNum = 0.0
        var rbTotalDen = 0.0
        for (agg in sectors.values) {
            rbTotalNum += agg.wbRet
            rbTotalDen += agg.wb
        }
        val rbTotal = rbTotalNum / rbTotalDen
        for (pf in portfolioIds) {
            val pfDay = port[pf]?.get(date)
            var basisTotal = 0.0
            if (pfDay != null) for (agg in pfDay.values) basisTotal += agg.basis
            for ((sector, b) in sectors) {
                val rbI = b.wbRet / b.wb
                val pAgg = pfDay?.get(sector)
                val wp = if (pAgg != null && basisTotal > 0.0) pAgg.basis / basisTotal else 0.0
                val rp = if (pAgg != null) pAgg.basisRet / pAgg.basis else rbI
                val out = sums.getOrPut(pf) { HashMap() }.getOrPut(sector) { Sums() }
                out.wp += wp
                out.wb += b.wb
                out.a += (wp - b.wb) * (rbI - rbTotal)
                out.s += b.wb * (rp - rbI)
                out.i += (wp - b.wb) * (rp - rbI)
                out.days += 1
            }
        }
    }

    return sums.flatMap { (pf, bySector) ->
        bySector.map { (sector, s) ->
            AttributionRow(
                portfolioId = pf,
                sector = sector,
                avgPortfolioWeight = s.wp / s.days,
                avgBenchmarkWeight = s.wb / s.days,
                allocation = s.a,
                selection = s.s,
                interaction = s.i,
            )
        }
    }.sortedWith(compareBy({ it.portfolioId }, { it.sector }))
}

private fun dateSecKey(date: LocalDate, securityId: Int): Long =
    date.toEpochDay() * 1_000_000L + securityId

private fun fetchSectors(conn: Connection): Map<Int, String> =
    conn.createStatement().use { st ->
        st.executeQuery("SELECT security_id, sector FROM securities").use { rs ->
            buildMap { while (rs.next()) put(rs.getInt(1), rs.getString(2)) }
        }
    }

private fun fetchPortfolioIds(conn: Connection, portfolioFilter: Int?): List<Int> =
    conn.createStatement().use { st ->
        val where = portfolioFilter?.let { "WHERE portfolio_id = $it" } ?: ""
        st.executeQuery("SELECT portfolio_id FROM portfolios $where ORDER BY 1").use { rs ->
            buildList { while (rs.next()) add(rs.getInt(1)) }
        }
    }
