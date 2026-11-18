package org.a1tsign.vds.crawler.support

import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.model.LegalInfoSnapshot
import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.PriceSnapshot
import org.a1tsign.vds.crawler.model.ProviderInfo
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Virtualization
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

const val FIXTURE_PROVIDER_SLUG: String = "acme"

val FIXTURE_NOW: Instant = Instant.parse("2026-11-03T12:00:00Z")

fun fixtureClock(now: Instant = FIXTURE_NOW): Clock = Clock.fixed(now, ZoneOffset.UTC)

class StubAdapter(
    override val id: String = FIXTURE_PROVIDER_SLUG,
    override val providerSlug: String = FIXTURE_PROVIDER_SLUG,
) : ProviderAdapter {
    override val sourceKind: SourceKind = SourceKind.HTML

    override suspend fun fetch(ctx: CrawlContext): ProviderSnapshot = error("not used")
}

fun fixtureAdapter(providerSlug: String = FIXTURE_PROVIDER_SLUG): ProviderAdapter =
    StubAdapter(providerSlug = providerSlug)

val FRANKFURT: LocationKey = LocationKey("DE", city = "Frankfurt", datacenterName = "FRA-1")
val AMSTERDAM: LocationKey = LocationKey("NL", city = "Amsterdam", datacenterName = "AMS-1")

/** Start from a valid snapshot so a mutation has one attributable failure. */
fun validSnapshot(): ProviderSnapshot = ProviderSnapshot(
    provider = ProviderInfo(
        slug = FIXTURE_PROVIDER_SLUG,
        name = "Acme Hosting",
        websiteUrl = "https://acme.example",
        description = "Reference provider",
        status = ProviderStatus.ACTIVE,
    ),
    legalInfo = LegalInfoSnapshot(
        legalEntityName = "Acme Hosting GmbH",
        registrationCountry = "DE",
        registrationNumber = "HRB 12345",
        confidence = Confidence.MEDIUM,
        sourceUrl = "https://acme.example/imprint",
    ),
    paymentMethods = setOf(PaymentMethod.VISA_MASTERCARD, PaymentMethod.CRYPTO_BTC),
    locations = listOf(
        LocationSnapshot(
            key = FRANKFURT,
            region = "Hesse",
            latitude = BigDecimal("50.110924"),
            longitude = BigDecimal("8.682127"),
        ),
        LocationSnapshot(key = AMSTERDAM),
    ),
    tariffs = listOf(
        validTariff(name = "VPS 1", externalId = "vps-1"),
        validTariff(name = "VPS 2", externalId = "vps-2", amount = BigDecimal("19.9900")),
    ),
    tariffListComplete = true,
    observedAt = FIXTURE_NOW,
    sourceUrls = listOf("https://acme.example/vps"),
)

fun validTariff(
    name: String,
    externalId: String? = null,
    amount: BigDecimal = BigDecimal("9.9900"),
): TariffSnapshot = TariffSnapshot(
    externalId = externalId,
    name = name,
    url = "https://acme.example/vps/${name.lowercase().replace(' ', '-')}",
    vcpuCount = 2,
    cpuModel = "EPYC 7443P",
    ramMb = 4096,
    diskGb = 80,
    diskType = DiskType.NVME,
    bandwidthMbps = 1000,
    trafficLimitGb = 4000,
    ipv4Count = 1,
    ipv6Supported = true,
    virtualization = Virtualization.KVM,
    locationKeys = listOf(AMSTERDAM),
    prices = listOf(
        PriceSnapshot(BillingPeriod.MONTHLY, amount, "EUR"),
        PriceSnapshot(BillingPeriod.ANNUAL, amount.multiply(BigDecimal("10")), "EUR"),
    ),
)

fun ProviderSnapshot.withTariff(index: Int, transform: (TariffSnapshot) -> TariffSnapshot): ProviderSnapshot =
    copy(tariffs = tariffs!!.mapIndexed { i, tariff -> if (i == index) transform(tariff) else tariff })

fun ProviderSnapshot.withLocation(index: Int, transform: (LocationSnapshot) -> LocationSnapshot): ProviderSnapshot =
    copy(locations = locations!!.mapIndexed { i, location -> if (i == index) transform(location) else location })

fun TariffSnapshot.withPrice(index: Int, transform: (PriceSnapshot) -> PriceSnapshot): TariffSnapshot =
    copy(prices = prices.mapIndexed { i, price -> if (i == index) transform(price) else price })

fun ProviderSnapshot.withoutLocationLinks(): ProviderSnapshot =
    copy(tariffs = tariffs?.map { it.copy(locationKeys = null) })
