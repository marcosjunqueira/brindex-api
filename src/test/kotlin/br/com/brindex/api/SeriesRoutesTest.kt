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
import kotlin.test.assertTrue

class SeriesRoutesTest {

    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("brindex-test", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE series (
                        code TEXT PRIMARY KEY,
                        domain TEXT NOT NULL,
                        name TEXT NOT NULL,
                        metadata TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    """
                    CREATE TABLE points (
                        series_code TEXT NOT NULL REFERENCES series(code),
                        date TEXT NOT NULL,
                        value TEXT NOT NULL,
                        extra_values TEXT,
                        source_updated_at TEXT NOT NULL,
                        PRIMARY KEY (series_code, date)
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    """
                    INSERT INTO series (code, domain, name, metadata, created_at) VALUES
                    ('TD:LFT:2026-03-01:BUY', 'treasury-direct', 'Tesouro Direto LFT 2026-03-01 (BUY)', '{"maturity":"2026-03-01","series":"LFT","side":"BUY"}', '2026-09-09T00:00:00Z'),
                    ('PTAX:USD:SELL', 'ptax', 'PTAX USD SELL', '{}', '2026-09-09T00:00:00Z'),
                    ('CDI:SGS:4391', 'cdi', 'CDI', '{}', '2026-09-09T00:00:00Z')
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    """
                    INSERT INTO points (series_code, date, value, extra_values, source_updated_at) VALUES
                    ('TD:LFT:2026-03-01:BUY', '2026-01-02', '18105.30', '{"rate":"0.000164","base_price":"18094.98"}', '2026-09-09T00:00:00Z'),
                    ('TD:LFT:2026-03-01:BUY', '2026-01-05', '18115.38', '{"rate":"0.000134","base_price":"18105.04"}', '2026-09-09T00:00:00Z'),
                    ('TD:LFT:2026-03-01:BUY', '2026-01-06', '1234.5678901234', NULL, '2026-09-09T00:00:00Z'),
                    ('PTAX:USD:SELL', '2026-01-02', '5.4321', NULL, '2026-09-09T00:00:00Z')
                    """.trimIndent()
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `series lists all seeded series`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"code\":\"TD:LFT:2026-03-01:BUY\""))
        assertTrue(body.contains("\"code\":\"PTAX:USD:SELL\""))
        assertTrue(body.contains("\"code\":\"CDI:SGS:4391\""))
    }

    @Test
    fun `series filters by domain`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series?domain=ptax")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("PTAX:USD:SELL"))
        assertTrue(!body.contains("CDI:SGS:4391"))
    }

    @Test
    fun `series filters by domain with no matches returns empty list`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series?domain=nonexistent")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    @Test
    fun `points returns all points in date order for unfiltered range`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/TD%3ALFT%3A2026-03-01%3ABUY/points")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val firstIndex = body.indexOf("2026-01-02")
        val secondIndex = body.indexOf("2026-01-05")
        val thirdIndex = body.indexOf("2026-01-06")
        assertTrue(firstIndex in 0 until secondIndex)
        assertTrue(secondIndex in 0 until thirdIndex)
    }

    @Test
    fun `points respects since and until boundaries inclusively`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/TD%3ALFT%3A2026-03-01%3ABUY/points?since=2026-01-05&until=2026-01-05")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("2026-01-05"))
        assertTrue(!body.contains("2026-01-02"))
        assertTrue(!body.contains("2026-01-06"))
    }

    @Test
    fun `points for unknown code returns 404`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/DOES%3ANOT%3AEXIST/points")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"series not found"}""", response.bodyAsText())
    }

    @Test
    fun `points preserves exact decimal precision without float drift`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/TD%3ALFT%3A2026-03-01%3ABUY/points")
        val body = response.bodyAsText()
        assertTrue(body.contains("\"value\":1234.5678901234"))
    }

    @Test
    fun `points keeps null extra_values as JSON null, not omitted`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/PTAX%3AUSD%3ASELL/points")
        val body = response.bodyAsText()
        assertTrue(body.contains("\"extra_values\":null"))
    }

    @Test
    fun `points latest returns most recent point`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/TD%3ALFT%3A2026-03-01%3ABUY/points/latest")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"date\":\"2026-01-06\""))
        assertTrue(body.contains("\"value\":1234.5678901234"))
    }

    @Test
    fun `points latest for unknown code returns 404`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/DOES%3ANOT%3AEXIST/points/latest")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"series not found"}""", response.bodyAsText())
    }

    @Test
    fun `points latest for series with no points returns 404`() = testApplication {
        application { module(dbPath = dbFile.absolutePath) }
        val response = client.get("/series/CDI%3ASGS%3A4391/points/latest")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"no points for series"}""", response.bodyAsText())
    }
}
