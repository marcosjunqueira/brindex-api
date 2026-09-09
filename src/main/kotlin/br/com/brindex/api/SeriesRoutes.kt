package br.com.brindex.api

import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * Writes a decimal string as a raw, unquoted JSON number token — never via Double/Float — so the
 * exact digits stored in SQLite (see CLAUDE.md's decimal discipline) round-trip byte-for-byte.
 */
object RawJsonNumberSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("RawJsonNumber", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        require(encoder is JsonEncoder) { "RawJsonNumberSerializer only supports JSON output" }
        encoder.encodeJsonElement(if (value == null) JsonNull else JsonUnquotedLiteral(value))
    }

    override fun deserialize(decoder: Decoder): String? =
        throw UnsupportedOperationException("read-only API: deserialization not supported")
}

private fun parseJsonOrNull(raw: String?): JsonElement =
    raw?.let { Json.parseToJsonElement(it) } ?: JsonNull

@Serializable
data class SeriesDto(
    val code: String,
    val domain: String,
    val name: String,
    val metadata: JsonElement
)

@Serializable
data class PointDto(
    val date: String,
    @Serializable(with = RawJsonNumberSerializer::class) val value: String?,
    val extra_values: JsonElement,
    val source_updated_at: String
)

@Serializable
data class ErrorDto(val error: String)

private fun SeriesRow.toDto() = SeriesDto(
    code = code,
    domain = domain,
    name = name,
    metadata = parseJsonOrNull(metadata)
)

private fun PointRow.toDto() = PointDto(
    date = date,
    value = value,
    extra_values = parseJsonOrNull(extraValues),
    source_updated_at = sourceUpdatedAt
)

fun Route.seriesRoutes(repo: SeriesRepository) {
    get("/series") {
        val domain = call.request.queryParameters["domain"]
        call.respond(repo.listSeries(domain).map { it.toDto() })
    }

    get("/series/{code}/points") {
        val code = call.parameters["code"]!!
        if (!repo.seriesExists(code)) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("series not found"))
            return@get
        }
        val since = call.request.queryParameters["since"]
        val until = call.request.queryParameters["until"]
        call.respond(repo.listPoints(code, since, until).map { it.toDto() })
    }

    get("/series/{code}/points/latest") {
        val code = call.parameters["code"]!!
        if (!repo.seriesExists(code)) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("series not found"))
            return@get
        }
        val point = repo.latestPoint(code)
        if (point == null) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("no points for series"))
            return@get
        }
        call.respond(point.toDto())
    }
}
