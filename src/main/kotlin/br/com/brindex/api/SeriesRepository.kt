package br.com.brindex.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteOpenMode
import java.io.File
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

    // Opened read-write *without* SQLITE_OPEN_CREATE, then locked down with `query_only`:
    // - not SQLITE_OPEN_READONLY: brindex-ingest uses a rollback journal, and if it dies
    //   mid-transaction the next connection must roll the hot journal back before it can read,
    //   which a read-only connection can't do (SQLITE_READONLY_ROLLBACK on every query);
    // - no CREATE: a wrong or deleted path fails instead of silently creating an empty file;
    // - `query_only`: any INSERT/UPDATE/DDL issued through this connection is rejected.
    // busy_timeout makes a read that hits the ingest's write lock wait briefly instead of
    // failing at once with SQLITE_BUSY.
    private val connectionProperties = SQLiteConfig().apply {
        resetOpenMode(SQLiteOpenMode.CREATE)
        busyTimeout = BUSY_TIMEOUT_MS
    }.toProperties()

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath", connectionProperties).also { conn ->
            conn.createStatement().use { it.execute("PRAGMA query_only = ON") }
        }

    /** Fails fast with a clear message if `dbPath` doesn't point at a database with the expected
     * tables — called once at startup so a missing/wrong DB is a loud, immediate failure instead
     * of every route silently 500ing later while `/health` keeps reporting the process is fine. */
    suspend fun verifySchema() = withContext(Dispatchers.IO) {
        check(File(dbPath).isFile) {
            "database file '$dbPath' does not exist — " +
                "point BRINDEX_DB_PATH at a database brindex-ingest has already populated"
        }
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

    /** Cheap query used by the readiness check; throws if the database can't be read. */
    suspend fun ping() = withContext(Dispatchers.IO) {
        connect().use { conn ->
            conn.prepareStatement("SELECT 1 FROM series LIMIT 1").use { stmt -> stmt.executeQuery().close() }
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

    /** Returns at most [limit] points, oldest first. */
    suspend fun listPoints(code: String, since: String?, until: String?, limit: Int): List<PointRow> =
        withContext(Dispatchers.IO) {
            connect().use { conn ->
                val conditions = buildString {
                    append("series_code = ?")
                    if (since != null) append(" AND date >= ?")
                    if (until != null) append(" AND date <= ?")
                }
                val sql = "SELECT date, value, extra_values, source_updated_at FROM points " +
                    "WHERE $conditions ORDER BY date LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    var index = 1
                    stmt.setString(index++, code)
                    if (since != null) stmt.setString(index++, since)
                    if (until != null) stmt.setString(index++, until)
                    stmt.setInt(index, limit)
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

    private companion object {
        const val BUSY_TIMEOUT_MS = 5_000
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
