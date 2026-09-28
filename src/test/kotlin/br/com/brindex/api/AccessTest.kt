package br.com.brindex.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccessTest {

    private lateinit var dbFile: File
    private lateinit var accountsFile: File

    private fun sql(file: File, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt -> statements.forEach { stmt.executeUpdate(it) } }
        }
    }

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("brindex-access-test", ".sqlite")
        accountsFile = File.createTempFile("brindex-accounts-test", ".sqlite")
        sql(
            dbFile,
            "CREATE TABLE series (code TEXT PRIMARY KEY, domain TEXT NOT NULL, " +
                "name TEXT NOT NULL, metadata TEXT NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE points (series_code TEXT NOT NULL REFERENCES series(code), " +
                "date TEXT NOT NULL, value TEXT, extra_values TEXT, " +
                "source_updated_at TEXT NOT NULL, PRIMARY KEY (series_code, date))",
            "INSERT INTO series (code, domain, name, metadata, created_at) VALUES " +
                "('PTAX:USD:SELL', 'ptax', 'PTAX USD SELL', '{}', '2026-09-09T00:00:00Z')",
        )
        // Fixture in the shape the admin/billing service writes (see SPEC_API_ACCESS.md §3).
        sql(
            accountsFile,
            "CREATE TABLE accounts (id INTEGER PRIMARY KEY, key_hash TEXT UNIQUE, status TEXT NOT NULL, " +
                "disabled INTEGER NOT NULL DEFAULT 0)",
            "INSERT INTO accounts (key_hash, status, disabled) VALUES " +
                "('${hashApiKey("brx_active")}', 'active', 0), " +
                "('${hashApiKey("brx_pastdue")}', 'past_due', 0), " +
                "('${hashApiKey("brx_canceled")}', 'canceled', 0), " +
                "('${hashApiKey("brx_disabled")}', 'active', 1)",
        )
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
        accountsFile.delete()
    }

    private fun ApplicationTestBuilder.start(requireApiKey: Boolean = true, rateLimitPerMinute: Int = 60) {
        application {
            module(
                dbPath = dbFile.absolutePath,
                access = AccessConfig(requireApiKey, accountsFile.absolutePath, rateLimitPerMinute),
            )
        }
    }

    private suspend fun HttpClient.getSeries(key: String?) = get("/series") {
        if (key != null) header(HttpHeaders.Authorization, "Bearer $key")
    }

    @Test
    fun `series requires an active key while health stays public`() = testApplication {
        start()
        assertEquals(HttpStatusCode.Unauthorized, client.getSeries(null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getSeries("brx_unknown").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getSeries("brx_canceled").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getSeries("brx_disabled").status)
        assertEquals(HttpStatusCode.OK, client.getSeries("brx_active").status)
        assertEquals(HttpStatusCode.OK, client.getSeries("brx_pastdue").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `v1 routes require a key too`() = testApplication {
        start()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/series").status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/series?api_key=brx_active").status)
    }

    @Test
    fun `key is also accepted as api_key query parameter`() = testApplication {
        start()
        assertEquals(HttpStatusCode.OK, client.get("/series?api_key=brx_active").status)
        assertEquals(HttpStatusCode.OK, client.get("/series/PTAX/USD/SELL/points?api_key=brx_active").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/series?api_key=brx_canceled").status)
    }

    @Test
    fun `malformed authorization header is a 401, not a 500`() = testApplication {
        start()
        val response = client.get("/series") { header(HttpHeaders.Authorization, "Bearer a b c") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `REQUIRE_API_KEY is parsed strictly`() {
        assertEquals(true, AccessConfig.fromEnv(mapOf("REQUIRE_API_KEY" to "true")::get).requireApiKey)
        assertEquals(false, AccessConfig.fromEnv(mapOf<String, String>()::get).requireApiKey)
        assertFailsWith<IllegalStateException> { AccessConfig.fromEnv(mapOf("REQUIRE_API_KEY" to "yes")::get) }
    }

    @Test
    fun `series stays open when keys are not required`() = testApplication {
        start(requireApiKey = false)
        assertEquals(HttpStatusCode.OK, client.getSeries(null).status)
    }

    @Test
    fun `requests over the per-key limit get 429`() = testApplication {
        start(rateLimitPerMinute = 2)
        assertEquals(HttpStatusCode.OK, client.getSeries("brx_active").status)
        assertEquals(HttpStatusCode.OK, client.getSeries("brx_active").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.getSeries("brx_active").status)
        // Buckets are per key: another key is unaffected.
        assertEquals(HttpStatusCode.OK, client.getSeries("brx_pastdue").status)
    }

    @Test
    fun `startup fails fast when the accounts database is missing`() = testApplication {
        val missing = File(accountsFile.parentFile, "no-accounts-${System.nanoTime()}.sqlite")
        application {
            assertFailsWith<IllegalStateException> {
                module(dbPath = dbFile.absolutePath, access = AccessConfig(true, missing.absolutePath))
            }
        }
        client.get("/health")
    }
}
