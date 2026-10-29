package org.a1tsign.vds.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.a1tsign.vds.AppComponents
import org.a1tsign.vds.config.HttpConfig
import org.a1tsign.vds.support.TEST_CLOCK
import org.a1tsign.vds.support.capturingLogs
import org.a1tsign.vds.support.testApi
import org.slf4j.event.Level
import java.io.IOException
import java.math.BigDecimal
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.reflect.typeOf
import io.ktor.server.plugins.PayloadTooLargeException as KtorPayloadTooLargeException

// --- throwaway DTOs/routes: the contract is exercised through real HTTP, never by calling the
// serializers or validators in isolation, so a break at the ContentNegotiation/StatusPages
// boundary is caught here rather than in the first business endpoint. ---

@Serializable
private data class EchoRequest(
    val slug: String,
    val amount: String,
    val at: @Contextual Instant,
)

@Serializable
private data class EchoResponse(
    val slug: String,
    val amount: @Contextual BigDecimal,
    val at: @Contextual Instant,
    // Always null here: proves `explicitNulls = false` drops it from the wire even though
    // `encodeDefaults = true` is on.
    val note: String? = null,
)

@Serializable
private data class RateRequest(val rate: @Contextual BigDecimal)

@Serializable
private data class RateResponse(val rate: @Contextual BigDecimal)

@Serializable
private data class SizeResponse(val size: Int)

private const val SECRET_BODY_MARKER = "payload-secret-marker"
private const val SECRET_TOKEN = "token-secret-marker"

// Deliberately shares no substring with any route path below: the first version of the leak test
// used "boom", which the path /api/test/fail/boom contains, so the assertion failed on the
// envelope's own `path` field instead of on a real leak.
private const val SECRET_EXCEPTION_MARKER = "kaputt-secret-marker"

private fun Route.wireFormatTestRoutes(rawInvocations: AtomicInteger = AtomicInteger()) {
    post("/api/test/echo") {
        val body = call.receive<EchoRequest>()
        val validation = Validation()
        val slug = validation.validateSlug("slug", body.slug)
        val amount = validation.validateAmount("amount", body.amount)
        validation.throwIfInvalid()
        call.respond(EchoResponse(slug = slug!!, amount = amount!!, at = body.at))
    }

    // A BigDecimal-typed *request* field: exercises BigDecimalSerializer.deserialize (string-only
    // token, plain notation) rather than the String + validateAmount request pattern above.
    post("/api/test/rate") {
        val body = call.receive<RateRequest>()
        call.respond(RateResponse(rate = body.rate))
    }

    post("/api/test/raw") {
        // Counted at entry: a 413 with this counter unchanged is the observable proof that the
        // body was rejected before anything read it.
        rawInvocations.incrementAndGet()
        val text = call.receiveText()
        call.respond(SizeResponse(text.length))
    }

    // Echoes what each validator *returned*, not just a status: a validator's contract is both
    // "record the failure" and "hand back the value it validated", and later tasks build their
    // DTOs out of those return values.
    post("/api/test/all-fields") {
        val body = call.receive<AllFieldsRequest>()
        val validation = Validation()
        val name = validation.requireNonBlank("name", body.name)
            ?.let { validation.maxLength("name", it, 5) }
        val slug = validation.validateSlug("slug", body.slug)
        val countryCode = validation.validateCountryCode("countryCode", body.countryCode)
        val currency = validation.validateCurrency("currency", body.currency)
        val websiteUrl = validation.validateUrl("websiteUrl", body.websiteUrl)
        val amount = validation.validateAmount("amount", body.amount)
        validation.throwIfInvalid()
        call.respond(
            AllFieldsResponse(
                name = name!!,
                slug = slug!!,
                countryCode = countryCode!!,
                currency = currency!!,
                websiteUrl = websiteUrl!!,
                amount = amount!!,
            ),
        )
    }

    get("/api/test/page") {
        val pageRequest = call.pageRequest(setOf("name", "id"))
        call.respond(
            pageResponse(
                total = 42,
                page = pageRequest.page,
                size = pageRequest.size,
                items = pageRequest.sort.map { "${it.field} ${if (it.descending) "DESC" else "ASC"}" },
            ),
        )
    }

    get("/api/test/fail/{kind}") {
        when (call.parameters["kind"]) {
            "validation" -> throw ValidationException(listOf(ApiErrorDetail("slug", "must match ^[a-z0-9-]+$")))
            "validation-empty" -> throw ValidationException(emptyList())
            "unauthorized" -> throw UnauthorizedException()
            "forbidden" -> throw ForbiddenException()
            "not-found" -> throw NotFoundException("Provider 7 not found")
            "conflict" -> throw ConflictException("slug already taken")
            "payload" -> throw PayloadTooLargeException("Request body exceeds 262144 bytes")
            "ktor-payload" -> throw KtorPayloadTooLargeException(MAX_REQUEST_BODY_BYTES)
            // The member of the abstract ContentTransformationException family that a wrong or
            // missing Content-Type actually produces; the family's own branch answers 415 for it,
            // while its PayloadTooLargeException sibling keeps the more specific 413 branch.
            // Thrown from a route here so the type -> status mapping is pinned independently of
            // which request shape happens to produce it.
            "content-transformation" -> throw CannotTransformContentToTypeException(typeOf<RateRequest>())
            "too-many" -> throw TooManyRequestsException(retryAfterSeconds = 30)
            "unavailable" -> throw ServiceUnavailableException()
            "internal" -> throw RuntimeException("$SECRET_EXCEPTION_MARKER jdbc:postgresql://db:5432/vds")
            else -> call.respond(SizeResponse(0))
        }
    }

    get("/api/test/ok") {
        call.respond(SizeResponse(1))
    }
}

@Serializable
private data class AllFieldsRequest(
    val name: String,
    val slug: String,
    val countryCode: String,
    val currency: String,
    val websiteUrl: String,
    val amount: String,
)

@Serializable
private data class AllFieldsResponse(
    val name: String,
    val slug: String,
    val countryCode: String,
    val currency: String,
    val websiteUrl: String,
    val amount: @Contextual BigDecimal,
)

/**
 * A request body the client sends with **no** `Content-Type` header at all (`OutgoingContent`'s
 * `contentType` defaults to null and nothing else sets the header), which is what a `curl` call
 * that forgot `-H 'Content-Type: application/json'` looks like on the wire.
 */
private class UntypedBody(private val text: String) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType? = null

    override fun bytes(): ByteArray = text.toByteArray()
}

private fun assertJsonEquals(expected: String, actual: String) =
    assertEquals(ApiJson.parseToJsonElement(expected), ApiJson.parseToJsonElement(actual))

/** The correlation ref carried by a `500` envelope, asserting the message's exact shape on the way. */
private fun refOf(body: String): String {
    val message = ApiJson.parseToJsonElement(body).jsonObject.getValue("message").toString().trim('"')
    return Regex("""^Internal error \(ref: ([0-9a-f]{8})\)$""").find(message)?.groupValues?.get(1)
        ?: throw AssertionError("message must be 'Internal error (ref: xxxxxxxx)', was '$message'")
}

private const val RAW_CHUNK_BYTES = 8 * 1024

/** One chunk past the limit, so the whole body is drained and the writes below cannot block. */
private val OVERSIZED_CHUNK_COUNT = (MAX_REQUEST_BODY_BYTES / RAW_CHUNK_BYTES).toInt() + 1

/**
 * Writes a raw HTTP/1.1 `POST /api/test/raw` carrying **both** an (understated) `Content-Length`
 * and `Transfer-Encoding: chunked`, streaming [chunks] chunks of [RAW_CHUNK_BYTES] bytes, and
 * returns the whole response text. `Connection: close` makes the server close the socket after
 * answering, so reading to EOF terminates.
 */
private fun postBothFramingHeaders(port: Int, chunks: Int): String =
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val out = socket.getOutputStream()
        val head = "POST /api/test/raw HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 5\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        val chunkFrame = (
            "${RAW_CHUNK_BYTES.toString(16)}\r\n" + "a".repeat(RAW_CHUNK_BYTES) + "\r\n"
            ).toByteArray()
        try {
            out.write(head.toByteArray())
            repeat(chunks) { out.write(chunkFrame) }
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        } catch (e: IOException) {
            // The server rejected the body and closed the connection while we were still writing.
            // That is a legitimate rejection, not a test failure -- read whatever it answered.
        }
        socket.getInputStream().readBytes().decodeToString()
    }

class WireFormatTest {
    // ---------- money on the wire ----------

    @Test
    fun `money round-trips as a plain decimal string scaled to four digits`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/echo") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"slug":"hetzner","amount":"10","at":"2026-10-28T12:00:00+02:00"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """{"slug":"hetzner","amount":"10.0000","at":"2026-10-28T10:00:00Z"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `a JSON number for a money field is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/echo") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"slug":"hetzner","amount":10,"at":"2026-10-28T12:00:00Z"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Malformed request body","path":"/api/test/echo"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `a JSON number for a BigDecimal-typed field is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/rate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"rate":0.92}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a BigDecimal-typed field keeps every fractional digit it was given`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/rate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"rate":"0.920000000000"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals("""{"rate":"0.920000000000"}""", response.bodyAsText())
    }

    @Test
    fun `a BigDecimal-typed field rejects scientific notation`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/rate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"rate":"9.2e-1"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `BigDecimal is never encoded in scientific notation`() {
        // 9.2E-1 and 1E+3 are exactly what BigDecimal.toString() would emit; toPlainString() must
        // be used instead. Asserted on the real response below too, but pinned here on the value
        // that makes toString()/toPlainString() differ.
        assertEquals(
            """{"rate":"0.92"}""",
            ApiJson.encodeToString(RateResponse(BigDecimal("9.2E-1"))),
        )
        assertEquals(
            """{"rate":"1000"}""",
            ApiJson.encodeToString(RateResponse(BigDecimal("1E+3"))),
        )
    }

    @Test
    fun `every rejected amount form is a 400 naming the field`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val cases = mapOf(
            "1e3" to "must be a decimal number in plain notation",
            "-1" to "must not be negative",
            "1.23456" to "must have at most 4 fractional digits",
            "123456789.0" to "must have at most 8 integer digits",
            "abc" to "must be a decimal number in plain notation",
        )
        for ((raw, expectedMessage) in cases) {
            val response = client.post("/api/test/echo") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"slug":"hetzner","amount":"$raw","at":"2026-10-28T12:00:00Z"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status, "amount=$raw")
            assertJsonEquals(
                """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                    "message":"Request validation failed","path":"/api/test/echo",
                    "details":[{"field":"amount","message":"$expectedMessage"}]}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `an amount at both limits is accepted`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/echo") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"slug":"a","amount":"12345678.1234","at":"2026-10-28T12:00:00Z"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """{"slug":"a","amount":"12345678.1234","at":"2026-10-28T12:00:00Z"}""",
            response.bodyAsText(),
        )
    }

    // ---------- time on the wire ----------

    @Test
    fun `an instant without an explicit offset is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/echo") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"slug":"a","amount":"1","at":"2026-10-28T12:00:00"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Malformed request body","path":"/api/test/echo"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `an instant with sub-second precision is emitted truncated to whole seconds in UTC`() {
        assertEquals(
            """{"slug":"a","amount":"1.0000","at":"2026-10-28T10:00:00Z"}""",
            ApiJson.encodeToString(
                EchoResponse(
                    slug = "a",
                    amount = BigDecimal("1.0000"),
                    at = Instant.parse("2026-10-28T10:00:00.987654Z"),
                ),
            ),
        )
    }

    // ---------- the shared Json instance ----------

    @Test
    fun `an unknown property in a request body is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/echo") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"slug":"a","amount":"1","at":"2026-10-28T12:00:00Z","typo":1}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Malformed request body","path":"/api/test/echo"}""",
            response.bodyAsText(),
        )
    }

    // ---------- validators ----------

    @Test
    fun `every field failure of one body is reported at once`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/all-fields") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """{"name":"   ","slug":"Not_A_Slug","countryCode":"de","currency":"eur",
                    "websiteUrl":"ftp://example.com","amount":"-1"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Request validation failed","path":"/api/test/all-fields",
                "details":[
                  {"field":"name","message":"must not be blank"},
                  {"field":"slug","message":"must match ^[a-z0-9-]+$ and be at most 64 characters"},
                  {"field":"countryCode","message":"must be an uppercase ISO 3166-1 alpha-2 country code"},
                  {"field":"currency","message":"must be an uppercase ISO 4217 currency code"},
                  {"field":"websiteUrl","message":"must be an absolute http or https URL of at most 2048 characters"},
                  {"field":"amount","message":"must not be negative"}
                ]}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `valid values of every field pass and are returned by their validator`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/all-fields") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """{"name":"ok","slug":"hetzner-de","countryCode":"DE","currency":"EUR",
                    "websiteUrl":"https://hetzner.com/vds","amount":"4.5"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """{"name":"ok","slug":"hetzner-de","countryCode":"DE","currency":"EUR",
                "websiteUrl":"https://hetzner.com/vds","amount":"4.5000"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `an amount of zero is accepted`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        // The schema's CHECK is `amount >= 0`, so free is a legal price and must not be rejected
        // along with the negative amounts.
        val response = client.post("/api/test/echo") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"slug":"a","amount":"0","at":"2026-10-28T12:00:00Z"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """{"slug":"a","amount":"0.0000","at":"2026-10-28T12:00:00Z"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `length limits are boundary-exact`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        suspend fun post(slug: String, url: String, name: String): HttpStatusCode =
            client.post("/api/test/all-fields") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"name":"$name","slug":"$slug","countryCode":"DE","currency":"EUR",
                        "websiteUrl":"$url","amount":"1"}""",
                )
            }.status

        val url63 = "https://e.test/" + "a".repeat(2048 - "https://e.test/".length)
        assertEquals(2048, url63.length)
        assertEquals(HttpStatusCode.OK, post("a".repeat(64), url63, "12345"))
        assertEquals(HttpStatusCode.BadRequest, post("a".repeat(65), url63, "12345"))
        assertEquals(HttpStatusCode.BadRequest, post("a".repeat(64), url63 + "a", "12345"))
        assertEquals(HttpStatusCode.BadRequest, post("a".repeat(64), url63, "123456"))
    }

    @Test
    fun `a relative or non-http url is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val urls = listOf(
            "/relative",
            "example.com",
            "ftp://example.com",
            "javascript:alert(1)",
            // Absolute and http-schemed, but with no host at all -- parseable, not usable.
            "http://",
        )
        for (url in urls) {
            val response = client.post("/api/test/all-fields") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"name":"ok","slug":"a","countryCode":"DE","currency":"EUR",
                        "websiteUrl":"$url","amount":"1"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "url=$url")
        }
    }

    // ---------- pagination ----------

    @Test
    fun `pagination defaults and the id tie-breaker`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/page")

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """{"content":["id ASC"],"page":0,"size":20,"totalElements":42,"totalPages":3}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `an explicit sort gets id ASC appended`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/page?sort=name,desc")

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """{"content":["name DESC","id ASC"],"page":0,"size":20,"totalElements":42,"totalPages":3}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `a sort without a direction is ascending`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/page?sort=name")

        assertJsonEquals(
            """{"content":["name ASC","id ASC"],"page":0,"size":20,"totalElements":42,"totalPages":3}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `sorting by the tie-breaker field itself is not duplicated`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/page?sort=id,desc")

        assertJsonEquals(
            """{"content":["id DESC"],"page":0,"size":20,"totalElements":42,"totalPages":3}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `explicit page and size at their lower bounds are accepted and echoed`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        // page=0 and size=1 are the legal lower bounds; and `page` must be echoed as given, not
        // collapsed to its default.
        assertJsonEquals(
            """{"content":["id ASC"],"page":0,"size":1,"totalElements":42,"totalPages":42}""",
            client.get("/api/test/page?page=0&size=1").bodyAsText(),
        )
        assertJsonEquals(
            """{"content":["id ASC"],"page":2,"size":20,"totalElements":42,"totalPages":3}""",
            client.get("/api/test/page?page=2").bodyAsText(),
        )
    }

    @Test
    fun `a sort with more than two parts is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/page?sort=name,desc,extra")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Request validation failed","path":"/api/test/page",
                "details":[{"field":"sort","message":"must be 'field' or 'field,asc|desc'"}]}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `the maximum page size is accepted and anything larger is rejected`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val atLimit = client.get("/api/test/page?size=100")
        assertEquals(HttpStatusCode.OK, atLimit.status)
        assertJsonEquals(
            """{"content":["id ASC"],"page":0,"size":100,"totalElements":42,"totalPages":1}""",
            atLimit.bodyAsText(),
        )

        val overLimit = client.get("/api/test/page?size=101")
        assertEquals(HttpStatusCode.BadRequest, overLimit.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Request validation failed","path":"/api/test/page",
                "details":[{"field":"size","message":"must be between 1 and 100"}]}""",
            overLimit.bodyAsText(),
        )
    }

    @Test
    fun `invalid pagination parameters are rejected not clamped`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val cases = mapOf(
            "page=-1" to """[{"field":"page","message":"must be zero or greater"}]""",
            "page=x" to """[{"field":"page","message":"must be zero or greater"}]""",
            "size=0" to """[{"field":"size","message":"must be between 1 and 100"}]""",
            "size=x" to """[{"field":"size","message":"must be between 1 and 100"}]""",
            "sort=unknownField" to
                """[{"field":"sort","message":"unknown sort field 'unknownField', allowed: id, name"}]""",
            "sort=name,sideways" to
                """[{"field":"sort","message":"direction must be 'asc' or 'desc'"}]""",
            "sort=" to """[{"field":"sort","message":"unknown sort field '', allowed: id, name"}]""",
        )
        for ((query, expectedDetails) in cases) {
            val response = client.get("/api/test/page?$query")

            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertJsonEquals(
                """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                    "message":"Request validation failed","path":"/api/test/page",
                    "details":$expectedDetails}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `every bad pagination parameter of one request is reported at once`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/page?page=-1&size=101&sort=nope")

        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Request validation failed","path":"/api/test/page",
                "details":[
                  {"field":"page","message":"must be zero or greater"},
                  {"field":"size","message":"must be between 1 and 100"},
                  {"field":"sort","message":"unknown sort field 'nope', allowed: id, name"}
                ]}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `totalPages rounds up and an empty result has no pages`() {
        assertEquals(3, pageResponse(42, 0, 20, listOf("a")).totalPages)
        assertEquals(1, pageResponse(20, 0, 20, listOf("a")).totalPages)
        assertEquals(2, pageResponse(21, 0, 20, listOf("a")).totalPages)
        assertEquals(0, pageResponse(0, 0, 20, emptyList<String>()).totalPages)
        // pageRequest never yields size 0, but pageResponse is a public helper: a caller that
        // passes one must get 0 pages, not an ArithmeticException from the division.
        assertEquals(0, pageResponse(42, 0, 0, emptyList<String>()).totalPages)
    }

    // ---------- the error envelope ----------

    @Test
    fun `every application exception maps to its status with the same envelope`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val cases = listOf(
            Triple("validation", 400, "Bad Request") to
                ""","details":[{"field":"slug","message":"must match ^[a-z0-9-]+$"}]""",
            Triple("unauthorized", 401, "Unauthorized") to "",
            Triple("forbidden", 403, "Forbidden") to "",
            Triple("not-found", 404, "Not Found") to "",
            Triple("conflict", 409, "Conflict") to "",
            Triple("payload", 413, "Payload Too Large") to "",
            Triple("ktor-payload", 413, "Payload Too Large") to "",
            Triple("content-transformation", 415, "Unsupported Media Type") to "",
            Triple("too-many", 429, "Too Many Requests") to "",
            Triple("unavailable", 503, "Service Unavailable") to "",
        )
        val expectedMessages = mapOf(
            "validation" to "Request validation failed",
            "unauthorized" to "Authentication required",
            "forbidden" to "Access denied",
            "not-found" to "Provider 7 not found",
            "conflict" to "slug already taken",
            "payload" to "Request body exceeds 262144 bytes",
            "ktor-payload" to "Request body exceeds the 262144 byte limit",
            "content-transformation" to "Content-Type must be application/json",
            "too-many" to "Too many requests",
            "unavailable" to "Service temporarily unavailable",
        )
        for ((case, detailsFragment) in cases) {
            val (kind, status, reason) = case
            val response = client.get("/api/test/fail/$kind")

            assertEquals(HttpStatusCode.fromValue(status), response.status, kind)
            assertJsonEquals(
                """{"timestamp":"2026-10-28T12:00:00Z","status":$status,"error":"$reason",
                    "message":"${expectedMessages.getValue(kind)}",
                    "path":"/api/test/fail/$kind"$detailsFragment}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `details is omitted unless the failure is a validation failure`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val notFound = ApiJson.parseToJsonElement(client.get("/api/test/fail/not-found").bodyAsText())
        assertNull(notFound.jsonObject["details"], "details must be absent for a 404")

        val empty = client.get("/api/test/fail/validation-empty")
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":400,"error":"Bad Request",
                "message":"Request validation failed","path":"/api/test/fail/validation-empty",
                "details":[]}""",
            empty.bodyAsText(),
        )
    }

    @Test
    fun `429 carries Retry-After`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/fail/too-many")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("30", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `no other status carries Retry-After`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        assertNull(client.get("/api/test/fail/unavailable").headers[HttpHeaders.RetryAfter])
        assertNull(client.get("/api/test/fail/not-found").headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `the timestamp comes from the injected clock`() = testApi(
        clock = Clock.fixed(Instant.parse("2001-02-03T04:05:06Z"), ZoneOffset.UTC),
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.get("/api/test/fail/not-found")

        assertJsonEquals(
            """{"timestamp":"2001-02-03T04:05:06Z","status":404,"error":"Not Found",
                "message":"Provider 7 not found","path":"/api/test/fail/not-found"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `a missing or non-JSON Content-Type is a 415 envelope, never a 500`() {
        // Every member of Ktor's ContentTransformationException family extends java.io.IOException
        // -- *not* BadRequestException (verified with javap on ktor-server-core-jvm-3.6.0). Without
        // its own StatusPages branch a forgotten `-H 'Content-Type: application/json'` fell into
        // the catch-all, so a pure client mistake answered 500 with a correlation ref and an ERROR
        // log line, inflating the server-error rate every later dashboard is built on. Measured,
        // then fixed. All three forms below raise CannotTransformContentToTypeException (no
        // converter claims the content type), which Ktor's own DefaultEnginePipeline answers 415
        // for -- the catch-all had been shadowing that default.
        val logs = capturingLogs {
            testApi(routes = { wireFormatTestRoutes() }) {
                val expected = """{"timestamp":"2026-10-28T12:00:00Z","status":415,
                    "error":"Unsupported Media Type",
                    "message":"Content-Type must be application/json","path":"/api/test/rate"}"""

                val missing = client.post("/api/test/rate") { setBody(UntypedBody("""{"rate":"0.92"}""")) }
                assertEquals(HttpStatusCode.UnsupportedMediaType, missing.status, "no Content-Type header")
                assertJsonEquals(expected, missing.bodyAsText())

                for (type in listOf(ContentType.Text.Plain, ContentType.Application.Xml)) {
                    val response = client.post("/api/test/rate") {
                        header(HttpHeaders.ContentType, type.toString())
                        setBody("""{"rate":"0.92"}""")
                    }

                    assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, type.toString())
                    assertJsonEquals(expected, response.bodyAsText())
                }
            }
        }

        assertEquals(
            emptyList(),
            logs.messagesAt(Level.ERROR),
            "a client Content-Type mistake must not be logged at ERROR",
        )
    }

    @Test
    fun `an unexpected exception is a generic 500 that leaks nothing, logged at ERROR with a ref`() {
        lateinit var body: String
        val logs = capturingLogs {
            testApi(routes = { wireFormatTestRoutes() }) {
                val response = client.get("/api/test/fail/internal")
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                body = response.bodyAsText()
            }
        }

        for (leak in listOf(SECRET_EXCEPTION_MARKER, "jdbc:postgresql", "RuntimeException", "java.lang", "at org.a1tsign")) {
            assertFalse(body.contains(leak), "500 body must not contain '$leak': $body")
        }

        val envelope = ApiJson.parseToJsonElement(body).jsonObject
        assertEquals("500", envelope.getValue("status").toString())
        assertEquals("\"Internal Server Error\"", envelope.getValue("error").toString())
        assertEquals("\"/api/test/fail/internal\"", envelope.getValue("path").toString())
        assertNull(envelope["details"])

        val ref = refOf(body)

        val errors = logs.messagesAt(Level.ERROR)
        assertTrue(errors.any { it.contains(ref) }, "an ERROR log line must carry ref $ref: $errors")
        assertTrue(
            logs.throwablesAt(Level.ERROR).any { it.contains(SECRET_EXCEPTION_MARKER) },
            "the real exception must reach the server log: ${logs.throwablesAt(Level.ERROR)}",
        )
    }

    @Test
    fun `the correlation ref differs per failure`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        // The refs themselves, not the whole bodies: comparing bodies would still pass if the ref
        // became a constant and some other field started varying instead.
        val first = refOf(client.get("/api/test/fail/internal").bodyAsText())
        val second = refOf(client.get("/api/test/fail/internal").bodyAsText())

        assertNotEquals(first, second, "each 500 must get its own correlation ref")
    }

    // ---------- request body size limit ----------

    @Test
    fun `a body at the limit is handled and one byte more is a 413`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val atLimit = client.post("/api/test/raw") { setBody("a".repeat(MAX_REQUEST_BODY_BYTES.toInt())) }
        assertEquals(HttpStatusCode.OK, atLimit.status)
        assertJsonEquals("""{"size":${MAX_REQUEST_BODY_BYTES}}""", atLimit.bodyAsText())

        val overLimit = client.post("/api/test/raw") { setBody("a".repeat(MAX_REQUEST_BODY_BYTES.toInt() + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, overLimit.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":413,"error":"Payload Too Large",
                "message":"Request body exceeds the $MAX_REQUEST_BODY_BYTES byte limit",
                "path":"/api/test/raw"}""",
            overLimit.bodyAsText(),
        )
    }

    @Test
    fun `an oversized body never reaches the handler at all`() {
        // "Reject before the body is read" is only observable as "the handler never ran": the
        // Content-Length check sits in onCall, ahead of routing, so nothing has consumed a byte.
        // An implementation that instead read the body and measured it afterwards -- the bug a
        // 192 MB heap cannot afford -- would run the handler and fail this.
        val invocations = AtomicInteger()
        testApi(routes = { wireFormatTestRoutes(invocations) }) {
            val under = client.post("/api/test/raw") { setBody("a".repeat(16)) }
            assertEquals(HttpStatusCode.OK, under.status)
            assertEquals(1, invocations.get())

            val over = client.post("/api/test/raw") { setBody("a".repeat(MAX_REQUEST_BODY_BYTES.toInt() + 1)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
            assertEquals(1, invocations.get(), "the handler must not run for an oversized body")
        }
    }

    @Test
    fun `a chunked body past the limit is aborted while streaming`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/raw") {
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    // No Content-Length: the limit can only be enforced by counting bytes as they
                    // are read.
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val chunk = ByteArray(8 * 1024) { 'a'.code.toByte() }
                        repeat((MAX_REQUEST_BODY_BYTES / chunk.size).toInt() + 1) {
                            channel.writeFully(chunk, 0, chunk.size)
                        }
                        channel.flush()
                    }
                },
            )
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":413,"error":"Payload Too Large",
                "message":"Request body exceeds the $MAX_REQUEST_BODY_BYTES byte limit",
                "path":"/api/test/raw"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `a chunked body at the limit is handled normally`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        val response = client.post("/api/test/raw") {
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val chunk = ByteArray(8 * 1024) { 'a'.code.toByte() }
                        repeat((MAX_REQUEST_BODY_BYTES / chunk.size).toInt()) {
                            channel.writeFully(chunk, 0, chunk.size)
                        }
                        channel.flush()
                    }
                },
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals("""{"size":${MAX_REQUEST_BODY_BYTES}}""", response.bodyAsText())
    }

    @Test
    fun `a body declaring both Content-Length and chunked framing cannot bypass the limit`() {
        // Why a raw socket instead of the test client: CIO decides the body framing from
        // Transfer-Encoding *before* it looks at Content-Length
        // (io.ktor.http.cio.parseHttpBody tests isTransferEncodingChunked first), and the server
        // pipeline rejects only a *duplicate* Content-Length header, never a Transfer-Encoding +
        // Content-Length conflict. So a request carrying both is decoded as an unbounded chunked
        // stream while request.contentLength() returns the declared lie -- 5 here. An
        // implementation that skips byte counting whenever Content-Length is present therefore
        // lets an unbounded body through, which on a 192 MB heap is a remote OOM. The Ktor test
        // client derives Content-Length from the body and cannot emit both headers, so this is
        // the only way to tell the two implementations apart. It covers the understated
        // Content-Length case at the same time: 5 declared, 270336 actually streamed.
        val bytesSeenByHandler = AtomicInteger(0)
        val server = embeddedServer(CIO, port = 0) {
            installApiPlugins(HttpConfig(port = 0, corsAllowedOrigins = emptyList()), TEST_CLOCK)
            routing {
                post("/api/test/raw") {
                    val text = call.receiveText()
                    bytesSeenByHandler.set(text.length)
                    call.respond(SizeResponse(text.length))
                }
            }
        }
        val response = try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            postBothFramingHeaders(port, chunks = OVERSIZED_CHUNK_COUNT)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }

        assertEquals(
            0,
            bytesSeenByHandler.get(),
            "the handler must never receive an unbounded body: it read ${bytesSeenByHandler.get()} bytes",
        )
        val statusLine = response.substringBefore("\r\n")
        assertEquals("HTTP/1.1 413 Payload Too Large", statusLine, "full response was: $response")
        assertJsonEquals(
            """{"timestamp":"2026-10-28T12:00:00Z","status":413,"error":"Payload Too Large",
                "message":"Request body exceeds the $MAX_REQUEST_BODY_BYTES byte limit",
                "path":"/api/test/raw"}""",
            response.substringAfter("\r\n\r\n"),
        )
    }

    // ---------- CORS ----------

    @Test
    fun `with no configured origin no cross-origin access is allowed`() = testApi(
        corsAllowedOrigins = emptyList(),
        routes = { wireFormatTestRoutes() },
    ) {
        val preflight = client.options("/api/test/ok") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }
        assertEquals(HttpStatusCode.Forbidden, preflight.status)
        assertNull(preflight.headers[HttpHeaders.AccessControlAllowOrigin])

        val simple = client.get("/api/test/ok") { header(HttpHeaders.Origin, "https://app.example.com") }
        assertNull(simple.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `a configured origin is allowed and any other is not`() = testApi(
        corsAllowedOrigins = listOf("https://app.example.com"),
        routes = { wireFormatTestRoutes() },
    ) {
        val allowed = client.options("/api/test/ok") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }
        assertEquals("https://app.example.com", allowed.headers[HttpHeaders.AccessControlAllowOrigin])

        val other = client.options("/api/test/ok") {
            header(HttpHeaders.Origin, "https://evil.example.com")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }
        assertEquals(HttpStatusCode.Forbidden, other.status)
        assertNull(other.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `an allowed origin may preflight the write methods and the Authorization header`() = testApi(
        corsAllowedOrigins = listOf("https://app.example.com"),
        routes = { wireFormatTestRoutes() },
    ) {
        // Ktor's CORS defaults cover only GET/POST/HEAD and no custom header, so without the
        // explicit allowMethod/allowHeader calls the write endpoints and every authenticated call
        // would be unusable from the configured origin.
        for (method in listOf(HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)) {
            val response = client.options("/api/test/ok") {
                header(HttpHeaders.Origin, "https://app.example.com")
                header(HttpHeaders.AccessControlRequestMethod, method.value)
                header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.Authorization)
            }
            assertEquals(HttpStatusCode.OK, response.status, method.value)
            assertEquals("https://app.example.com", response.headers[HttpHeaders.AccessControlAllowOrigin], method.value)
        }
    }

    @Test
    fun `an allowed origin may send a cross-origin JSON request`() = testApi(
        corsAllowedOrigins = listOf("https://app.example.com"),
        routes = { wireFormatTestRoutes() },
    ) {
        // allowHeader(HttpHeaders.ContentType) is not an allowlist entry: CORSConfig.allowHeader
        // special-cases that one name and flips allowNonSimpleContentTypes (false by default) --
        // verified in the 3.6.0 bytecode. That flag is what permits a non-simple Content-Type such
        // as application/json cross-origin, so without the call both halves below answer 403 and
        // the whole JSON API is unreachable from the configured origin. Measured, not assumed.
        val preflight = client.options("/api/test/rate") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.ContentType)
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals("https://app.example.com", preflight.headers[HttpHeaders.AccessControlAllowOrigin])

        val posted = client.post("/api/test/rate") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"rate":"0.92"}""")
        }
        assertEquals(HttpStatusCode.OK, posted.status)
        assertEquals("https://app.example.com", posted.headers[HttpHeaders.AccessControlAllowOrigin])
        assertJsonEquals("""{"rate":"0.92"}""", posted.bodyAsText())
    }

    @Test
    fun `a request without an Origin header is unaffected by CORS`() = testApi(
        routes = { wireFormatTestRoutes() },
    ) {
        assertEquals(HttpStatusCode.OK, client.get("/api/test/ok").status)
    }

    // ---------- call logging ----------

    @Test
    fun `call logging records the call at INFO without the Authorization header or the body`() {
        val logs = capturingLogs {
            testApi(routes = { wireFormatTestRoutes() }) {
                client.post("/api/test/raw") {
                    header(HttpHeaders.Authorization, "Bearer $SECRET_TOKEN")
                    setBody(SECRET_BODY_MARKER)
                }
            }
        }

        // The exact line, not a substring match on its parts: the configured `format` is the thing
        // that decides what an access-log line may contain, so the whole line is the assertion.
        val info = logs.messagesAt(Level.INFO)
        assertTrue(
            info.contains("POST /api/test/raw -> 200"),
            "CallLogging must log exactly method, path and status at INFO: $info",
        )
        for (line in logs.messages()) {
            assertFalse(line.contains(SECRET_TOKEN), "a log line leaked the Authorization header: $line")
            assertFalse(line.contains(SECRET_BODY_MARKER), "a log line leaked the request body: $line")
        }
    }

    // ---------- shared wiring ----------

    @Test
    fun `registerRoutes wires the ops routes`() = testApplication {
        environment { config = MapApplicationConfig() }
        // /health never touches the database, so a pool that can never connect is enough to
        // prove the wiring without Docker. initializationFailTimeout = -1 skips Hikari's eager
        // connectivity check at construction.
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://localhost:1/nonexistent"
                username = "nonexistent"
                password = "nonexistent"
                maximumPoolSize = 1
                minimumIdle = 0
                connectionTimeout = 300
                initializationFailTimeout = -1
            },
        )
        try {
            application {
                installApiPlugins(HttpConfig(port = 0, corsAllowedOrigins = emptyList()), TEST_CLOCK)
                registerRoutes(AppComponents(dataSource))
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertJsonEquals("""{"status":"UP"}""", response.bodyAsText())
        } finally {
            dataSource.close()
        }
    }
}
