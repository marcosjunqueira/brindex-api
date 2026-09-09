package br.com.brindex.api

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
 */
class SeriesRepository(private val dbPath: String) {

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    fun listSeries(domain: String?): List<SeriesRow> = connect().use { conn ->
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
                                code = rs.getString("code"),
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

    fun seriesExists(code: String): Boolean = connect().use { conn ->
        conn.prepareStatement("SELECT 1 FROM series WHERE code = ? LIMIT 1").use { stmt ->
            stmt.setString(1, code)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    fun listPoints(code: String, since: String?, until: String?): List<PointRow> = connect().use { conn ->
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

    fun latestPoint(code: String): PointRow? = connect().use { conn ->
        val sql = "SELECT date, value, extra_values, source_updated_at FROM points " +
            "WHERE series_code = ? ORDER BY date DESC LIMIT 1"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, code)
            stmt.executeQuery().use { rs -> rs.mapPoints().firstOrNull() }
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
