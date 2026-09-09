package br.com.brindex.api

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.options
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
import kotlin.test.assertNull

class CorsTest {

    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("brindex-cors-test", ".sqlite")
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
            }
        }
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `preflight from an allowed origin gets Access-Control-Allow-Origin`() = testApplication {
        application {
            module(dbPath = dbFile.absolutePath, corsAllowedOrigins = listOf("http://localhost:5174"))
        }
        val response = client.options("/series") {
            headers {
                append(HttpHeaders.Origin, "http://localhost:5174")
                append(HttpHeaders.AccessControlRequestMethod, "GET")
            }
        }
        assertEquals("http://localhost:5174", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `GET from an allowed origin gets Access-Control-Allow-Origin`() = testApplication {
        application {
            module(dbPath = dbFile.absolutePath, corsAllowedOrigins = listOf("http://localhost:5174"))
        }
        val response = client.get("/health") {
            headers { append(HttpHeaders.Origin, "http://localhost:5174") }
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:5174", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `request from a non-configured origin gets no Access-Control-Allow-Origin`() = testApplication {
        application {
            module(dbPath = dbFile.absolutePath, corsAllowedOrigins = listOf("http://localhost:5174"))
        }
        val response = client.get("/health") {
            headers { append(HttpHeaders.Origin, "http://evil.example") }
        }
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `CORS is off by default, matching pre-existing behavior`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/health") {
            headers { append(HttpHeaders.Origin, "http://localhost:5174") }
        }
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `a CORS origin missing a scheme fails fast with a clear message`() = testApplication {
        application {
            val exception = assertFailsWith<IllegalStateException> {
                module(dbPath = dbFile.absolutePath, corsAllowedOrigins = listOf("localhost:5174"))
            }
            assertEquals(
                "CORS_ALLOWED_ORIGINS entry 'localhost:5174' is not a full origin " +
                    "(scheme://host[:port]), e.g. http://localhost:5174",
                exception.message,
            )
        }
        client.get("/health")
    }

    @Test
    fun `parseCorsOrigins handles blank, single and multiple comma-separated values`() {
        assertEquals(emptyList(), parseCorsOrigins(null))
        assertEquals(emptyList(), parseCorsOrigins(""))
        assertEquals(emptyList(), parseCorsOrigins("   "))
        assertEquals(listOf("http://localhost:5174"), parseCorsOrigins("http://localhost:5174"))
        assertEquals(
            listOf("http://localhost:5174", "https://cornerstone.local"),
            parseCorsOrigins(" http://localhost:5174 , https://cornerstone.local "),
        )
    }
}
