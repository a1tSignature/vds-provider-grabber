package org.a1tsign.vds.discovery.support

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One canned reply. The last reply registered for a path repeats once the sequence runs out. */
data class TavilyReply(
    val status: Int = 200,
    val body: String = "",
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
    /** Server-side pause before anything is written -- for the request-timeout case. */
    val delayMs: Long = 0,
)

class RecordedTavilyRequest(
    val method: String,
    val path: String,
    val query: String?,
    val body: String,
    headers: Map<String, String>,
) {
    private val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(headers) }

    fun header(name: String): String? = headers[name]

    fun headerValues(): Collection<String> = headers.values
}

/** Serve Tavily-shaped replies locally so assertions see the actual request without contacting the vendor. */
class TavilyFixtureServer : AutoCloseable {

    private val replies = ConcurrentHashMap<String, List<TavilyReply>>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val recorded = CopyOnWriteArrayList<RecordedTavilyRequest>()

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing { route("{...}") { handle { serve(call) } } }
    }

    val port: Int

    init {
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    fun url(): String = "http://127.0.0.1:$port"

    fun onSearch(vararg replies: TavilyReply): TavilyFixtureServer = on("/search", *replies)

    fun onUsage(vararg replies: TavilyReply): TavilyFixtureServer = on("/usage", *replies)

    fun on(path: String, vararg replies: TavilyReply): TavilyFixtureServer {
        this.replies[path] = replies.toList()
        return this
    }

    fun requests(): List<RecordedTavilyRequest> = recorded.toList()

    override fun close() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    private suspend fun serve(call: ApplicationCall) {
        val path = call.request.path()
        recorded += RecordedTavilyRequest(
            method = call.request.httpMethod.value,
            path = path,
            query = call.request.queryParameters.entries().takeIf { it.isNotEmpty() }
                ?.joinToString("&") { "${it.key}=${it.value.first()}" },
            body = runCatching { call.receiveText() }.getOrDefault(""),
            headers = call.request.headers.entries().associate { it.key to it.value.joinToString(",") },
        )
        val sequence = replies[path]
        if (sequence == null) {
            call.respondText("no fixture for $path", status = HttpStatusCode.NotFound)
            return
        }
        val index = hits.getOrPut(path) { AtomicInteger() }.getAndIncrement()
        val reply = sequence[minOf(index, sequence.size - 1)]
        if (reply.delayMs > 0) delay(reply.delayMs)
        reply.headers.forEach { (name, value) -> call.response.header(name, value) }
        call.respondText(reply.body, ContentType.parse(reply.contentType), HttpStatusCode.fromValue(reply.status))
    }
}

/** A port nothing listens on: bound to learn a free number, then released. */
fun closedLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

/** The raw page content a response must never carry into a [org.a1tsign.vds.discovery.SearchHit]. */
const val TAVILY_RAW_CONTENT_MARKER: String = "FULL PAGE CONTENT THAT MUST NOT BE STORED"

/**
 * A `200` body in the vendor's shape: two results, the first one also carrying `raw_content`,
 * `score`, `published_date` and a field this build has never heard of.
 */
fun tavilySearchBody(): String = """
    {
      "query": "vps hosting",
      "answer": null,
      "response_time": 0.42,
      "unknown_future_field": {"nested": true},
      "results": [
        {
          "url": "https://acme.example/vps",
          "title": "Acme VPS",
          "content": "Cheap NVMe VPS from 3 EUR",
          "score": 0.93,
          "published_date": "2026-11-01",
          "raw_content": "$TAVILY_RAW_CONTENT_MARKER",
          "favicon": "https://acme.example/favicon.ico"
        },
        {
          "url": "https://beta.example/",
          "title": null,
          "content": null,
          "score": 0.11
        }
      ]
    }
""".trimIndent()

fun tavilyOversizedBody(bytes: Int): String =
    """{"results": [], "padding": "${"p".repeat(bytes)}"}"""
