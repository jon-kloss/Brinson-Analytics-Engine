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
