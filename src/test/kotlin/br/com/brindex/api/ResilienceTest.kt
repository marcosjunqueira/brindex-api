package br.com.brindex.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResilienceTest {

    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("brindex-resilience-test", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    "CREATE TABLE series (code TEXT PRIMARY KEY, domain TEXT NOT NULL, " +
                        "name TEXT NOT NULL, metadata TEXT NOT NULL, created_at TEXT NOT NULL)"
                )
                stmt.executeUpdate(
                    "CREATE TABLE points (series_code TEXT NOT NULL REFERENCES series(code), " +
                        "date TEXT NOT NULL, value TEXT, extra_values TEXT, " +
                        "source_updated_at TEXT NOT NULL, PRIMARY KEY (series_code, date))"
                )
                stmt.executeUpdate(
                    "INSERT INTO series (code, domain, name, metadata, created_at) VALUES " +
                        "('PTAX:USD:SELL', 'ptax', 'PTAX USD SELL', '{}', '2026-09-09T00:00:00Z')"
                )
                stmt.executeUpdate(
                    "INSERT INTO points (series_code, date, value, extra_values, source_updated_at) VALUES " +
                        "('PTAX:USD:SELL', '2026-01-02', '5.4321', NULL, '2026-09-09T00:00:00Z'), " +
                        "('PTAX:USD:SELL', '2026-01-05', '5.4400', NULL, '2026-09-09T00:00:00Z')"
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `ready responds ok when the database is readable`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `ready responds 503 once the database file goes away, while health stays ok`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        client.get("/ready") // start the application before removing the file
        assertTrue(dbFile.delete())
        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertEquals("""{"error":"database unavailable"}""", ready.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `startup fails fast when the database file does not exist`() = testApplication {
        val missing = File(dbFile.parentFile, "does-not-exist-${System.nanoTime()}.sqlite")
        application {
            val exception = assertFailsWith<IllegalStateException> { module(dbPath = missing.absolutePath) }
            assertTrue(exception.message!!.contains("does not exist"))
        }
        client.get("/health")
        // A read-only open must never create the file as a side effect.
        assertTrue(!missing.exists())
    }

    @Test
    fun `a valid X-Request-Id is echoed back, otherwise one is generated`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val echoed = client.get("/health") { header(HttpHeaders.XRequestId, "abc-123") }
        assertEquals("abc-123", echoed.headers[HttpHeaders.XRequestId])

        val generated = client.get("/health") { header(HttpHeaders.XRequestId, "bad id with spaces") }
        val id = generated.headers[HttpHeaders.XRequestId]
        assertTrue(id != null && id.isNotBlank())
        assertNotEquals("bad id with spaces", id)
    }

    @Test
    fun `points over the row cap are rejected instead of silently truncated`() = testApplication {
        application { module(dbPath = dbFile.absolutePath, pointsMaxRows = 1) }
        val response = client.get("/series/PTAX/USD/SELL/points")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            """{"error":"range has more than 1 points; narrow it with since/until"}""",
            response.bodyAsText()
        )
        val narrowed = client.get("/series/PTAX/USD/SELL/points?since=2026-01-05")
        assertEquals(HttpStatusCode.OK, narrowed.status)
    }

    @Test
    fun `points exactly at the row cap are returned`() = testApplication {
        application { module(dbPath = dbFile.absolutePath, pointsMaxRows = 2) }
        val response = client.get("/series/PTAX/USD/SELL/points")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an invalid POINTS_MAX_ROWS fails fast`() {
        assertEquals(DEFAULT_POINTS_MAX_ROWS, parsePointsMaxRows(null))
        assertEquals(500, parsePointsMaxRows(" 500 "))
        assertFailsWith<IllegalStateException> { parsePointsMaxRows("0") }
        assertFailsWith<IllegalStateException> { parsePointsMaxRows("abc") }
    }
}
