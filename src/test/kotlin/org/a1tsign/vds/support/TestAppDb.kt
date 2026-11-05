package org.a1tsign.vds.support

import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.config.AppConfig
import org.a1tsign.vds.api.dto.LoginRequest
import org.a1tsign.vds.api.ApiJson
import org.a1tsign.vds.api.registerRoutes
import org.a1tsign.vds.auth.BootstrapAdmin
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.buildComponents
import org.a1tsign.vds.module
import org.a1tsign.vds.config.loadConfig
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.time.Clock
import org.a1tsign.vds.fx.CurrencyRateProvider

fun testAppConfig(
    withBootstrapAdmin: Boolean = true,
    jwtSecret: String = "test-jwt-secret-012345678901234567890123456789",
    jwtTtlHours: Long = 24,
    bootstrapUsername: String = "bootstrap-admin",
    bootstrapPassword: String = "bootstrap-password-1",
    // Additive (vds-002.7): lets a crawler-admin-API test switch VDS_CRAWLER_* on without every
    // other call site having to know about it -- defaults to empty, so existing behaviour is
    // unchanged.
    extraEnv: Map<String, String> = emptyMap(),
): AppConfig = loadConfig(
    buildMap {
        put("VDS_DB_URL", PostgresSupport.jdbcUrl)
        put("VDS_DB_USER", PostgresSupport.username)
        put("VDS_DB_PASSWORD", PostgresSupport.password)
        put("VDS_JWT_SECRET", jwtSecret)
        put("VDS_JWT_TTL_HOURS", jwtTtlHours.toString())
        if (withBootstrapAdmin) {
            put("VDS_BOOTSTRAP_ADMIN_USERNAME", bootstrapUsername)
            put("VDS_BOOTSTRAP_ADMIN_PASSWORD", bootstrapPassword)
        }
        putAll(extraEnv)
    },
)

/** Full app harness: shared Testcontainers database, app factory, startup bootstrap and real routes. */
fun testAppDb(
    clock: Clock,
    appConfig: AppConfig = testAppConfig(),
    passwordHasher: PasswordHasher = PasswordHasher(),
    currencyRateProvider: CurrencyRateProvider? = null,
    authenticatedRoutes: Route.() -> Unit = {},
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    var fxHttpClient: io.ktor.client.HttpClient? = null
    testApplication {
        environment { config = MapApplicationConfig() }
        application {
            val components = buildComponents(
                appConfig,
                PostgresSupport.dataSource,
                clock,
                PostgresSupport.database,
                passwordHasher,
                currencyRateProvider,
            )
            fxHttpClient = components.fxHttpClient
            BootstrapAdmin(components.database, components.userRepository, passwordHasher)
                .ensure(appConfig.bootstrapAdmin, clock.instant())
            module(components)
            routing { authenticate("bearer-jwt") { authenticatedRoutes() } }
        }
        block()
    }
    fxHttpClient?.close()
}

suspend fun ApplicationTestBuilder.loginForToken(username: String, password: String): String {
    val response = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(ApiJson.encodeToString(LoginRequest(username, password)))
    }
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
}

suspend fun ApplicationTestBuilder.loginResponse(username: String, password: String) = client.post("/api/v1/auth/login") {
    contentType(ContentType.Application.Json)
    setBody(ApiJson.encodeToString(LoginRequest(username, password)))
}

suspend fun ApplicationTestBuilder.loginAs(
    fixture: UserFixture,
    username: String,
    password: String,
    roles: Set<Role>,
    at: java.time.Instant,
    status: UserStatus = UserStatus.ACTIVE,
): String {
    fixture.create(username, password, roles, status, at)
    return loginForToken(username, password)
}
