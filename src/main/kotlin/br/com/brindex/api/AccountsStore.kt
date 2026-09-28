package br.com.brindex.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteOpenMode
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** Stripe subscription statuses that keep API access on. `past_due` is included so a failed card
 * doesn't cut access off while Stripe is still retrying the payment. */
val ACCESS_STATUSES = setOf("active", "trialing", "past_due")

/** Keys are 256-bit random tokens, so a plain SHA-256 (no salt, no slow KDF) is enough to make a
 * leaked accounts database useless for calling the API. Must match the admin service's hashing. */
fun hashApiKey(plain: String): String =
    MessageDigest.getInstance("SHA-256").digest(plain.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Read-only view of the accounts database (ACCOUNTS_DB_PATH) that the separate admin/billing
 * service owns and writes: this API only checks whether a presented key is active. Opened the same
 * way as [SeriesRepository] — no CREATE, `query_only` — for the same reasons.
 */
class AccountsStore(private val dbPath: String) {

    private val connectionProperties = SQLiteConfig().apply {
        resetOpenMode(SQLiteOpenMode.CREATE)
        busyTimeout = 5_000
    }.toProperties()

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath", connectionProperties).also { conn ->
            conn.createStatement().use { it.execute("PRAGMA query_only = ON") }
        }

    suspend fun verifySchema() = withContext(Dispatchers.IO) {
        check(File(dbPath).isFile) {
            "accounts database '$dbPath' does not exist — point ACCOUNTS_DB_PATH at the admin service's database"
        }
        connect().use { conn ->
            conn.metaData.getTables(null, null, "accounts", null).use { rs ->
                check(rs.next()) { "accounts database at '$dbPath' has no 'accounts' table" }
            }
        }
    }

    /** Id of the account owning [plainKey] if it currently has access, else null. */
    suspend fun activeAccountId(plainKey: String): Long? = withContext(Dispatchers.IO) {
        connect().use { conn ->
            conn.prepareStatement("SELECT id, status, disabled FROM accounts WHERE key_hash = ?").use { stmt ->
                stmt.setString(1, hashApiKey(plainKey))
                stmt.executeQuery().use { rs ->
                    if (rs.next() && rs.getInt("disabled") == 0 && rs.getString("status") in ACCESS_STATUSES) {
                        rs.getLong("id")
                    } else {
                        null
                    }
                }
            }
        }
    }
}
