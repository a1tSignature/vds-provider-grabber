package org.a1tsign.vds.crawler.write

import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.closedLoopbackPort
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.Virtualization
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.capturingLogs
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.event.Level
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against the real application and PostgreSQL ([CrawlerAppHarness]). Assertions are scoped
 * to rows the test created; request counts come from what the server saw.
 */
class CatalogApiClientTest : AbstractDbTest() {

    @Test
    fun `three writes after one login reach the server as exactly one login plus three calls`() {
        withCatalog { client, harness ->
            val provider = client.createProvider(providerWrite("acme"))
            client.putLegalInfo(provider.id, legalInfoWrite())
            client.putPaymentMethods(provider.id, listOf(PaymentMethodWrite("VISA_MASTERCARD")))

            assertEquals(1, harness.loginCount(), "the token must be cached across calls")
            assertEquals(
                listOf(
                    "POST /api/v1/auth/login",
                    "POST /api/v1/providers",
                    "PUT /api/v1/providers/${provider.id}/legal-info",
                    "PUT /api/v1/providers/${provider.id}/payment-methods",
                ),
                harness.calls().map { "${it.method} ${it.path}" },
            )
        }
    }

    @Test
    fun `a token inside 60 seconds of expiry is replaced before the next request is issued`() {
        withCatalog { client, harness ->
            client.createProvider(providerWrite("acme"))
            assertEquals(1, harness.loginCount())
            harness.clearRecordedCalls()

            // A still-valid token inside the renewal margin distinguishes proactive refresh from a 401 retry.
            clock.advance(Duration.ofHours(24).minusSeconds(30))
            val found = client.findProviderBySlug("acme")

            assertEquals("acme", found?.slug)
            assertEquals(
                listOf("POST /api/v1/auth/login", "GET /api/v1/internal/catalog/providers"),
                harness.calls().map { "${it.method} ${it.path}" },
                "the re-login must precede the request, not follow a failure",
            )
        }
    }

    @Test
    fun `a revoked token is replaced by exactly one re-login and the call then succeeds`() {
        withCatalog { client, harness ->
            val provider = client.createProvider(providerWrite("acme"))
            harness.clearRecordedCalls()
            harness.revokeServiceAccountTokens()

            val detail = client.getProvider(provider.id)

            assertEquals("acme", detail.provider.slug)
            assertEquals(
                listOf(
                    "GET /api/v1/internal/catalog/providers/${provider.id}",
                    "POST /api/v1/auth/login",
                    "GET /api/v1/internal/catalog/providers/${provider.id}",
                ),
                harness.calls().map { "${it.method} ${it.path}" },
                "one rejected attempt, one re-login, one successful retry",
            )
        }
    }

    @Test
    fun `a rejected login is a non-retryable Unauthorized and no catalog call is attempted`() {
        withCatalog(config = { it.crawlerConfig(password = "wrong-password") }) { client, harness ->
            val failure = assertFailsWith<CatalogApiException.Unauthorized> {
                client.findProviderBySlug("acme")
            }

            assertEquals(1, harness.loginCount(), "a rejected login is not retried")
            assertEquals(listOf("POST /api/v1/auth/login"), harness.calls().map { "${it.method} ${it.path}" })
            assertTrue(failure.message!!.contains("crawler-service"), "message was: ${failure.message}")
        }
    }

    @Test
    fun `a second consecutive 401 fails the call without a third attempt`() {
        withCatalog { client, harness ->
            client.createProvider(providerWrite("acme"))
            harness.clearRecordedCalls()
            harness.rejectCatalogCallsAsUnauthorized = true

            assertFailsWith<CatalogApiException.Unauthorized> { client.findProviderBySlug("acme") }

            assertEquals(
                listOf(
                    "GET /api/v1/internal/catalog/providers",
                    "POST /api/v1/auth/login",
                    "GET /api/v1/internal/catalog/providers",
                ),
                harness.calls().map { "${it.method} ${it.path}" },
                "exactly one re-login and exactly two attempts of the call",
            )
        }
    }

    @Test
    fun `no password, token or Authorization value ever reaches the log or an exception`() {
        val failures = mutableListOf<String>()
        val logs = capturingLogs {
            withCatalog { client, harness ->
                val provider = client.createProvider(providerWrite("acme"))
                client.putLegalInfo(provider.id, legalInfoWrite())
                failures += causeChain(
                    assertFailsWith<CatalogApiException.Conflict> { client.createProvider(providerWrite("acme")) },
                )
                failures += causeChain(
                    assertFailsWith<CatalogApiException.ClientError> {
                        client.createTariff(provider.id, tariffWrite("VPS-1", vcpuCount = 0))
                    },
                )
                // A connect failure on login: the only wrapped throwable, on the request carrying the password.
                CatalogApiClient(
                    config = harness.crawlerConfig(apiBaseUrl = "http://127.0.0.1:${closedLoopbackPort()}"),
                    clock = clock,
                    limits = FAST_LIMITS,
                    backoffJitter = { 0L },
                    sleep = { },
                ).use { offline ->
                    failures += causeChain(
                        assertFailsWith<CatalogApiException.TransportFailure> { offline.findProviderBySlug("acme") },
                    )
                }
            }
        }

        val captured = (
            logs.messages() + logs.throwablesAt(Level.WARN) + logs.throwablesAt(Level.ERROR) + failures
            ).joinToString("\n")
        assertTrue(captured.isNotEmpty(), "nothing was captured, so the assertion would be vacuous")
        assertTrue(!captured.contains("crawler-password-1"), "the password leaked: $captured")
        assertTrue(!captured.contains("Bearer"), "an Authorization value leaked: $captured")
        // Every HS256 JWT this project issues is base64url of {"typ"... -- i.e. starts with eyJ.
        assertTrue(!captured.contains("eyJ"), "a token leaked: $captured")
    }

    @Test
    fun `findProviderBySlug picks the exact slug out of paged q matches and returns null for an absent one`() {
        withCatalog(pageSize = 2) { client, harness ->
            // Put the exact slug after substring matches so it can only be found by paging.
            client.createProvider(providerWrite("not-acme"))
            client.createProvider(providerWrite("acme-cloud"))
            client.createProvider(providerWrite("zeta", name = "Acme Hosting"))
            val exact = client.createProvider(providerWrite("acme"))
            harness.clearRecordedCalls()

            assertEquals(exact.id, client.findProviderBySlug("acme")?.id)
            assertEquals(2, harness.countCalls("GET", "/api/v1/internal/catalog/providers"), "page 0 then page 1")

            harness.clearRecordedCalls()
            assertNull(client.findProviderBySlug("ghost"))
            assertEquals(1, harness.countCalls("GET", "/api/v1/internal/catalog/providers"), "an empty result stops immediately")
        }
    }

    @Test
    fun `the q walk stops at the ten-page bound instead of paging forever`() {
        withCatalog(pageSize = 1) { client, harness ->
            repeat(11) { index -> client.createProvider(providerWrite("acme-$index")) }
            client.createProvider(providerWrite("acme"))
            harness.clearRecordedCalls()

            assertNull(client.findProviderBySlug("acme"), "beyond the bound the slug is reported as absent")
            assertEquals(10, harness.countCalls("GET", "/api/v1/internal/catalog/providers"))
        }
    }

    @Test
    fun `listAllTariffs accumulates every page and returns deactivated tariffs too, which getProvider does not`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        // pageSize = 1 forces three pages: a first-page-only read would report a truncated list as complete.
        withCatalog(pageSize = 1) { client, harness ->
            val provider = fixture.createProvider("acme", clock.instant())
            fixture.createTariff(provider.id, "active-1", clock.instant())
            fixture.createTariff(provider.id, "active-2", clock.instant())
            fixture.createTariff(provider.id, "retired", clock.instant(), isActive = false)

            val all = client.listAllTariffs(provider.id)
            val detail = client.getProvider(provider.id)

            assertEquals(listOf("active-1", "active-2", "retired"), all.map { it.name }.sorted())
            assertEquals(listOf(false, true, true), all.map { it.isActive }.sorted())
            assertEquals(
                listOf("active-1", "active-2"),
                detail.tariffs.map { it.tariff.name }.sorted(),
                "the detail endpoint carries active tariffs only -- which is why listAllTariffs exists",
            )
            assertEquals(
                3,
                harness.countCalls("GET", "/api/v1/internal/catalog/providers/${provider.id}/tariffs"),
                "one request per page, and the loop stops at totalPages",
            )
        }
    }

    @Test
    fun `every write endpoint round-trips the values that were sent`() {
        withCatalog { client, harness ->
            val provider = client.createProvider(providerWrite("acme", name = "Acme Hosting"))
            client.putLegalInfo(
                provider.id,
                LegalInfoWrite(
                    dataSource = "CRAWLER",
                    confidence = "LOW",
                    legalEntityName = "Acme Ltd",
                    registrationCountry = "DE",
                    registrationNumber = "HRB-1",
                    legalAddress = "Berlin",
                    sourceUrl = "https://acme.example/legal",
                ),
            )
            client.putPaymentMethods(
                provider.id,
                listOf(PaymentMethodWrite("VISA_MASTERCARD", "Visa only"), PaymentMethodWrite("CRYPTO_BTC")),
            )
            val location = client.createLocation(
                provider.id,
                LocationWrite(
                    countryCode = "DE",
                    region = "Berlin",
                    city = "Berlin",
                    datacenterName = "DC1",
                    latitude = BigDecimal("52.52"),
                    longitude = BigDecimal("13.405"),
                ),
            )
            val tariff = client.createTariff(provider.id, tariffWrite("VPS-1"))
            val links = client.replaceTariffLocations(tariff.id, listOf(location.id))
            val price = client.putPrice(
                tariff.id,
                PriceWrite("MONTHLY", BigDecimal("10.50"), "USD", "CRAWLER"),
            )

            val renamed = client.updateProvider(provider.id, providerWrite("acme", name = "Acme Hosting Renamed"))
            val moved = client.updateLocation(
                location.id,
                LocationWrite(
                    countryCode = "DE",
                    region = "Berlin",
                    city = "Potsdam",
                    datacenterName = "DC1",
                    latitude = BigDecimal("52.52"),
                    longitude = BigDecimal("13.405"),
                ),
            )
            val grown = client.updateTariff(tariff.id, tariffWrite("VPS-1", vcpuCount = 4))

            assertEquals(listOf(location.id), links)
            assertTrue(price.appended)
            assertEquals("Acme Hosting", provider.name, "the create response carries what was created")
            assertEquals("Acme Hosting Renamed", renamed.name)
            assertEquals("Potsdam", moved.city)
            assertEquals(4, grown.vcpuCount)

            val detail = client.getProvider(provider.id)
            assertEquals("Acme Hosting Renamed", detail.provider.name, "the update was stored, not just echoed")
            assertEquals("Acme Ltd", detail.legalInfo?.legalEntityName)
            assertEquals("CRAWLER", detail.legalInfo?.dataSource)
            assertEquals("LOW", detail.legalInfo?.confidence)
            assertEquals("https://acme.example/legal", detail.legalInfo?.sourceUrl)
            assertEquals(
                listOf(CatalogPaymentMethod("CRYPTO_BTC"), CatalogPaymentMethod("VISA_MASTERCARD", "Visa only")),
                detail.paymentMethods.sortedBy { it.method },
            )
            val storedLocation = detail.locations.single()
            assertEquals("DC1", storedLocation.datacenterName)
            assertEquals("Potsdam", storedLocation.city)
            assertEquals(0, storedLocation.latitude!!.compareTo(BigDecimal("52.52")))
            assertEquals(0, storedLocation.longitude!!.compareTo(BigDecimal("13.405")))
            val storedTariff = detail.tariffs.single().tariff
            assertEquals("VPS-1", storedTariff.name)
            assertEquals(4, storedTariff.vcpuCount)
            assertEquals("NVME", storedTariff.diskType)
            assertTrue(storedTariff.isActive && storedTariff.inStock)

            // Fields the read API does not expose: the frozen provenance of the price row.
            val stored = transaction(PostgresSupport.database) {
                PriceRepository().findCurrent(tariff.id, BillingPeriod.MONTHLY)
            }!!
            assertEquals("10.5000", stored.amount.toPlainString(), "NUMERIC(12,4), the exact decimal that was sent")
            assertEquals(PriceDataSource.CRAWLER, stored.dataSource)
            assertEquals(harness.serviceUserId, stored.recordedBy)
            val legal = transaction(PostgresSupport.database) { ProviderRepository().legalInfo(provider.id) }!!
            assertEquals(LegalDataSource.CRAWLER, legal.dataSource)
            assertEquals(Confidence.LOW, legal.confidence)
        }
    }

    @Test
    fun `a repeated price is reported as unchanged and a rate-less currency as a NO_FX_RATE counter`() {
        withCatalog { client, _ ->
            val provider = client.createProvider(providerWrite("acme"))
            val tariff = client.createTariff(provider.id, tariffWrite("VPS-1"))

            val first = client.putPrice(tariff.id, PriceWrite("MONTHLY", BigDecimal("10.50"), "USD", "CRAWLER"))
            val second = client.putPrice(tariff.id, PriceWrite("MONTHLY", BigDecimal("10.50"), "USD", "CRAWLER"))
            // No FX rate was ever ingested in this test, so a non-USD price cannot be converted.
            val euro = client.putPrice(tariff.id, PriceWrite("ANNUAL", BigDecimal("99.00"), "EUR", "CRAWLER"))

            assertEquals(true, first.appended, "the first write appends a history row")
            assertEquals(false, second.appended, "an identical re-submission writes nothing")
            assertEquals(false, first.noFxRate)
            assertEquals(true, euro.appended)
            assertEquals(true, euro.noFxRate, "surfaced as a counter, not an exception")

            val rows = transaction(PostgresSupport.database) {
                PriceRepository().history(tariff.id, null, null, null)
            }
            assertEquals(2, rows.size, "the repeated price must not have appended a second MONTHLY row")
        }
    }

    @Test
    fun `a duplicate slug and a duplicate location are Conflict, not an aborting failure`() {
        withCatalog { client, _ ->
            val provider = client.createProvider(providerWrite("acme"))
            val duplicateSlug = assertFailsWith<CatalogApiException.Conflict> {
                client.createProvider(providerWrite("acme"))
            }
            assertEquals("/api/v1/providers", duplicateSlug.path)
            assertTrue(duplicateSlug.apiMessage.isNotBlank(), "the envelope message is what the reconciler logs")

            val location = LocationWrite(countryCode = "DE", city = "Berlin", datacenterName = "DC1")
            client.createLocation(provider.id, location)
            val duplicateLocation = assertFailsWith<CatalogApiException.Conflict> {
                client.createLocation(provider.id, location)
            }
            assertEquals("/api/v1/providers/${provider.id}/locations", duplicateLocation.path)

            assertEquals("acme", client.findProviderBySlug("acme")?.slug)
        }
    }

    @Test
    fun `a 400 is a ClientError carrying the envelope details and is logged without the request body`() {
        val logs = capturingLogs {
            withCatalog { client, _ ->
                val provider = client.createProvider(providerWrite("acme"))
                val failure = assertFailsWith<CatalogApiException.ClientError> {
                    client.createTariff(provider.id, tariffWrite("BODY-MARKER-TARIFF", vcpuCount = 0))
                }

                assertEquals(400, failure.status)
                assertEquals("/api/v1/providers/${provider.id}/tariffs", failure.path)
                assertEquals("Request validation failed", failure.apiMessage)
                assertEquals(listOf("vcpuCount"), failure.details.map { it.field })
            }
        }

        val warnings = logs.messagesAt(Level.WARN)
        assertEquals(1, warnings.count { it.contains("-> 400") }, "exactly one WARN for the failed call")
        val warning = warnings.single { it.contains("-> 400") }
        assertTrue(warning.contains("Request validation failed") && warning.contains("vcpuCount"), warning)
        assertTrue(!warning.contains("BODY-MARKER-TARIFF"), "the request body must not be logged: $warning")
    }

    @Test
    fun `a 503 is retried within the budget and surfaces as ServerError once it is spent`() {
        val waits = mutableListOf<Long>()
        // Full bound instead of a random wait, so the backoff schedule is assertable.
        withCatalog(backoffJitter = { bound -> bound }, sleep = { millis -> waits += millis }) { client, harness ->
            val created = client.createProvider(providerWrite("acme"))
            harness.clearRecordedCalls()

            harness.failNextCatalogCalls(times = 1)
            assertEquals(created.id, client.findProviderBySlug("acme")?.id, "a single 503 is retried, not surfaced")
            assertEquals(2, harness.countCalls("GET", "/api/v1/internal/catalog/providers"), "the rejected attempt plus one retry")
            assertEquals(listOf(1_000L), waits)

            harness.clearRecordedCalls()
            waits.clear()
            harness.failNextCatalogCalls(times = Int.MAX_VALUE)
            val failure = assertFailsWith<CatalogApiException.ServerError> { client.findProviderBySlug("acme") }

            assertEquals(503, failure.status)
            assertEquals("/api/v1/internal/catalog/providers", failure.path)
            assertEquals(3, harness.countCalls("GET", "/api/v1/internal/catalog/providers"), "one attempt and exactly two retries")
            assertEquals(listOf(1_000L, 2_000L), waits, "base 1 s doubling, the same budget as the transport path")
        }
    }

    @Test
    fun `a closed port fails after exactly two retries with the configured backoff`() {
        val waits = mutableListOf<Long>()
        CrawlerAppHarness(clock).use { harness ->
            val config = harness.crawlerConfig(apiBaseUrl = "http://127.0.0.1:${closedLoopbackPort()}")
            CatalogApiClient(
                config = config,
                clock = clock,
                limits = FAST_LIMITS,
                // Full bound instead of a random wait, so the backoff schedule is assertable.
                backoffJitter = { bound -> bound },
                sleep = { millis -> waits += millis },
            ).use { client ->
                val failure = assertFailsWith<CatalogApiException.TransportFailure> {
                    runBlocking { client.findProviderBySlug("acme") }
                }
                assertEquals("/api/v1/auth/login", failure.path)
            }
            assertEquals(listOf(1_000L, 2_000L), waits, "two retries, base 1 s doubling")
            assertEquals(emptyList(), harness.calls(), "nothing reached the real server")
        }
    }

    private fun withCatalog(
        pageSize: Int = 100,
        config: (CrawlerAppHarness) -> CrawlerConfig = { it.crawlerConfig() },
        backoffJitter: (Long) -> Long = { 0L },
        sleep: suspend (Long) -> Unit = { },
        block: suspend (CatalogApiClient, CrawlerAppHarness) -> Unit,
    ) = CrawlerAppHarness(clock).use { harness ->
        CatalogApiClient(
            config = config(harness),
            clock = clock,
            limits = FAST_LIMITS,
            pageSize = pageSize,
            backoffJitter = backoffJitter,
            sleep = sleep,
        ).use { client -> runBlocking { block(client, harness) } }
    }

    private fun causeChain(error: Throwable): String =
        generateSequence<Throwable>(error) { current -> current.cause?.takeIf { it !== current } }
            .joinToString("\n") { it.toString() }

    private fun providerWrite(slug: String, name: String = "Provider $slug") = ProviderWrite(
        slug = slug,
        name = name,
        websiteUrl = "https://$slug.example",
        status = "ACTIVE",
    )

    private fun legalInfoWrite() = LegalInfoWrite(
        dataSource = "CRAWLER",
        confidence = "LOW",
        legalEntityName = "Acme Ltd",
        sourceUrl = "https://acme.example/legal",
    )

    private fun tariffWrite(name: String, vcpuCount: Int = 2) = TariffWrite(
        name = name,
        vcpuCount = vcpuCount,
        ramMb = 2048,
        diskGb = 40,
        diskType = DiskType.NVME.name,
        ipv4Count = 1,
        virtualization = Virtualization.KVM.name,
        isActive = true,
        inStock = true,
    )

    private companion object {
        /** Production retry budget, shorter timeouts: a loopback call that is going to fail, fails fast. */
        val FAST_LIMITS = CatalogClientLimits(
            connectTimeoutMs = 1_000,
            requestTimeoutMs = 5_000,
            socketTimeoutMs = 5_000,
        )
    }
}
