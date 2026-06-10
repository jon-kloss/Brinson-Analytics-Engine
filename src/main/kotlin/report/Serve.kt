package report

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.Connection

/**
 * `brinson serve` — a small live backend over the DuckDB file, built on the JDK's
 * own HTTP server (zero dependencies, matching the repo's discipline).
 *
 * Routes:
 *   GET /                    the dashboard (same bundled assets as the static site)
 *   GET /api/meta            shared data + a light portfolio list ({id, name} stubs)
 *   GET /api/portfolio/{id}  one portfolio's series, attribution, contributors, weights
 *   GET /api/data            the full document (all portfolios) — bulk consumers
 *   GET /data.json           alias of /api/data, so the static bootstrap path stays live
 *   GET /healthz             liveness probe
 *
 * Payloads are computed once at startup (the expensive part is the same single-pass
 * attribution query the bench measures) and served from memory — analytics over
 * end-of-day data don't change between batch loads, so per-request recompute would
 * buy nothing. CORS is wide open: the data is synthetic and public.
 */
fun serve(dbPath: Path, port: Int, echo: (String) -> Unit) {
    val started = System.currentTimeMillis()
    val parts = etl.openDatabase(dbPath).use { conn: Connection -> buildDashboardParts(conn) }
    val meta = parts.metaJson.toByteArray(Charsets.UTF_8)
    val full = parts.fullJson().toByteArray(Charsets.UTF_8)
    val portfolios = parts.portfolioJson.mapValues { (_, v) -> v.toByteArray(Charsets.UTF_8) }
    echo(
        "Computed dashboard JSON in ${System.currentTimeMillis() - started} ms " +
            "(meta %,d B, %,d portfolios ~%,d B each, full %,d B)"
                .fmt(meta.size, portfolios.size, portfolios.values.first().size, full.size),
    )

    val assets = listOf("index.html", "styles.css", "dashboard.js", "guide.html").associateWith { name ->
        object {}.javaClass.getResourceAsStream("/dashboard/$name")!!.use { it.readBytes() }
    }
    fun contentType(name: String) = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        else -> "application/octet-stream"
    }

    fun HttpExchange.respond(status: Int, body: ByteArray, type: String) {
        responseHeaders.add("Access-Control-Allow-Origin", "*")
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    val json = "application/json; charset=utf-8"
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.createContext("/") { ex ->
        val path = ex.requestURI.path.trimStart('/')
        when {
            path == "api/meta" -> ex.respond(200, meta, json)
            path.startsWith("api/portfolio/") -> {
                val body = path.removePrefix("api/portfolio/").toIntOrNull()?.let { portfolios[it] }
                if (body != null) ex.respond(200, body, json)
                else ex.respond(404, "unknown portfolio".toByteArray(), "text/plain")
            }
            path == "api/data" || path == "data.json" -> ex.respond(200, full, json)
            path == "healthz" -> ex.respond(200, "ok".toByteArray(), "text/plain")
            path.isEmpty() || path == "index.html" -> ex.respond(200, assets.getValue("index.html"), contentType("index.html"))
            assets.containsKey(path) -> ex.respond(200, assets.getValue(path), contentType(path))
            else -> ex.respond(404, "not found".toByteArray(), "text/plain")
        }
    }
    server.start()
    echo("Serving dashboard + API on http://0.0.0.0:$port (Ctrl+C to stop)")
}
