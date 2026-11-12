package org.a1tsign.vds.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.loginAs
import org.a1tsign.vds.support.testAppDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeps `src/main/resources/openapi.yaml` honest, per ADR-07: a hand-written contract is only
 * acceptable because this test fails on a missing *or* extra operation, not just "the file is
 * served".
 *
 * YAML parsing uses `it.krzeminski:snakeyaml-engine-kmp` (the transitive dependency ADR-07/the
 * task describe as "already on the test classpath") via reflection, not a direct import: it
 * resolves transitively only onto `testRuntimeClasspath` (through
 * `ktor-server-routing-openapi -> ktor-openapi-schema -> kaml -> snakeyaml-engine-kmp`), not onto
 * `testCompileClasspath` -- `./gradlew dependencies --configuration testCompileClasspath` shows no
 * `it.krzeminski`/`com.charleskorn.kaml` entry, only `testRuntimeClasspath` does. A direct `import`
 * therefore fails to compile even though the class loads fine at test run time. `Class.forName`
 * sidesteps exactly that gap without adding any dependency to `build.gradle.kts`.
 */
class OpenApiContractTest : AbstractDbTest() {
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    @Test
    fun `served authenticated, 401 without a token, and the body is the YAML document`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val viewer = UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), PasswordHasher())
            val token = loginAs(viewer, "openapi-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant())

            val ok = client.get("/openapi.yaml") { header(auth(token).first, auth(token).second) }
            assertEquals(HttpStatusCode.OK, ok.status)
            val body = ok.bodyAsText()
            assertTrue(body.startsWith("openapi:"), "body must start with 'openapi:', was: ${body.take(40)}")
            val contentType = ok.headers[HttpHeaders.ContentType].orEmpty()
            assertTrue(
                contentType.contains("yaml") || contentType.contains("text"),
                "unexpected Content-Type: $contentType",
            )

            val unauthorized = client.get("/openapi.yaml")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            val envelope = Json.parseToJsonElement(unauthorized.bodyAsText()).jsonObject
            assertEquals(401, envelope.getValue("status").jsonPrimitive.int)
            assertTrue(envelope.containsKey("error"))
            assertTrue(envelope.containsKey("message"))
            assertTrue(envelope.containsKey("path"))
            assertFalse(envelope.containsKey("details"), "a non-validation error must omit 'details'")
        }
    }

    @Test
    fun `the document is valid YAML`() {
        val document = parsedDocument()
        assertEquals("3.0.3", document["openapi"])
        assertTrue((document["paths"] as Map<*, *>).isNotEmpty())
    }

    @Test
    fun `declared operations exactly match the routes the application registers`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            // Trigger application startup so the routing tree is fully built before inspection.
            client.get("/health")
            val root = application.plugin(RoutingRoot.Plugin)
            val actual = collectRoutes(root)

            val declared = declaredOperations(parsedDocument())

            val missing = declared - actual
            val extra = actual - declared
            assertTrue(
                missing.isEmpty() && extra.isEmpty(),
                "operation set mismatch.\nMissing from the app (declared but not registered): $missing\n" +
                    "Extra in the app (registered but not declared): $extra",
            )
        }
    }

    @Test
    fun `the walk excludes the static SPA subtree but still sees a real API route`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            client.get("/health")
            val root = application.plugin(RoutingRoot.Plugin)
            val actual = collectRoutes(root)

            assertTrue(
                "GET" to "/api/v1/dictionaries" in actual,
                "the walk must still see a real, declared API route: $actual",
            )
            assertTrue(
                actual.none { (_, path) -> path == "/" },
                "the SPA's static-content tailcard route must be excluded from the walk: $actual",
            )
        }
    }

    @Test
    fun `the error envelope is referenced by the standard statuses of a sampled endpoint, and 413, 415, 502 are declared`() {
        val document = parsedDocument()
        val priceOperation = navigate(document, "paths", "/api/v1/tariffs/{id}/price", "put") as Map<*, *>
        val responses = priceOperation["responses"] as Map<*, *>
        for (code in listOf("400", "401", "403", "404", "409")) {
            val raw = responses[code] as? Map<*, *> ?: error("no $code response declared for PUT /api/v1/tariffs/{id}/price")
            val response = resolveRef(raw, document)
            val schema = navigate(response, "content", "application/json", "schema") as Map<*, *>
            assertEquals(
                "#/components/schemas/ApiError",
                schema["\$ref"],
                "PUT /api/v1/tariffs/{id}/price status $code must reference the shared ApiError schema",
            )
        }

        for (code in listOf("413", "415", "502")) {
            assertTrue(containsResponseCode(document, code), "the document must declare a $code response somewhere")
        }
    }

    @Test
    fun `no money or coordinate field is typed as a JSON number`() {
        val document = parsedDocument()
        val moneyFields = setOf("amount", "amountUsd", "usdRate", "minMonthlyPriceUsd", "maxPriceUsd", "latitude", "longitude")
        val found = mutableListOf<Pair<String, Any?>>()
        collectPropertyTypes(document, moneyFields, found)
        assertTrue(found.isNotEmpty(), "none of $moneyFields were found in the document at all -- the walk is broken")
        for ((field, type) in found) {
            assertEquals("string", type, "property '$field' must be typed as a string, not $type")
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Parses `openapi.yaml` off the classpath into a generic `Map`/`List`/`String` tree -- see the class doc for why reflection. */
    private fun parsedDocument(): Map<*, *> {
        val text = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use { it.readBytes().decodeToString() }
        val loadClass = Class.forName("it.krzeminski.snakeyaml.engine.kmp.api.Load")
        val parser = loadClass.getDeclaredConstructor().newInstance()
        val loadOne = loadClass.getMethod("loadOne", String::class.java)
        @Suppress("UNCHECKED_CAST")
        return loadOne.invoke(parser, text) as Map<*, *>
    }

    private fun navigate(root: Map<*, *>, vararg path: String): Any? {
        var current: Any? = root
        for (segment in path) {
            current = (current as Map<*, *>)[segment]
        }
        return current
    }

    private fun resolveRef(node: Map<*, *>, document: Map<*, *>): Map<*, *> {
        val ref = node["\$ref"] as? String ?: return node
        val segments = ref.removePrefix("#/").split("/")
        @Suppress("UNCHECKED_CAST")
        return navigate(document, *segments.toTypedArray()) as Map<*, *>
    }

    private fun containsResponseCode(node: Any?, code: String): Boolean = when (node) {
        is Map<*, *> -> node.keys.any { it == code } || node.values.any { containsResponseCode(it, code) }
        is List<*> -> node.any { containsResponseCode(it, code) }
        else -> false
    }

    private fun collectPropertyTypes(node: Any?, names: Set<String>, found: MutableList<Pair<String, Any?>>) {
        when (node) {
            is Map<*, *> -> for ((key, value) in node) {
                if (key is String && key in names && value is Map<*, *>) {
                    found += key to value["type"]
                }
                collectPropertyTypes(value, names, found)
            }
            is List<*> -> node.forEach { collectPropertyTypes(it, names, found) }
            else -> {}
        }
    }

    /** `(method, path)` pairs declared under `paths`, with `method` upper-cased. */
    private fun declaredOperations(document: Map<*, *>): Set<Pair<String, String>> {
        val httpMethods = setOf("get", "post", "put", "patch", "delete")
        val paths = document["paths"] as Map<*, *>
        return paths.entries.flatMap { (path, item) ->
            (item as Map<*, *>).keys
                .filterIsInstance<String>()
                .filter { it in httpMethods }
                .map { method -> method.uppercase() to (path as String) }
        }.toSet()
    }

    /**
     * Walks the real routing tree, normalising `{paramName}` path-parameter syntax.
     *
     * Excludes the SPA static-content subtree (`api/routes/StaticRoutes.kt`'s `staticResources`):
     * it is never declared in `openapi.yaml`, and the walk cannot otherwise describe it --
     * `staticResources` registers a tailcard route whose selector this test has no public type to
     * match against, only the `"(static-content)"` string Ktor's own (package-private)
     * `TailcardSelector.toString()` produces.
     */
    private fun collectRoutes(node: RoutingNode, pathSegments: List<String> = emptyList()): Set<Pair<String, String>> {
        val selector = node.selector
        if (selector.toString() == "(static-content)") return emptySet()
        val nextSegments = when (selector) {
            is PathSegmentConstantRouteSelector -> pathSegments + selector.value
            is PathSegmentParameterRouteSelector -> pathSegments + "{${selector.name}}"
            else -> pathSegments
        }
        val here: Set<Pair<String, String>> =
            if (selector is HttpMethodRouteSelector && node.hasHandler()) {
                setOf(selector.method.value to "/" + pathSegments.joinToString("/"))
            } else {
                emptySet()
            }
        return here + node.children.flatMap { collectRoutes(it, nextSegments) }
    }
}
