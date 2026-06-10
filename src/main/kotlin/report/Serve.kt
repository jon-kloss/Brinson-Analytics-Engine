package report

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.Connection
import java.util.zip.GZIPOutputStream

/**
 * `brinson serve` — a small live backend over the DuckDB file, built on the JDK's
 * own HTTP server (zero dependencies, matching the repo's discipline).
 *
 * The API is shaped by the UI's access pattern — each endpoint returns only what a
 * specific stage of the page needs:
 *
 *   GET /api/portfolios              picker list: [{id, name}]            (~1.6 KB)
 *   GET /api/market                  shared context: dates, rb, sectors   (~13 KB)
 *   GET /api/portfolio/{id}          returns, risk, attribution, contributors
 *   GET /api/portfolio/{id}/weights  the weights matrix — 60% of a portfolio's
 *                                    bytes feeding one below-the-fold chart,
 *                                    so it loads after first paint
 *   GET /api/data | /data.json       the full document (bulk / static parity)
 *   GET /healthz                     liveness probe
 *   GET /                            the dashboard (same bundled assets as Pages)
 *
 * Everything is computed once at startup and pre-compressed; responses are served
 * from memory with gzip when the client accepts it. CORS is wide open: the data is
 * synthetic and public.
 */
fun serve(dbPath: Path, port: Int, echo: (String) -> Unit) {
    val started = System.currentTimeMillis()

    class Payload(text: ByteArray, val type: String) {
        val plain: ByteArray = text
        val gz: ByteArray = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(text) }
        }.toByteArray()
    }

    val json = "application/json; charset=utf-8"
    fun jsonPayload(s: String) = Payload(s.toByteArray(Charsets.UTF_8), json)

    // Recomputed on boot and after each import; reads see a consistent snapshot.
    class Snapshot(parts: DashboardJson) {
        val market = jsonPayload(parts.marketJson)
        val portfolios = jsonPayload(parts.portfoliosJson)
        val cores = parts.portfolioCore.mapValues { (_, v) -> jsonPayload(v) }
        val weights = parts.portfolioWeights.mapValues { (_, v) -> jsonPayload(v) }
        val full = jsonPayload(parts.fullJson())
    }

    fun rebuild(): Snapshot =
        Snapshot(etl.openDatabase(dbPath).use { conn: Connection -> buildDashboardParts(conn) })

    var snap = rebuild()
    val health = Payload("ok".toByteArray(), "text/plain")
    val notFound = Payload("not found".toByteArray(), "text/plain")

    val assets = listOf("index.html", "styles.css", "dashboard.js", "guide.html").associateWith { name ->
        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "application/javascript; charset=utf-8"
        }
        Payload(object {}.javaClass.getResourceAsStream("/dashboard/$name")!!.use { it.readBytes() }, type)
    }

    echo(
        ("Boot in ${System.currentTimeMillis() - started} ms — payloads (plain/gzip B): " +
            "portfolios %,d/%,d · market %,d/%,d · core ~%,d/%,d · weights ~%,d/%,d · full %,d/%,d").fmt(
            snap.portfolios.plain.size, snap.portfolios.gz.size, snap.market.plain.size, snap.market.gz.size,
            snap.cores.values.first().plain.size, snap.cores.values.first().gz.size,
            snap.weights.values.first().plain.size, snap.weights.values.first().gz.size,
            snap.full.plain.size, snap.full.gz.size,
        ),
    )

    fun HttpExchange.respond(status: Int, p: Payload) {
        val gzipOk = requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true
        responseHeaders.add("Access-Control-Allow-Origin", "*")
        responseHeaders.add("Content-Type", p.type)
        val body = if (gzipOk) {
            responseHeaders.add("Content-Encoding", "gzip")
            p.gz
        } else {
            p.plain
        }
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.createContext("/") { ex ->
        val path = ex.requestURI.path.trimStart('/')
        when {
            path == "api/import" && ex.requestMethod == "POST" -> {
                val body = ex.requestBody.readNBytes(10 shl 20) // 10 MB cap
                val name = (ex.requestURI.rawQuery ?: "")
                    .split("&").firstOrNull { it.startsWith("name=") }
                    ?.let { java.net.URLDecoder.decode(it.removePrefix("name="), Charsets.UTF_8) }
                    ?.takeIf { it.isNotBlank() } ?: "Imported portfolio"
                try {
                    val tmp = Files.createTempFile("brinson-import", ".csv")
                    try {
                        Files.write(tmp, body)
                        val result = etl.openDatabase(dbPath).use { conn: Connection ->
                            etl.importPortfolio(conn, tmp, name.take(80))
                        }
                        snap = rebuild()
                        echo("Imported '${result.name}' as portfolio ${result.portfolioId} (${result.positionRows} rows); payloads rebuilt")
                        ex.respond(
                            201,
                            jsonPayload(
                                "{\"id\":${result.portfolioId},\"name\":${'"'}${result.name.replace("\\", "").replace("\"", "")}${'"'}," +
                                    "\"rows\":${result.positionRows},\"newSecurities\":${result.newSecurities},\"flowDays\":${result.flowDays}}",
                            ),
                        )
                    } finally {
                        Files.deleteIfExists(tmp)
                    }
                } catch (e: IllegalArgumentException) {
                    ex.respond(400, Payload(("import rejected: " + (e.message ?: "invalid CSV")).toByteArray(), "text/plain"))
                } catch (e: Exception) {
                    ex.respond(500, Payload(("import failed: " + (e.message ?: e.toString())).toByteArray(), "text/plain"))
                }
            }
            path == "api/portfolios" -> ex.respond(200, snap.portfolios)
            path == "api/market" -> ex.respond(200, snap.market)
            path.startsWith("api/portfolio/") -> {
                val rest = path.removePrefix("api/portfolio/")
                val payload = when {
                    rest.endsWith("/weights") ->
                        rest.removeSuffix("/weights").toIntOrNull()?.let { snap.weights[it] }
                    else -> rest.toIntOrNull()?.let { snap.cores[it] }
                }
                if (payload != null) ex.respond(200, payload) else ex.respond(404, notFound)
            }
            path == "api/data" || path == "data.json" -> ex.respond(200, snap.full)
            path == "healthz" -> ex.respond(200, health)
            path.isEmpty() || path == "index.html" -> ex.respond(200, assets.getValue("index.html"))
            assets.containsKey(path) -> ex.respond(200, assets.getValue(path))
            else -> ex.respond(404, notFound)
        }
    }
    server.start()
    echo("Serving dashboard + API on http://0.0.0.0:$port (Ctrl+C to stop)")
}
