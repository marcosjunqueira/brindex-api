package br.com.brindex.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("br.com.brindex.api.Application")

// Default cap on rows a single /points response may carry; see POINTS_MAX_ROWS in .env.example.
const val DEFAULT_POINTS_MAX_ROWS = 100_000

// A client-supplied X-Request-Id is echoed into logs, so only accept short, log-safe values.
private val REQUEST_ID = Regex("""[A-Za-z0-9._-]{1,64}""")

private val API_RATE_LIMIT = RateLimitName("api-key")

fun main() {
    val portEnv = System.getenv("PORT")
    val port = if (portEnv == null) {
        8080
    } else {
        portEnv.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("PORT env var '$portEnv' is not a valid TCP port number (1-65535)")
    }
    // Ktor's embedded server registers a JVM shutdown hook on start(): on SIGTERM (systemd,
    // docker stop) it stops accepting connections and lets in-flight requests finish, bounded by
    // the grace period/timeout set here.
    embeddedServer(Netty, configure = {
        connector {
            this.port = port
            host = "0.0.0.0"
        }
        shutdownGracePeriod = 1_000
        shutdownTimeout = 5_000
    }, module = Application::module).start(wait = true)
}

fun parsePointsMaxRows(raw: String?): Int =
    if (raw.isNullOrBlank()) {
        DEFAULT_POINTS_MAX_ROWS
    } else {
        raw.trim().toIntOrNull()?.takeIf { it in 1..10_000_000 }
            ?: error("POINTS_MAX_ROWS env var '$raw' is not an integer between 1 and 10000000")
    }

// Comma-separated origins from CORS_ALLOWED_ORIGINS, e.g. "http://localhost:5174,https://a.b".
// Blank/unset yields an empty list, which means CORS stays off (see module() below).
fun parseCorsOrigins(raw: String?): List<String> =
    raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

fun Application.module(
    dbPath: String = System.getenv("BRINDEX_DB_PATH") ?: "brindex.sqlite",
    corsAllowedOrigins: List<String> = parseCorsOrigins(System.getenv("CORS_ALLOWED_ORIGINS")),
    pointsMaxRows: Int = parsePointsMaxRows(System.getenv("POINTS_MAX_ROWS")),
    access: AccessConfig = AccessConfig.fromEnv(),
) {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { REQUEST_ID.matches(it) }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        callIdMdc("call-id")
    }
    if (corsAllowedOrigins.isNotEmpty()) {
        install(CORS) {
            corsAllowedOrigins.forEach { origin ->
                val uri = runCatching { URI(origin) }.getOrNull()
                val scheme = uri?.scheme
                val host = uri?.host
                if (scheme == null || host == null) {
                    error(
                        "CORS_ALLOWED_ORIGINS entry '$origin' is not a full origin " +
                            "(scheme://host[:port]), e.g. http://localhost:5174"
                    )
                }
                val hostAndPort = if (uri.port == -1) host else "$host:${uri.port}"
                allowHost(hostAndPort, schemes = listOf(scheme))
            }
            // Browser clients send the API key in this header (a non-simple header, so it must be
            // allowed explicitly or the preflight fails).
            allowHeader(HttpHeaders.Authorization)
        }
    }
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        // Catches everything routes don't handle themselves (malformed stored JSON, a missing
        // table, an unparseable stored value, ...) so a bug in the data surfaces as one consistent
        // ErrorDto-shaped 500 instead of Ktor's default bare/inconsistent error response. The
        // cause is logged server-side with the request id, never sent to the client: exception
        // messages can carry file paths, SQL, or stored data.
        exception<Throwable> { call, cause ->
            // Path only: the query string can carry an api_key.
            log.error("Unhandled error on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
        }
    }
    val repo = SeriesRepository(dbPath)
    // Fail fast at startup if dbPath doesn't point at an already-populated database, rather than
    // silently starting up against an empty auto-created SQLite file that only fails once the
    // first request hits a data endpoint — /health never touches the DB, so it would otherwise
    // report healthy the whole time.
    runBlocking { repo.verifySchema() }

    // API keys are checked against the accounts database the separate admin/billing service writes.
    if (access.requireApiKey) {
        val accounts = AccountsStore(access.accountsDbPath)
        runBlocking { accounts.verifySchema() }
        install(Authentication) {
            bearer("api-key") {
                realm = "brindex"
                // Also accept ?api_key=... because some clients (Portfolio Performance's JSON quote
                // feed) can't send headers. The header wins when both are present.
                authHeader { call ->
                    // A malformed header is just "no credentials" (401), not a 500.
                    runCatching { call.request.parseAuthorizationHeader() }.getOrNull()
                        ?: call.request.queryParameters["api_key"]?.let { HttpAuthHeader.Single("Bearer", it) }
                }
                authenticate { credential -> accounts.activeAccountId(credential.token)?.let(::ApiKeyPrincipal) }
            }
        }
        install(RateLimit) {
            register(API_RATE_LIMIT) {
                rateLimiter(limit = access.rateLimitPerMinute, refillPeriod = 1.minutes)
                requestKey { call -> call.principal<ApiKeyPrincipal>()!!.accountId }
            }
        }
    }

    routing {
        // Liveness: the process is up. Never touches the database.
        get("/health") {
            call.respondText("ok")
        }
        // Readiness: the database is reachable and queryable right now.
        get("/ready") {
            val ready = runCatching { repo.ping() }
                .onFailure { log.warn("Readiness check failed: {}", it.toString()) }
                .isSuccess
            if (ready) {
                call.respondText("ok")
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("database unavailable"))
            }
        }
        // Unversioned routes stay as they are for existing consumers; new clients use /v1.
        fun Route.dataRoutes() {
            seriesRoutes(repo, pointsMaxRows)
            route("/v1") {
                seriesRoutes(repo, pointsMaxRows, decimalAsString = true)
            }
        }
        if (access.requireApiKey) {
            authenticate("api-key") {
                rateLimit(API_RATE_LIMIT) {
                    dataRoutes()
                }
            }
        } else {
            dataRoutes()
        }
    }
}

data class ApiKeyPrincipal(val accountId: Long)

/** API key and rate limit settings. Off by default, which keeps the original private-network
 * behavior (no auth at all). */
data class AccessConfig(
    val requireApiKey: Boolean = false,
    val accountsDbPath: String = "accounts.sqlite",
    val rateLimitPerMinute: Int = 60,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv) = AccessConfig(
            requireApiKey = env("REQUIRE_API_KEY")?.takeIf { it.isNotBlank() }?.let { raw ->
                // Strict: a typo must not silently leave the API open.
                raw.trim().toBooleanStrictOrNull()
                    ?: error("REQUIRE_API_KEY env var '$raw' must be 'true' or 'false'")
            } ?: false,
            accountsDbPath = env("ACCOUNTS_DB_PATH")?.takeIf { it.isNotBlank() } ?: "accounts.sqlite",
            rateLimitPerMinute = env("RATE_LIMIT_PER_MINUTE")?.takeIf { it.isNotBlank() }?.let { raw ->
                raw.trim().toIntOrNull()?.takeIf { it > 0 }
                    ?: error("RATE_LIMIT_PER_MINUTE env var '$raw' is not a positive integer")
            } ?: 60,
        )
    }
}
