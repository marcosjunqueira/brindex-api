package br.com.brindex.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral

private val JSON_NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * Writes a decimal string as a raw, unquoted JSON number token — never via Double/Float — so the
 * exact digits stored in SQLite (see CLAUDE.md's decimal discipline) round-trip byte-for-byte.
 * Validates the stored text actually looks like a JSON number first: `points.value` is an
 * unconstrained SQLite TEXT column, and splicing arbitrary text in unescaped/unvalidated would let
 * a malformed row (e.g. an empty string, or stray JSON-structural characters) corrupt the response
 * body while still returning 200. An invalid value throws instead, which the installed StatusPages
 * handler turns into a clean 500.
 */
object RawJsonNumberSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("RawJsonNumber", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        require(encoder is JsonEncoder) { "RawJsonNumberSerializer only supports JSON output" }
        if (value == null) {
            encoder.encodeJsonElement(JsonNull)
            return
        }
        check(JSON_NUMBER.matches(value)) { "stored value '$value' is not valid JSON-number text" }
        encoder.encodeJsonElement(JsonUnquotedLiteral(value))
    }

    override fun deserialize(decoder: Decoder): String? =
        throw UnsupportedOperationException("read-only API: deserialization not supported")
}

/**
 * Writes a JSON-object/array blob (`series.metadata`, `points.extra_values` — opaque JSON TEXT
 * columns) as a raw, unquoted literal, byte-for-byte as stored. This is deliberately NOT
 * `Json.parseToJsonElement(...)` re-embedded as a `JsonElement` field: kotlinx.serialization's
 * generic `JsonElement` machinery materializes any *unquoted* nested numeric literal as a `Double`
 * on parse, and re-serializes that `Double` (verified: `18094.980` -> `18094.98`, `1E2` -> `100.0`,
 * high-precision decimals truncated to ~16 significant digits) — silently violating the same
 * decimal discipline `RawJsonNumberSerializer` exists to protect for `value`. Parsing here is only
 * to validate the stored text is well-formed JSON (so a malformed blob throws a clear error via
 * StatusPages instead of corrupting the response); the parsed tree itself is discarded and never
 * re-serialized.
 */
object RawJsonBlobSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("RawJsonBlob", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        require(encoder is JsonEncoder) { "RawJsonBlobSerializer only supports JSON output" }
        if (value == null) {
            encoder.encodeJsonElement(JsonNull)
            return
        }
        Json.parseToJsonElement(value)
        encoder.encodeJsonElement(JsonUnquotedLiteral(value))
    }

    override fun deserialize(decoder: Decoder): String? =
        throw UnsupportedOperationException("read-only API: deserialization not supported")
}

@Serializable
data class SeriesDto(
    val code: String,
    val domain: String,
    val name: String,
    @Serializable(with = RawJsonBlobSerializer::class) val metadata: String?
)

@Serializable
data class PointDto(
    val date: String,
    @Serializable(with = RawJsonNumberSerializer::class) val value: String?,
    @Serializable(with = RawJsonBlobSerializer::class) val extra_values: String?,
    val source_updated_at: String
)

@Serializable
data class ErrorDto(val error: String)

private fun SeriesRow.toDto() = SeriesDto(
    code = code,
    domain = domain,
    name = name,
    metadata = metadata
)

private fun PointRow.toDto() = PointDto(
    date = date,
    value = value,
    extra_values = extraValues,
    source_updated_at = sourceUpdatedAt
)

private suspend fun ApplicationCall.respondSeriesNotFound() {
    respond(HttpStatusCode.NotFound, ErrorDto("series not found"))
}

private fun List<String>.toSeriesCode(): String = joinToString(":")

fun Route.seriesRoutes(repo: SeriesRepository) {
    get("/series") {
        val domain = call.request.queryParameters["domain"]
        call.respond(repo.listSeries(domain).map { it.toDto() })
    }

    /**
     * A series `code` is canonically `<DOMAIN>:<IDENTIFIER...>` (see SPEC_READ_API.md §2) — the
     * `:`-joined string stored in `series.code`/`points.series_code`. Exposing that string as a
     * single path segment would force clients to percent-encode every `:` (`%3A`), illegible in a
     * URL. Instead each `:`-separated part is its own path segment (`TD/LFT/2026-03-01/BUY`,
     * `PTAX/USD/BUY`, `CDI/SGS/4391`) and `toSeriesCode()` joins them back with `:` to recover the
     * exact stored code before it ever reaches the repository/database layer.
     *
     * The number of identifier parts varies by domain, so this can't be a fixed-arity route per
     * domain. Ktor's tailcard (`{param...}`) captures a variable number of trailing segments, but
     * only when it is the very last element of the route pattern — `segmentIncrement` for
     * `PathSegmentTailcardRouteSelector` always consumes every remaining segment, so it can't be
     * followed by literal segments like `/points` in the same route. Instead this single catch-all
     * route captures the whole tail under `/series/`, and the handler strips the trailing `points`
     * or `points/latest` marker segments itself to recover the code segments.
     */
    get("/series/{tail...}") {
        val segments = call.parameters.getAll("tail").orEmpty()
        val isLatest = segments.size >= 2 && segments.last() == "latest" && segments[segments.size - 2] == "points"
        val isPoints = !isLatest && segments.isNotEmpty() && segments.last() == "points"
        val codeSegments = when {
            isLatest -> segments.dropLast(2)
            isPoints -> segments.dropLast(1)
            else -> null
        }
        if (codeSegments == null || codeSegments.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("not found"))
            return@get
        }
        val code = codeSegments.toSeriesCode()

        if (isLatest) {
            val point = repo.latestPoint(code)
            if (point == null) {
                if (!repo.seriesExists(code)) {
                    call.respondSeriesNotFound()
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("no points for series"))
                }
                return@get
            }
            call.respond(point.toDto())
            return@get
        }

        val since = call.request.queryParameters["since"]
        val until = call.request.queryParameters["until"]
        if (since != null && !ISO_DATE.matches(since)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("since must be YYYY-MM-DD"))
            return@get
        }
        if (until != null && !ISO_DATE.matches(until)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("until must be YYYY-MM-DD"))
            return@get
        }
        // Query first, check existence only to disambiguate an empty result (unknown code vs a
        // known code with no points in range) — avoids a second round trip on the common path.
        val points = repo.listPoints(code, since, until)
        if (points.isEmpty() && !repo.seriesExists(code)) {
            call.respondSeriesNotFound()
            return@get
        }
        call.respond(points.map { it.toDto() })
    }
}
