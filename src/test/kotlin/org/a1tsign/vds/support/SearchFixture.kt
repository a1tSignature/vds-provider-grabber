package org.a1tsign.vds.support

import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Deterministic dataset shared by [org.a1tsign.vds.api.ProviderReadApiTest] (task vds-001.15) and
 * task 16's cross-provider tariff search test. Built directly through [ProviderRepository] and
 * [CatalogFixture] against one fixed base instant -- there is no clock to advance because every
 * price row here is inserted directly with an explicit `effective_to`, never through
 * [org.a1tsign.vds.service.PriceService]'s two-step close-then-insert that the "only one current
 * row per (tariff, period)" rule would otherwise require a clock advance between.
 *
 * Six providers, one per edge case `minMonthlyPriceUsd`/the filters have to get right:
 * - [nimbus]: several tariffs; the cheapest *current* MONTHLY price wins (9.00 USD), including one
 *   tariff with a **closed** MONTHLY row (20.00) superseded by a newer current one (9.00), and one
 *   tariff whose only price is ANNUAL (never mixed with MONTHLY).
 * - [polaris]: the cheapest tariff (3.00) is `is_active = false` and excluded; the next (15.00,
 *   `in_stock = false`) wins -- proving `in_stock` never affects the aggregate.
 * - [vertex]: the cheapest current MONTHLY price (on an active tariff) has `amount_usd = NULL` (no
 *   FX rate) and is ignored; the next cheapest (18.00) wins. `status = INACTIVE`.
 * - [wayfarer]: one active tariff has only an ANNUAL price, another active tariff has no price row
 *   at all -- `minMonthlyPriceUsd` is absent/null. `status = UNKNOWN`.
 * - [zephyr]: single active tariff, the cheapest MONTHLY price in the whole dataset (5.00); no
 *   legal info at all (`registrationCountry` absent) and no locations.
 * - [dormant]: its only tariff is `is_active = false` -- the one `hasActiveTariffs = false` case in
 *   the dataset; `minMonthlyPriceUsd` is absent/null.
 *
 * Locations: DE (nimbus, polaris -- the "two providers share a country" case), US (nimbus,
 * dormant), FR (vertex), GB (wayfarer); zephyr has none.
 *
 * Payment methods (AND-filter subset proof): PAYPAL -> {nimbus, polaris, zephyr}; CRYPTO_BTC ->
 * {nimbus, vertex, zephyr}; both together -> exactly {nimbus, zephyr}, a strict subset of each.
 *
 * `q` marker: "Quasar" appears only in [nimbus]'s *name*; "quasar" appears only in [polaris]'s
 * *slug* -- `q=quasar` (any case) must match both, proving the filter checks name OR slug.
 */
class SearchFixture(private val database: Database) {
    private val providers = ProviderRepository()
    private val catalog = CatalogFixture(database, providers)
    private val tariffsRepo = TariffRepository()
    private val base: Instant = Instant.parse("2024-01-01T00:00:00Z")

    val nimbus: Provider = createProvider("nimbus-cloud", "Nimbus Hosting Quasar", ProviderStatus.ACTIVE)
    val polaris: Provider = createProvider("polaris-quasar-net", "Polaris Systems", ProviderStatus.ACTIVE)
    val vertex: Provider = createProvider("search-vertex", "Vertex Hosting", ProviderStatus.INACTIVE)
    val wayfarer: Provider = createProvider("search-wayfarer", "Compass Networks", ProviderStatus.UNKNOWN)
    val zephyr: Provider = createProvider("search-zephyr", "Zephyr Networks", ProviderStatus.ACTIVE)
    val dormant: Provider = createProvider("search-dormant", "Dormant Systems", ProviderStatus.ACTIVE)

    init {
        createLegalInfo(nimbus.id, "US")
        createLegalInfo(polaris.id, "DE")
        createLegalInfo(vertex.id, "FR")
        createLegalInfo(wayfarer.id, "GB")
        // zephyr: deliberately no legal info -- registrationCountry must be absent, not a value.
        createLegalInfo(dormant.id, "US")

        catalog.createLocation(nimbus.id, "DE", at = base)
        catalog.createLocation(nimbus.id, "US", at = base)
        catalog.createLocation(polaris.id, "DE", at = base)
        catalog.createLocation(vertex.id, "FR", at = base)
        catalog.createLocation(wayfarer.id, "GB", at = base)
        catalog.createLocation(dormant.id, "US", at = base)
        // zephyr: deliberately no locations of its own (task 16 adds BR/JP ones below).

        catalog.createPaymentMethods(
            nimbus.id,
            listOf(PaymentMethod.PAYPAL to null, PaymentMethod.CRYPTO_BTC to null, PaymentMethod.BANK_WIRE to null),
        )
        catalog.createPaymentMethods(polaris.id, listOf(PaymentMethod.PAYPAL to null, PaymentMethod.CRYPTO_ETH to null))
        catalog.createPaymentMethods(
            vertex.id,
            listOf(PaymentMethod.CRYPTO_BTC to null, PaymentMethod.CRYPTO_ETH to null, PaymentMethod.SBP to null),
        )
        catalog.createPaymentMethods(wayfarer.id, listOf(PaymentMethod.BANK_WIRE to null, PaymentMethod.CASH to null))
        catalog.createPaymentMethods(
            zephyr.id,
            listOf(
                PaymentMethod.CRYPTO_BTC to null, PaymentMethod.PAYPAL to null,
                PaymentMethod.CRYPTO_ETH to null, PaymentMethod.BANK_WIRE to null,
            ),
        )
        catalog.createPaymentMethods(dormant.id, listOf(PaymentMethod.CASH to null))

        // nimbus: 3 tariffs -- min current MONTHLY = 9.00 (Nimbus Pro's current row).
        val nimbusBasic = catalog.createTariff(nimbus.id, "Nimbus Basic", base, vcpuCount = 1, ramMb = 1024, diskGb = 20, diskType = DiskType.SSD)
        catalog.createTariffPrice(nimbusBasic.id, BigDecimal("12.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("12.0000"))
        val nimbusPro = catalog.createTariff(nimbus.id, "Nimbus Pro", base, vcpuCount = 4, ramMb = 8192, diskGb = 160, diskType = DiskType.NVME)
        catalog.createTariffPrice(
            nimbusPro.id, BigDecimal("18.00"), "EUR", BillingPeriod.MONTHLY, base,
            amountUsd = BigDecimal("20.0000"), effectiveTo = base.plus(Duration.ofDays(1)),
        )
        catalog.createTariffPrice(
            nimbusPro.id, BigDecimal("9.00"), "USD", BillingPeriod.MONTHLY, base.plus(Duration.ofDays(1)),
            amountUsd = BigDecimal("9.0000"),
        )
        val nimbusArchive = catalog.createTariff(nimbus.id, "Nimbus Archive", base, vcpuCount = 1, ramMb = 512, diskGb = 10, diskType = DiskType.HDD)
        catalog.createTariffPrice(nimbusArchive.id, BigDecimal("100.00"), "USD", BillingPeriod.ANNUAL, base, amountUsd = BigDecimal("100.0000"))

        // polaris: cheapest (3.00) is inactive and excluded; the active 15.00 is in_stock = false and still counts.
        val polarisMini = catalog.createTariff(
            polaris.id, "Polaris Mini", base, vcpuCount = 1, ramMb = 512, diskGb = 10, diskType = DiskType.HDD, isActive = false,
        )
        catalog.createTariffPrice(polarisMini.id, BigDecimal("3.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("3.0000"))
        val polarisStandard = catalog.createTariff(
            polaris.id, "Polaris Standard", base, vcpuCount = 2, ramMb = 4096, diskGb = 80, diskType = DiskType.SSD, inStock = false,
        )
        catalog.createTariffPrice(polarisStandard.id, BigDecimal("15.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("15.0000"))

        // vertex: cheapest (5.00) has no FX rate and is ignored; 18.00 wins.
        val vertexEdge = catalog.createTariff(vertex.id, "Vertex Edge", base, vcpuCount = 1, ramMb = 2048, diskGb = 40, diskType = DiskType.SSD)
        catalog.createTariffPrice(vertexEdge.id, BigDecimal("5.00"), "EUR", BillingPeriod.MONTHLY, base)
        val vertexCore = catalog.createTariff(vertex.id, "Vertex Core", base, vcpuCount = 3, ramMb = 6144, diskGb = 100, diskType = DiskType.NVME)
        catalog.createTariffPrice(vertexCore.id, BigDecimal("18.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("18.0000"))

        // wayfarer: ANNUAL-only tariff + an active tariff with no price row at all -> no MONTHLY price anywhere.
        val wayfarerAnnual = catalog.createTariff(wayfarer.id, "Wayfarer Annual", base, vcpuCount = 2, ramMb = 2048, diskGb = 50, diskType = DiskType.SSD)
        catalog.createTariffPrice(wayfarerAnnual.id, BigDecimal("120.00"), "USD", BillingPeriod.ANNUAL, base, amountUsd = BigDecimal("120.0000"))
        catalog.createTariff(wayfarer.id, "Wayfarer Empty", base, vcpuCount = 1, ramMb = 1024, diskGb = 20, diskType = DiskType.SSD)

        // zephyr: single tariff, the cheapest MONTHLY price in the whole dataset.
        val zephyrPrime = catalog.createTariff(zephyr.id, "Zephyr Prime", base, vcpuCount = 2, ramMb = 2048, diskGb = 40, diskType = DiskType.SSD)
        catalog.createTariffPrice(zephyrPrime.id, BigDecimal("5.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("5.0000"))

        // dormant: its only tariff is inactive -- hasActiveTariffs = false, minMonthlyPriceUsd absent.
        val dormantPlan = catalog.createTariff(
            dormant.id, "Dormant Plan", base, vcpuCount = 1, ramMb = 1024, diskGb = 20, diskType = DiskType.SSD, isActive = false,
        )
        catalog.createTariffPrice(dormantPlan.id, BigDecimal("7.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("7.0000"))
    }

    // ---- Task vds-001.16 additions: cross-provider tariff search + price history ---------------
    // Extra tariffs/locations under the EXISTING [zephyr] provider, never a new provider -- a new
    // provider would shift task 15's exact-set assertions on the *provider* list
    // (`hasActiveTariffs`, `minMonthlyPriceUsd` ranking, `locationCountry`). Every new price here
    // stays above zephyr's existing 5.00 minimum so `minMonthlyPriceUsd` for zephyr is untouched,
    // and BR/JP are country codes no task 15 assertion ever filters by. Looked up by name through
    // the `/tariffs` response in the task 16 tests, so -- unlike the providers -- not exposed as
    // fixture properties.
    init {
        val zephyrBr = catalog.createLocation(zephyr.id, "BR", at = base)
        val zephyrJp1 = catalog.createLocation(zephyr.id, "JP", at = base)
        val zephyrJp2 = catalog.createLocation(zephyr.id, "JP", city = "Osaka", at = base)

        // Zeta Alpha / Zeta Beta: both BR, both MONTHLY 10.00 -- the `id ASC` tie-break pair.
        val zetaAlpha = catalog.createTariff(zephyr.id, "Zeta Alpha", base, vcpuCount = 1, ramMb = 512, diskGb = 10, diskType = DiskType.HDD)
        catalog.linkTariffLocation(zetaAlpha.id, zephyrBr.id, zephyr.id)
        catalog.createTariffPrice(zetaAlpha.id, BigDecimal("10.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("10.0000"))

        // Metered (the one unmeteredTraffic=false case) -- createTariff always leaves
        // trafficLimitGb null, so this one goes through TariffRepository directly.
        val zetaBeta = transaction(database) {
            tariffsRepo.insert(
                zephyr.id, null, "Zeta Beta", null, 2, null, 2048, 100, DiskType.SSD,
                null, 500, 1, null, Virtualization.KVM, true, true, base,
            )
        }
        catalog.linkTariffLocation(zetaBeta.id, zephyrBr.id, zephyr.id)
        catalog.createTariffPrice(zetaBeta.id, BigDecimal("10.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("10.0000"))

        // Zeta Gamma: JP, above every min* threshold, unmetered, MONTHLY 30.00.
        val zetaGamma = catalog.createTariff(zephyr.id, "Zeta Gamma", base, vcpuCount = 3, ramMb = 4096, diskGb = 200, diskType = DiskType.SSD)
        catalog.linkTariffLocation(zetaGamma.id, zephyrJp1.id, zephyr.id)
        catalog.createTariffPrice(zetaGamma.id, BigDecimal("30.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("30.0000"))

        // Zeta Delta: linked to BOTH Japanese locations -- must appear once for country=JP. No
        // MONTHLY price row at all -- the "no current price" null-sort case.
        val zetaDelta = catalog.createTariff(zephyr.id, "Zeta Delta", base, vcpuCount = 8, ramMb = 16384, diskGb = 500, diskType = DiskType.NVME)
        catalog.linkTariffLocation(zetaDelta.id, zephyrJp1.id, zephyr.id)
        catalog.linkTariffLocation(zetaDelta.id, zephyrJp2.id, zephyr.id)

        // Zeta Epsilon: JP, a current MONTHLY price with no FX rate (amount_usd = NULL) -- the
        // other null-sort case, distinct from Delta's "no row at all".
        val zetaEpsilon = catalog.createTariff(zephyr.id, "Zeta Epsilon", base, vcpuCount = 1, ramMb = 512, diskGb = 10, diskType = DiskType.UNKNOWN)
        catalog.linkTariffLocation(zetaEpsilon.id, zephyrJp1.id, zephyr.id)
        catalog.createTariffPrice(zetaEpsilon.id, BigDecimal("5.00"), "EUR", BillingPeriod.MONTHLY, base)

        // Zeta History: a crafted MONTHLY price history for the overlap/`billingPeriod`-narrowing
        // tests -- rows 1-3 are contiguous and closed, row 4 is current/open-ended after a
        // deliberate gap (a fixture inserted directly, not through PriceService, has no
        // contiguity to preserve), and the ANNUAL row proves periods are never mixed. Its current
        // MONTHLY price (40.00) stays above zephyr's 5.00 minimum.
        val zetaHistory = catalog.createTariff(zephyr.id, "Zeta History", base, diskType = DiskType.NVME)
        catalog.createTariffPrice(
            zetaHistory.id, BigDecimal("10.00"), "USD", BillingPeriod.MONTHLY, base,
            amountUsd = BigDecimal("10.0000"), effectiveTo = base.plus(Duration.ofDays(10)),
        )
        catalog.createTariffPrice(
            zetaHistory.id, BigDecimal("20.00"), "USD", BillingPeriod.MONTHLY, base.plus(Duration.ofDays(10)),
            amountUsd = BigDecimal("20.0000"), effectiveTo = base.plus(Duration.ofDays(20)),
        )
        catalog.createTariffPrice(
            zetaHistory.id, BigDecimal("30.00"), "USD", BillingPeriod.MONTHLY, base.plus(Duration.ofDays(20)),
            amountUsd = BigDecimal("30.0000"), effectiveTo = base.plus(Duration.ofDays(30)),
        )
        catalog.createTariffPrice(
            zetaHistory.id, BigDecimal("40.00"), "USD", BillingPeriod.MONTHLY, base.plus(Duration.ofDays(40)),
            amountUsd = BigDecimal("40.0000"),
        )
        catalog.createTariffPrice(
            zetaHistory.id, BigDecimal("500.00"), "USD", BillingPeriod.ANNUAL, base.plus(Duration.ofDays(50)),
            amountUsd = BigDecimal("500.0000"),
        )

        // Zeta Empty: a tariff with zero price rows at all -- the price-history "empty list" case.
        catalog.createTariff(zephyr.id, "Zeta Empty", base, diskType = DiskType.HDD)
    }

    private fun createProvider(slug: String, name: String, status: ProviderStatus): Provider = transaction(database) {
        providers.insert(slug, name, "https://$slug.example", null, status, base)
    }

    private fun createLegalInfo(providerId: Long, registrationCountry: String) = transaction(database) {
        providers.upsertLegalInfo(
            providerId, "Example Ltd", registrationCountry, "REG-$providerId", null,
            LegalDataSource.MANUAL, Confidence.HIGH, null, null, base,
        )
    }
}
