package br.com.brindex.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    private lateinit var dbFile: File

    // Explicit, isolated dbPath rather than the bare `module()` default: that default reads
    // BRINDEX_DB_PATH from the ambient environment, and a dev machine that happens to have it set
    // (e.g. from a prior manual run) would otherwise make this test silently exercise a real
    // database instead of a throwaway fixture, breaking the "fixture-seeded, never live" testing
    // discipline CLAUDE.md documents.
    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("brindex-app-test", ".sqlite")
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
    fun `health endpoint responds ok`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }
}
