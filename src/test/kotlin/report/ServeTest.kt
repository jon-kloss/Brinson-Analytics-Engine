package report

import datagen.DataGenerator
import datagen.GenParams
import etl.loadFromParquet
import etl.openDatabase
import etl.openInMemory
import etl.writeParquet
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeTest {

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `serve exposes live JSON, dashboard assets, and health`() {
        val params = GenParams(seed = 7L, securities = 40, portfolios = 2, tradingDays = 12, scale = 0.05)
        val tmp = Files.createTempDirectory("brinson-serve")
        try {
            openInMemory().use { mem ->
                DataGenerator(params).generateInto(mem)
                writeParquet(mem, tmp.resolve("pq"))
            }
            openDatabase(tmp.resolve("d.duckdb")).use { it: java.sql.Connection ->
                loadFromParquet(it, tmp.resolve("pq"))
            }
            val port = ServerSocket(0).use { it.localPort }
            serve(tmp.resolve("d.duckdb"), port) {}

            val client = HttpClient.newHttpClient()
            fun get(path: String): HttpResponse<String> =
                client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            val api = get("/api/data")
            assertEquals(200, api.statusCode())
            assertEquals("*", api.headers().firstValue("Access-Control-Allow-Origin").get())
            assertContains(api.body(), "\"portfolios\"")
            // /data.json aliases the live payload, so the static bootstrap path stays live.
            assertEquals(api.body(), get("/data.json").body())

            // Access-pattern endpoints: each returns only what one UI stage consumes.
            val list = get("/api/portfolios")
            assertEquals(200, list.statusCode())
            assertContains(list.body(), "\"name\"")
            assertFalse(list.body().contains("\"rp\""), "picker list must not carry series")

            val mkt = get("/api/market")
            assertEquals(200, mkt.statusCode())
            assertContains(mkt.body(), "\"rb\"")
            assertContains(mkt.body(), "\"dates\"")
            assertFalse(mkt.body().contains("\"portfolios\""), "market context is portfolio-free")

            val p1 = get("/api/portfolio/1")
            assertEquals(200, p1.statusCode())
            assertContains(p1.body(), "\"rp\"")
            assertContains(p1.body(), "\"attribution\"")
            assertFalse(p1.body().contains("\"weights\""), "weights ship on their own endpoint")

            val w1 = get("/api/portfolio/1/weights")
            assertEquals(200, w1.statusCode())
            assertContains(w1.body(), "\"weights\"")

            // Builder endpoints: the pick list, and model creation end-to-end.
            val secs = get("/api/securities")
            assertEquals(200, secs.statusCode())
            assertContains(secs.body(), "\"t\":\"SEC001\"")
            fun postModel(query: String, body: String): HttpResponse<String> =
                client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/model$query"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            val made = postModel("?name=My%20Model&rebalance=monthly", "SEC001,60\nSEC002,40\n")
            assertEquals(201, made.statusCode(), made.body())
            assertContains(made.body(), "\"holdings\":2")
            // The snapshot was rebuilt: the new portfolio is immediately live.
            assertContains(get("/api/portfolios").body(), "My Model")
            val newId = Regex("\"id\":(\\d+)").find(made.body())!!.groupValues[1]
            val modelCore = get("/api/portfolio/$newId")
            assertEquals(200, modelCore.statusCode())
            // Two holdings all fit in "top": "bottom" must not repeat them.
            assertContains(modelCore.body(), "\"bottom\":[]")
            assertEquals(400, postModel("", "SEC001,30\nSEC002,30\n").statusCode(), "weights must sum")
            assertEquals(400, postModel("", "NOPE,100\n").statusCode(), "unknown ticker")
            assertEquals(400, postModel("?rebalance=hourly", "SEC001,100\n").statusCode(), "bad frequency")
            assertEquals(400, postModel("", "garbage line\n").statusCode(), "unparseable body")

            // The full document is the pieces spliced back together — they cannot drift.
            assertContains(api.body(), p1.body().dropLast(1), message = "full must embed detail core")
            assertContains(api.body(), w1.body().removePrefix("{").removeSuffix("}"))

            // gzip negotiation: same bytes after decompression.
            val gz = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/portfolio/1"))
                    .header("Accept-Encoding", "gzip").build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals("gzip", gz.headers().firstValue("Content-Encoding").get())
            assertTrue(gz.body().size < p1.body().length / 2, "gzip should at least halve the payload")
            val inflated = java.util.zip.GZIPInputStream(gz.body().inputStream()).readBytes()
            assertEquals(p1.body(), String(inflated, Charsets.UTF_8))

            assertEquals(404, get("/api/portfolio/999").statusCode())
            assertEquals(404, get("/api/portfolio/x").statusCode())
            assertEquals(404, get("/api/portfolio/1/nope").statusCode())
            assertEquals(404, get("/api/meta").statusCode())

            val index = get("/")
            assertEquals(200, index.statusCode())
            assertContains(index.body(), "BrinsonDashboard")
            assertEquals(200, get("/styles.css").statusCode())
            assertEquals(200, get("/dashboard.js").statusCode())
            assertEquals(200, get("/guide.html").statusCode())
            assertEquals(200, get("/healthz").statusCode())
            assertEquals(404, get("/nope").statusCode())
        } finally {
            tmp.deleteRecursively()
        }
    }
}
