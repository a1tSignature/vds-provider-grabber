package org.a1tsign.vds.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.loginAs
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppConfig
import org.a1tsign.vds.support.testAppDb
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicCatalogApiTest : AbstractDbTest() {
    private val root = "/api/v1"

    @Test
    fun `catalog reads work anonymously and return the same data to an authenticated caller`() = runBlocking {
        testAppDb(clock) {
            val catalog = CatalogFixture(PostgresSupport.database)
            val provider = catalog.createProvider("public-provider", clock.instant())
            val tariff = catalog.createTariff(provider.id, "Public plan", clock.instant())
            catalog.createTariffPrice(
                tariff.id, BigDecimal("12.00"), "USD", BillingPeriod.MONTHLY, clock.instant(),
                dataSource = PriceDataSource.MANUAL, amountUsd = BigDecimal("12.0000"),
            )
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val publicPaths = listOf(
                "$root/providers?size=50",
                "$root/providers/${provider.id}",
                "$root/providers/${provider.id}/tariffs",
                "$root/tariffs?providerId=${provider.id}",
                "$root/tariffs/by-provider?providerId=${provider.id}",
                "$root/tariffs/${tariff.id}",
                "$root/tariffs/${tariff.id}/price-history",
                "$root/dictionaries",
            )

            publicPaths.forEach { path ->
                val anonymous = client.get(path)
                val authenticated = client.get(path) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, anonymous.status, path)
                assertEquals(HttpStatusCode.OK, authenticated.status, path)
                assertEquals(anonymous.bodyAsText(), authenticated.bodyAsText(), path)
            }

            assertEquals(HttpStatusCode.Unauthorized, client.post("$root/providers").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("$root/providers/1/tariffs").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("$root/admin/users").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/openapi.yaml").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("$root/internal/catalog/providers") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun `internal catalog reads expose all statuses only to the configured editor identity`() = runBlocking {
        val config = testAppConfig(
            extraEnv = mapOf(
                "VDS_CRAWLER_USERNAME" to "crawler-service",
                "VDS_CRAWLER_PASSWORD" to "crawler-password-1",
            ),
        )
        testAppDb(clock, appConfig = config) {
            val catalog = CatalogFixture(PostgresSupport.database)
            val rejected = catalog.createProvider("rejected-internal", clock.instant())
            val tariff = catalog.createTariff(rejected.id, "Rejected internal plan", clock.instant())
            transaction(PostgresSupport.database) {
                ProviderRepository().updateSystemStatus(rejected.id, SystemStatus.REJECTED, clock.instant())
            }
            val crawlerToken = loginAs(
                UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), org.a1tsign.vds.auth.PasswordHasher()),
                "crawler-service", "long-password-crawler", setOf(Role.EDITOR), clock.instant(),
            )
            val ordinaryEditor = loginAs(
                UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), org.a1tsign.vds.auth.PasswordHasher()),
                "ordinary-editor", "long-password-editor", setOf(Role.EDITOR), clock.instant(),
            )

            assertEquals(HttpStatusCode.Unauthorized, client.get("$root/internal/catalog/providers").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("$root/internal/catalog/providers") { bearerAuth(ordinaryEditor) }.status)

            val list = client.get("$root/internal/catalog/providers?systemStatus=REJECTED") { bearerAuth(crawlerToken) }
            assertEquals(HttpStatusCode.OK, list.status)
            val body = Json.parseToJsonElement(list.bodyAsText()).jsonObject
            assertEquals(
                setOf(rejected.id),
                body.getValue("content").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet(),
            )

            val detail = client.get("$root/internal/catalog/providers/${rejected.id}") { bearerAuth(crawlerToken) }
            assertEquals(HttpStatusCode.OK, detail.status)
            assertEquals(
                "REJECTED",
                Json.parseToJsonElement(detail.bodyAsText()).jsonObject.getValue("provider").jsonObject
                    .getValue("systemStatus").jsonPrimitive.content,
            )
            val tariffs = client.get("$root/internal/catalog/providers/${rejected.id}/tariffs") { bearerAuth(crawlerToken) }
            assertEquals(HttpStatusCode.OK, tariffs.status)
            assertEquals(
                setOf(tariff.id),
                Json.parseToJsonElement(tariffs.bodyAsText()).jsonObject.getValue("content").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet(),
            )

            assertEquals(HttpStatusCode.NotFound, client.get("$root/providers/${rejected.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/tariffs/${tariff.id}") { bearerAuth(crawlerToken) }.status)
        }
    }

    @Test
    fun `catalog lists and direct reads include candidates but hide rejected providers`() = runBlocking {
        testAppDb(clock) {
            val catalog = CatalogFixture(PostgresSupport.database)
            val configured = catalog.createProvider("configured-provider", clock.instant())
            val candidate = catalog.createProvider("candidate-provider", clock.instant())
            val rejected = catalog.createProvider("rejected-provider", clock.instant())
            transaction(PostgresSupport.database) {
                ProviderRepository().updateSystemStatus(candidate.id, SystemStatus.CANDIDATE, clock.instant())
                ProviderRepository().updateSystemStatus(rejected.id, SystemStatus.REJECTED, clock.instant())
            }

            fun tariffFor(providerId: Long, name: String) = catalog.createTariff(providerId, name, clock.instant())
            val configuredTariff = tariffFor(configured.id, "Configured plan")
            val candidateTariff = tariffFor(candidate.id, "Candidate plan")
            val rejectedTariff = tariffFor(rejected.id, "Rejected plan")
            listOf(configuredTariff, candidateTariff, rejectedTariff).forEach { tariff ->
                catalog.createTariffPrice(
                    tariff.id, BigDecimal("12.00"), "USD", BillingPeriod.MONTHLY, clock.instant(),
                    amountUsd = BigDecimal("12.0000"),
                )
            }
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")

            val anonymousList = client.get("$root/providers?size=50")
            val authenticatedList = client.get("$root/providers?size=50") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, anonymousList.status)
            assertEquals(anonymousList.bodyAsText(), authenticatedList.bodyAsText())
            val list = Json.parseToJsonElement(anonymousList.bodyAsText()).jsonObject
            val ids = list.getValue("content").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet()
            assertEquals(setOf(configured.id, candidate.id), ids)
            assertEquals(2, list.getValue("totalElements").jsonPrimitive.int)

            val candidateFilter = client.get("$root/providers?systemStatus=CANDIDATE")
            assertEquals(HttpStatusCode.OK, candidateFilter.status)
            assertEquals(
                setOf(candidate.id),
                Json.parseToJsonElement(candidateFilter.bodyAsText()).jsonObject.getValue("content").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet(),
            )
            assertEquals(HttpStatusCode.BadRequest, client.get("$root/providers?systemStatus=REJECTED").status)
            assertEquals(HttpStatusCode.OK, client.get("$root/providers/${candidate.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/providers/${rejected.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/providers/${rejected.id}/tariffs").status)

            for (providerId in listOf(configured.id, candidate.id)) {
                val flat = client.get("$root/tariffs?providerId=$providerId")
                val grouped = client.get("$root/tariffs/by-provider?providerId=$providerId")
                assertEquals(HttpStatusCode.OK, flat.status)
                assertEquals(HttpStatusCode.OK, grouped.status)
                assertEquals(1, Json.parseToJsonElement(flat.bodyAsText()).jsonObject.getValue("totalElements").jsonPrimitive.int)
                assertEquals(1, Json.parseToJsonElement(grouped.bodyAsText()).jsonObject.getValue("totalElements").jsonPrimitive.int)
            }

            val rejectedFlat = Json.parseToJsonElement(client.get("$root/tariffs?providerId=${rejected.id}").bodyAsText()).jsonObject
            val rejectedGrouped = Json.parseToJsonElement(client.get("$root/tariffs/by-provider?providerId=${rejected.id}").bodyAsText()).jsonObject
            assertEquals(0, rejectedFlat.getValue("totalElements").jsonPrimitive.int)
            assertEquals(0, rejectedGrouped.getValue("totalElements").jsonPrimitive.int)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/tariffs/${rejectedTariff.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("$root/tariffs/${rejectedTariff.id}/price-history").status)

        }
    }
}
