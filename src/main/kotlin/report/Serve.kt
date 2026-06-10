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
 *   GET /                 the dashboard (same bundled assets as the static site)
 *   GET /api/data         dashboard JSON computed from the database (cached at boot)
 *   GET /data.json        alias of /api/data, so the static bootstrap path also gets live data
 *   GET /healthz          liveness probe
 *
 * The JSON is computed once at startup (the expensive part is the same single-pass
 * attribution query the bench measures) and served from memory — analytics over
 * end-of-day data don't change between batch loads, so per-request recompute would
 * buy nothing. CORS is wide open: the data is synthetic and public.
 */
fun serve(dbPath: Path, port: Int, echo: (String) -> Unit) {
    val json: ByteArray
    val started = System.currentTimeMillis()
    etl.openDatabase(dbPath).use { conn: Connection ->
        json = buildDashboardJson(conn).toByteArray(Charsets.UTF_8)
    }
    echo("Computed dashboard JSON in ${System.currentTimeMillis() - started} ms (%,d bytes)".fmt(json.size))

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

    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.createContext("/") { ex ->
        val path = ex.requestURI.path.trimStart('/')
        when {
            path == "api/data" || path == "data.json" -> ex.respond(200, json, "application/json; charset=utf-8")
            path == "healthz" -> ex.respond(200, "ok".toByteArray(), "text/plain")
            path.isEmpty() || path == "index.html" -> ex.respond(200, assets.getValue("index.html"), contentType("index.html"))
            assets.containsKey(path) -> ex.respond(200, assets.getValue(path), contentType(path))
            else -> ex.respond(404, "not found".toByteArray(), "text/plain")
        }
    }
    server.start()
    echo("Serving dashboard + API on http://0.0.0.0:$port (Ctrl+C to stop)")
}
