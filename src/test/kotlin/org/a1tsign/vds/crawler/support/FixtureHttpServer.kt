package org.a1tsign.vds.crawler.support

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.net.Socket
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** gzips [text], for a fixture that answers with `Content-Encoding: gzip`. */
fun gzipped(text: String): ByteArray {
    val sink = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(sink).use { it.write(text.toByteArray()) }
    return sink.toByteArray()
}

/** One canned response. The last entry registered for a path repeats once the sequence runs out. */
data class FixtureResponse(
    val status: Int = 200,
    val body: String = DEFAULT_BODY,
    val contentType: String? = "text/html; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
    /** Server-side pause before the response is written -- for the timeout and overlap cases. */
    val delayMs: Long = 0,
    /** Sent instead of [body] when set -- for a `Content-Encoding` or a non-text payload. */
    val rawBody: ByteArray? = null,
) {
    companion object {
        const val DEFAULT_BODY = "<html><body><h1 id=\"title\">ok</h1></body></html>"
    }
}

/** What the server saw, which is how a politeness or header assertion is made. */
class RecordedRequest(
    val path: String,
    val query: String?,
    val hostHeader: String?,
    headers: Map<String, String>,
    /** Monotonic arrival time -- the only sound basis for an interval assertion. */
    val arrivedAtNanos: Long,
    val arrivedAt: Instant,
) {
    private val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(headers) }

    fun header(name: String): String? = headers[name]

    /** Set once the handler finished writing, so a test can prove two requests did not overlap. */
    @Volatile
    var completedAtNanos: Long = 0L
        internal set

    /** Bytes the server managed to write; short of the intended size when the client hung up. */
    @Volatile
    var bytesWritten: Long = 0L
        internal set

    /** `true` when writing the response failed, i.e. the client closed the connection early. */
    @Volatile
    var writeFailed: Boolean = false
        internal set

    override fun toString() = "$path at $arrivedAt"
}

/**
 * A local HTTP server the crawler-fetch tests point at instead of the internet. **No crawler test
 * makes a real outbound call**, so everything a limit needs to be provoked -- a delay, a 3 MiB
 * stream, a programmed `503, 503, 200` sequence, a redirect chain, a robots.txt, an `image/png` --
 * is canned here, and every arrival is recorded with its monotonic timestamp and headers.
 *
 * It binds the loopback interface on an ephemeral port -- never `0.0.0.0`, which would expose the
 * fixture to the local network for the duration of every test run. Both `http://127.0.0.1:port`
 * and `http://localhost:port` reach it, and those are **two different hosts** for
 * `HostRateLimiter` and two different origins for `RobotsCache`, which is what makes the
 * cross-host redirect and per-host delay cases testable without a second process.
 *
 * Owned by the test: `FixtureHttpServer().use { … }` or start/stop in `@BeforeEach`/`@AfterEach`.
 */
class FixtureHttpServer : AutoCloseable {

    private val handlers = ConcurrentHashMap<String, List<FixtureResponse>>()
    private val streams = ConcurrentHashMap<String, StreamSpec>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            route("{...}") {
                handle { serve(call) }
            }
        }
    }

    val port: Int

    init {
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /** `http://$host:$port$path`; [host] selects which *name* the client will use. */
    fun url(path: String, host: String = "127.0.0.1") = "http://$host:$port$path"

    /** Registers [responses] for [path]; the last one repeats for any further request. */
    fun on(path: String, vararg responses: FixtureResponse): FixtureHttpServer {
        handlers[path] = responses.toList()
        return this
    }

    fun html(path: String, body: String = FixtureResponse.DEFAULT_BODY): FixtureHttpServer =
        on(path, FixtureResponse(body = body))

    fun robots(path: String = "/robots.txt", body: String, status: Int = 200): FixtureHttpServer =
        on(path, FixtureResponse(status = status, body = body, contentType = "text/plain"))

    fun redirect(path: String, location: String, status: Int = 302): FixtureHttpServer =
        on(path, FixtureResponse(status = status, body = "", headers = mapOf("Location" to location)))

    /**
     * Streams [totalBytes] in [chunkBytes] pieces, flushing each one, and records how far it got --
     * the handle on "the oversized body was abandoned mid-stream instead of buffered".
     */
    fun stream(
        path: String,
        totalBytes: Long,
        chunkBytes: Int = 16 * 1024,
        contentType: String = "text/html",
    ): FixtureHttpServer {
        streams[path] = StreamSpec(totalBytes, chunkBytes, contentType)
        return this
    }

    fun requests(): List<RecordedRequest> = recorded.toList()

    fun requestsTo(path: String): List<RecordedRequest> = recorded.filter { it.path == path }

    override fun close() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    private suspend fun serve(call: ApplicationCall) {
        val record = RecordedRequest(
            path = call.request.path(),
            query = call.request.queryParameters.entries().takeIf { it.isNotEmpty() }
                ?.joinToString("&") { "${it.key}=${it.value.first()}" },
            hostHeader = call.request.host(),
            headers = call.request.headers.entries().associate { it.key to it.value.joinToString(",") },
            arrivedAtNanos = System.nanoTime(),
            arrivedAt = Instant.now(),
        )
        recorded += record
        try {
            val path = record.path
            val stream = streams[path]
            if (stream != null) {
                writeStream(call, stream, record)
            } else {
                writeCanned(call, path, record)
            }
        } finally {
            record.completedAtNanos = System.nanoTime()
        }
    }

    private suspend fun writeCanned(call: ApplicationCall, path: String, record: RecordedRequest) {
        val sequence = handlers[path]
        if (sequence == null) {
            call.respondText("no fixture for $path", status = HttpStatusCode.NotFound)
            return
        }
        val index = hits.getOrPut(path) { AtomicInteger() }.getAndIncrement()
        val response = sequence[minOf(index, sequence.size - 1)]
        if (response.delayMs > 0) delay(response.delayMs)
        response.headers.forEach { (name, value) -> call.response.header(name, value) }
        val contentType = response.contentType?.let(ContentType::parse) ?: ContentType.Any
        val status = HttpStatusCode.fromValue(response.status)
        try {
            val raw = response.rawBody
            if (raw != null) {
                call.respondBytes(raw, contentType, status)
                record.bytesWritten = raw.size.toLong()
            } else {
                call.respondText(response.body, contentType, status)
                // Bytes, not characters: a non-ASCII fixture body is longer on the wire than
                // `String.length` says, and this field is read by the body-cap assertions.
                record.bytesWritten = response.body.toByteArray(Charsets.UTF_8).size.toLong()
            }
        } catch (_: Exception) {
            record.writeFailed = true
        }
    }

    private suspend fun writeStream(call: ApplicationCall, spec: StreamSpec, record: RecordedRequest) {
        val chunk = ByteArray(spec.chunkBytes) { 'x'.code.toByte() }
        try {
            call.respondBytesWriter(ContentType.parse(spec.contentType)) {
                var written = 0L
                while (written < spec.totalBytes) {
                    val size = minOf(chunk.size.toLong(), spec.totalBytes - written).toInt()
                    writeFully(chunk, 0, size)
                    flush()
                    written += size
                    record.bytesWritten = written
                }
            }
        } catch (_: Exception) {
            // Expected when the fetcher abandons an oversized body: the client is gone.
            record.writeFailed = true
        }
    }

    private data class StreamSpec(val totalBytes: Long, val chunkBytes: Int, val contentType: String)
}

/**
 * A raw-socket HTTP/1.1 responder for framing a Ktor server will not produce: a response that
 * declares `Content-Length: [declaredContentLength]` **and** `Transfer-Encoding: chunked`, then
 * sends [bodyBytes] of chunked body.
 *
 * This is the response-side twin of the vds-001.6 request-side bypass (see
 * `07-knowledge/rework-log`): `io.ktor.http.cio.parseHttpBody` -- shared by the CIO server and the
 * CIO client -- tests `Transfer-Encoding: chunked` *before* `Content-Length`, so a body-size guard
 * that trusts the declared length reads an unbounded stream instead. Nothing in Ktor's server API
 * can emit both headers, hence the socket.
 */
class RawFramingServer(
    private val declaredContentLength: Long,
    private val bodyBytes: Long,
    private val chunkBytes: Int = 16 * 1024,
    private val contentType: String = "text/html; charset=utf-8",
) : AutoCloseable {

    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))

    val port: Int get() = socket.localPort

    private val requests = AtomicInteger()

    /** How many body bytes were accepted by the client before it hung up. */
    @Volatile
    var bytesWritten: Long = 0L
        private set

    /** `true` once writing the body failed, i.e. the client abandoned the response. */
    @Volatile
    var writeFailed: Boolean = false
        private set

    val requestCount: Int get() = requests.get()

    private val worker = kotlin.concurrent.thread(isDaemon = true, name = "raw-framing-fixture") {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: java.io.IOException) {
                return@thread
            }
            connection.use { serve(it) }
        }
    }

    fun url(path: String) = "http://127.0.0.1:$port$path"

    override fun close() {
        socket.close()
        worker.interrupt()
    }

    private fun serve(connection: java.net.Socket) {
        val input = connection.getInputStream()
        // Read the request head only; the fixture answers whatever is asked for.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return
            head.append(byte.toChar())
        }
        requests.incrementAndGet()
        val output = connection.getOutputStream()
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $declaredContentLength\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        output.flush()
        val chunk = ByteArray(chunkBytes) { 'x'.code.toByte() }
        try {
            var written = 0L
            while (written < bodyBytes) {
                val size = minOf(chunk.size.toLong(), bodyBytes - written).toInt()
                output.write("${Integer.toHexString(size)}\r\n".toByteArray())
                output.write(chunk, 0, size)
                output.write("\r\n".toByteArray())
                output.flush()
                written += size
                bytesWritten = written
            }
            output.write("0\r\n\r\n".toByteArray())
            output.flush()
        } catch (_: java.io.IOException) {
            writeFailed = true
        }
    }
}

/**
 * A raw-socket responder that answers **every** request with [rawResponse] verbatim, for responses
 * a Ktor server cannot produce at all -- notably a garbage status line
 * (`NOT-HTTP-AT-ALL garbage`), which the CIO client reports as `io.ktor.http.cio.ParserException`,
 * an `IllegalStateException` rather than an `IOException`. That is the input that proves the
 * fetcher classifies *every* failure as a `FetchException` instead of only `IOException`s.
 *
 * Same shape as [RawFramingServer]: one accept loop on a daemon thread, [requestCount] recording
 * how many attempts arrived, and the connection closed after the answer so a retry opens a new one.
 */
class RawResponseServer(private val rawResponse: String) : AutoCloseable {

    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))

    private val requests = AtomicInteger()

    val port: Int get() = socket.localPort

    val requestCount: Int get() = requests.get()

    private val worker = kotlin.concurrent.thread(isDaemon = true, name = "raw-response-fixture") {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: java.io.IOException) {
                return@thread
            }
            connection.use { serve(it) }
        }
    }

    fun url(path: String) = "http://127.0.0.1:$port$path"

    override fun close() {
        socket.close()
        worker.interrupt()
    }

    private fun serve(connection: Socket) {
        val input = connection.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return
            head.append(byte.toChar())
        }
        requests.incrementAndGet()
        try {
            connection.getOutputStream().apply {
                write(rawResponse.toByteArray())
                flush()
            }
        } catch (_: java.io.IOException) {
            // The client may already be gone; nothing to record here.
        }
    }
}
