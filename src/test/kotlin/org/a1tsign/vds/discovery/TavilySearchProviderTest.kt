package org.a1tsign.vds.discovery

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.config.Secret
import org.a1tsign.vds.discovery.support.TAVILY_RAW_CONTENT_MARKER
import org.a1tsign.vds.discovery.support.TavilyFixtureServer
import org.a1tsign.vds.discovery.support.TavilyReply
import org.a1tsign.vds.discovery.support.closedLoopbackPort
import org.a1tsign.vds.discovery.support.tavilyOversizedBody
import org.a1tsign.vds.discovery.support.tavilySearchBody
import org.a1tsign.vds.support.LogCapture
import org.a1tsign.vds.support.capturingLogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vendor boundary, against a local fixture server only: **nothing here may ever reach
 * `api.tavily.com`**. Every case asserts one of the two things this class exists for -- the request
 * is exactly the pinned shape (each element of which costs money or breaks a product decision when
 * it drifts), and every documented failure status becomes its own category.
 */
class TavilySearchProviderTest {

    private val key = Secret(API_KEY)

    /** The provider plus the client it owns, closed afterwards as `Application.kt` would. */
    private fun <T> withProvider(
        endpoint: String,
        maxResponseBytes: Long = 512L * 1024,
        requestTimeoutMs: Long = 5_000,
        waits: MutableList<Long>? = null,
        block: (TavilySearchProvider) -> T,
    ): T {
        val provider = TavilySearchProvider(
            endpoint = endpoint,
            apiKey = key,
            requestTimeoutMs = requestTimeoutMs,
            maxResponseBytes = maxResponseBytes,
            sleep = { millis -> waits?.add(millis) },
        )
        return try {
            block(provider)
        } finally {
            provider.client.close()
        }
    }

    private fun LogCapture.leaksKey(): Boolean = messages().any { it.contains(API_KEY) }

    @Test
    fun `the request body is pinned, the key travels only in a Bearer header, and raw content is dropped`() {
        TavilyFixtureServer().use { server ->
            server.onSearch(TavilyReply(body = tavilySearchBody()))

            var hits: List<SearchHit> = emptyList()
            val logs = capturingLogs {
                withProvider(server.url()) { provider ->
                    hits = runBlocking { provider.search("vps hosting", 7) }
                    assertEquals(1, provider.creditsSpent)
                }
            }

            val request = server.requests().single()
            assertEquals("POST", request.method)
            assertEquals("/search", request.path)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("vps hosting", body.getValue("query").jsonPrimitive.content)
            assertEquals("basic", body.getValue("search_depth").jsonPrimitive.content)
            assertFalse(body.getValue("include_answer").jsonPrimitive.boolean)
            assertFalse(body.getValue("include_raw_content").jsonPrimitive.boolean)
            assertEquals(7, body.getValue("max_results").jsonPrimitive.int)
            // Never sent: images, a domain filter, and max_age_hours (which buys into the flex tier).
            listOf("include_images", "include_domains", "exclude_domains", "max_age_hours").forEach {
                assertNull(body[it], "$it must not be sent")
            }

            assertEquals("Bearer $API_KEY", request.header("Authorization"))
            assertTrue(request.header("Content-Type")!!.startsWith("application/json"), "a JSON API is sent JSON")
            assertNull(request.query, "the key, and everything else, stays out of the URL")

            // Snippet only: raw_content, score, published_date and an unknown field are all dropped.
            assertEquals(2, hits.size)
            assertEquals(SearchHit("https://acme.example/vps", "Acme VPS", "Cheap NVMe VPS from 3 EUR"), hits[0])
            assertEquals(SearchHit("https://beta.example/", null, null), hits[1])
            assertFalse(hits.toString().contains(TAVILY_RAW_CONTENT_MARKER))
            assertFalse(logs.leaksKey(), "no log line may carry the API key")
        }
    }

    @Test
    fun `every documented failure status maps to its own category, with no key in any log line`() {
        val malformedShape = """{"results": [{"title": "no url here"}]}"""
        val cases = listOf(
            Triple(401, emptyMap<String, String>(), SearchFailureKind.SEARCH_AUTH),
            Triple(403, emptyMap(), SearchFailureKind.SEARCH_AUTH),
            Triple(432, emptyMap(), SearchFailureKind.SEARCH_QUOTA),
            Triple(429, emptyMap(), SearchFailureKind.SEARCH_RATE_LIMIT),
            Triple(429, mapOf("Retry-After" to "600"), SearchFailureKind.SEARCH_RATE_LIMIT),
            // Seconds that overflow a Long once multiplied to milliseconds: still "too long", never 0.
            Triple(429, mapOf("Retry-After" to "9223372036854775807"), SearchFailureKind.SEARCH_RATE_LIMIT),
            Triple(434, emptyMap(), SearchFailureKind.SEARCH_UNAVAILABLE),
            Triple(500, emptyMap(), SearchFailureKind.SEARCH_UNAVAILABLE),
            Triple(503, emptyMap(), SearchFailureKind.SEARCH_UNAVAILABLE),
            // Undocumented: reported as unavailable rather than guessed at.
            Triple(418, emptyMap(), SearchFailureKind.SEARCH_UNAVAILABLE),
        )

        val logs = capturingLogs {
            cases.forEach { (status, headers, expected) ->
                TavilyFixtureServer().use { server ->
                    server.onSearch(TavilyReply(status = status, headers = headers, body = """{"message": "varies"}"""))
                    withProvider(server.url()) { provider ->
                        val failure = assertFailsWith<SearchException>("HTTP $status") {
                            runBlocking { provider.search("vps hosting", 5) }
                        }
                        assertEquals(expected, failure.kind, "HTTP $status")
                        assertTrue(failure.message!!.contains(status.toString()), failure.message)
                        assertEquals(1, server.requests().size, "a terminal status is never retried")
                        if (status == 429) {
                            // The two rate-limit situations tell an operator different things.
                            val expectedDetail =
                                if (headers.isEmpty()) "no usable Retry-After" else "is above the 60000 ms ceiling"
                            assertTrue(failure.message!!.contains(expectedDetail), failure.message)
                        }
                    }
                }
            }

            // A 200 this build cannot read into the expected shape: its own category, never silence.
            listOf("not json at all", malformedShape).forEach { body ->
                TavilyFixtureServer().use { server ->
                    server.onSearch(TavilyReply(body = body))
                    withProvider(server.url()) { provider ->
                        val failure = assertFailsWith<SearchException> { runBlocking { provider.search("q", 5) } }
                        assertEquals(SearchFailureKind.SEARCH_MALFORMED, failure.kind, body)
                    }
                }
            }

            // Transport failure: nothing is listening on that port.
            withProvider("http://127.0.0.1:${closedLoopbackPort()}") { provider ->
                val failure = assertFailsWith<SearchException> { runBlocking { provider.search("q", 5) } }
                assertEquals(SearchFailureKind.SEARCH_UNAVAILABLE, failure.kind)
            }
        }

        assertFalse(logs.leaksKey(), "no failure log line may carry the API key")
    }

    @Test
    fun `a 429 with a short Retry-After is retried once, and a second one ends the pass`() {
        TavilyFixtureServer().use { server ->
            server.onSearch(
                TavilyReply(status = 429, headers = mapOf("Retry-After" to "5")),
                TavilyReply(body = tavilySearchBody()),
            )
            val waits = mutableListOf<Long>()

            withProvider(server.url(), waits = waits) { provider ->
                val hits = runBlocking { provider.search("vps hosting", 5) }

                assertEquals(2, hits.size)
                assertEquals(listOf(5_000L), waits, "the documented Retry-After is honoured, once")
                assertEquals(2, server.requests().size)
                assertEquals(2, provider.creditsSpent, "a retried request is counted as spent too")
            }
        }

        // The ceiling is inclusive: exactly 60 s is still honoured, 600 s (in the table) is not.
        TavilyFixtureServer().use { server ->
            server.onSearch(
                TavilyReply(status = 429, headers = mapOf("Retry-After" to "60")),
                TavilyReply(body = tavilySearchBody()),
            )
            val waits = mutableListOf<Long>()

            withProvider(server.url(), waits = waits) { provider ->
                assertEquals(2, runBlocking { provider.search("q", 5) }.size)
                assertEquals(listOf(60_000L), waits)
            }
        }

        TavilyFixtureServer().use { server ->
            server.onSearch(TavilyReply(status = 429, headers = mapOf("Retry-After" to "5")))
            val waits = mutableListOf<Long>()

            withProvider(server.url(), waits = waits) { provider ->
                val failure = assertFailsWith<SearchException> { runBlocking { provider.search("q", 5) } }

                assertEquals(SearchFailureKind.SEARCH_RATE_LIMIT, failure.kind)
                assertEquals(1, waits.size, "exactly one wait: the pass ends rather than looping")
                assertEquals(2, server.requests().size)
            }
        }
    }

    @Test
    fun `a stalled response is unavailable and a body above the cap is refused`() {
        TavilyFixtureServer().use { server ->
            server.onSearch(TavilyReply(body = tavilySearchBody(), delayMs = 2_000))

            withProvider(server.url(), requestTimeoutMs = 300) { provider ->
                val failure = assertFailsWith<SearchException> { runBlocking { provider.search("q", 5) } }

                assertEquals(SearchFailureKind.SEARCH_UNAVAILABLE, failure.kind)
            }
        }

        TavilyFixtureServer().use { server ->
            server.onSearch(TavilyReply(body = tavilyOversizedBody(200_000)))

            withProvider(server.url(), maxResponseBytes = 2_048) { provider ->
                val failure = assertFailsWith<SearchException> { runBlocking { provider.search("q", 5) } }

                // The refusal is what is asserted; that the body was abandoned mid-stream rather
                // than buffered follows from readCapped's counting loop (argued in the report).
                assertEquals(SearchFailureKind.SEARCH_MALFORMED, failure.kind)
                assertTrue(failure.message!!.contains("2048"), failure.message)
            }
        }

        // The cap is "above", not "at": a body of exactly maxResponseBytes is read normally.
        TavilyFixtureServer().use { server ->
            val body = tavilyOversizedBody(100)
            server.onSearch(TavilyReply(body = body))

            withProvider(server.url(), maxResponseBytes = body.toByteArray().size.toLong()) { provider ->
                assertEquals(emptyList(), runBlocking { provider.search("q", 5) })
            }
        }
    }

    @Test
    fun `the usage cross-check reports a figure, and any failure is null rather than thrown`() {
        TavilyFixtureServer().use { server ->
            server.onUsage(
                TavilyReply(body = """{"key": {"usage": 42, "limit": 1000}, "account": {"plan_usage": 55}}"""),
                TavilyReply(status = 500, body = "nope"),
            )

            withProvider(server.url()) { provider ->
                assertEquals(SearchUsage(usedCredits = 42, creditLimit = 1000), runBlocking { provider.usage() })
                assertNull(runBlocking { provider.usage() }, "a non-200 cross-check is advisory, so it is null")
            }
            // The key travels in a header on this call too, and in no URL.
            server.requests().forEach { request ->
                assertEquals("GET", request.method)
                assertEquals("/usage", request.path)
                assertEquals("Bearer $API_KEY", request.header("Authorization"))
                assertNull(request.query)
            }
        }

        withProvider("http://127.0.0.1:${closedLoopbackPort()}") { provider ->
            assertNull(runBlocking { provider.usage() }, "a transport failure never fails a pass")
        }
    }

    private companion object {
        const val API_KEY = "tvly-test-key-that-must-never-be-logged"
    }
}
