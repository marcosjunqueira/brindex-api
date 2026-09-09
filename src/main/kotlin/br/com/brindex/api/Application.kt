package br.com.brindex.api

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

fun main() {
    val portEnv = System.getenv("PORT")
    val port = if (portEnv == null) {
        8080
    } else {
        portEnv.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("PORT env var '$portEnv' is not a valid TCP port number (1-65535)")
    }
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module(dbPath: String = System.getenv("BRINDEX_DB_PATH") ?: "brindex.sqlite") {
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        // Catches everything routes don't handle themselves (malformed stored JSON, a missing
        // table, an unparseable stored value, ...) so a bug in the data surfaces as one consistent
        // ErrorDto-shaped 500 instead of Ktor's default bare/inconsistent error response.
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(cause.message ?: "internal error"))
        }
    }
    val repo = SeriesRepository(dbPath)
    // Fail fast at startup if dbPath doesn't point at an already-populated database, rather than
    // silently starting up against an empty auto-created SQLite file that only fails once the
    // first request hits a data endpoint — /health never touches the DB, so it would otherwise
    // report healthy the whole time.
    runBlocking { repo.verifySchema() }
    routing {
        get("/health") {
            call.respondText("ok")
        }
        seriesRoutes(repo)
    }
}
