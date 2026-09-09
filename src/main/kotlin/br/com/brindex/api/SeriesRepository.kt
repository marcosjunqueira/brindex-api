package br.com.brindex.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

data class SeriesRow(
    val code: String,
    val domain: String,
    val name: String,
    val metadata: String
)

data class PointRow(
    val date: String,
    val value: String?,
    val extraValues: String?,
    val sourceUpdatedAt: String
)

/**
 * Opens a new JDBC connection per call rather than holding one open: sqlite-jdbc Connections
 * aren't safe for concurrent multi-threaded use, and Netty may dispatch requests across threads.
 * Fine for a low-QPS, personal-use read API; revisit with a pool if that ever changes.
 *
 * Every method runs its blocking JDBC work on `Dispatchers.IO` rather than whatever (small,
 * fixed-size) dispatcher Ktor's call pipeline happens to be running on — a blocking `connect()`/
 * query directly on that pool can stall unrelated concurrent requests (including `/health`) once
 * as many requests are in flight as there are pool threads.
 */
class SeriesRepository(private val dbPath: String) {

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    /** Fails fast with a clear message if `dbPath` doesn't point at a database with the expected
     * tables — called once at startup so a missing/wrong DB is a loud, immediate failure instead
     * of every route silently 500ing later while `/health` keeps reporting the process is fine. */
    suspend fun verifySchema() = withContext(Dispatchers.IO) {
        connect().use { conn ->
            val tables = buildSet {
                conn.metaData.getTables(null, null, "series", null).use { rs -> if (rs.next()) add("series") }
                conn.metaData.getTables(null, null, "points", null).use { rs -> if (rs.next()) add("points") }
            }
            check("series" in tables && "points" in tables) {
                "database at '$dbPath' is missing the 'series'/'points' tables — " +
                    "point BRINDEX_DB_PATH at a database brindex-ingest has already populated"
            }
        }
    }

    suspend fun listSeries(domain: String?): List<SeriesRow> = withContext(Dispatchers.IO) {
        connect().use { conn ->
            val sql = if (domain != null) {
                "SELECT code, domain, name, metadata FROM series WHERE domain = ? ORDER BY code"
            } else {
                "SELECT code, domain, name, metadata FROM series ORDER BY code"
            }
            conn.prepareStatement(sql).use { stmt ->
                if (domain != null) stmt.setString(1, domain)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                SeriesRow(
                                    code = checkNotNull(rs.getString("code")) { "series.code was SQL NULL" },
                                    domain = rs.getString("domain"),
                                    name = rs.getString("name"),
                                    metadata = rs.getString("metadata")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun seriesExists(code: String): Boolean = withContext(Dispatchers.IO) {
        connect().use { conn ->
            conn.prepareStatement("SELECT 1 FROM series WHERE code = ? LIMIT 1").use { stmt ->
                stmt.setString(1, code)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    suspend fun listPoints(code: String, since: String?, until: String?): List<PointRow> =
        withContext(Dispatchers.IO) {
            connect().use { conn ->
                val conditions = buildString {
                    append("series_code = ?")
                    if (since != null) append(" AND date >= ?")
                    if (until != null) append(" AND date <= ?")
                }
                val sql = "SELECT date, value, extra_values, source_updated_at FROM points " +
                    "WHERE $conditions ORDER BY date"
                conn.prepareStatement(sql).use { stmt ->
                    var index = 1
                    stmt.setString(index++, code)
                    if (since != null) stmt.setString(index++, since)
                    if (until != null) stmt.setString(index, until)
                    stmt.executeQuery().use { rs -> rs.mapPoints() }
                }
            }
        }

    suspend fun latestPoint(code: String): PointRow? = withContext(Dispatchers.IO) {
        connect().use { conn ->
            val sql = "SELECT date, value, extra_values, source_updated_at FROM points " +
                "WHERE series_code = ? ORDER BY date DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, code)
                stmt.executeQuery().use { rs -> rs.mapPoints().firstOrNull() }
            }
        }
    }

    private fun ResultSet.mapPoints(): List<PointRow> = buildList {
        while (next()) {
            add(
                PointRow(
                    date = getString("date"),
                    value = getString("value"),
                    extraValues = getString("extra_values"),
                    sourceUpdatedAt = getString("source_updated_at")
                )
            )
        }
    }
}
