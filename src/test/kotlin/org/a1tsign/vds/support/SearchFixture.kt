package org.a1tsign.vds.support

import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Shared provider-list, tariff-search and price-history dataset. Include missing relationships, stock and activity filters, unknown FX, ties and history gaps. */
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

        // polaris: cheapest (3.00) is inactive, the 15.00 is out of stock -- neither counts.
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

        val zephyrPrime = catalog.createTariff(zephyr.id, "Zephyr Prime", base, vcpuCount = 2, ramMb = 2048, diskGb = 40, diskType = DiskType.SSD)
        catalog.createTariffPrice(zephyrPrime.id, BigDecimal("5.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("5.0000"))

        val dormantPlan = catalog.createTariff(
            dormant.id, "Dormant Plan", base, vcpuCount = 1, ramMb = 1024, diskGb = 20, diskType = DiskType.SSD, isActive = false,
        )
        catalog.createTariffPrice(dormantPlan.id, BigDecimal("7.00"), "USD", BillingPeriod.MONTHLY, base, amountUsd = BigDecimal("7.0000"))
    }

    // Zephyr's additional tariffs exercise country-link deduplication, filters, sorting and history overlap.
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
