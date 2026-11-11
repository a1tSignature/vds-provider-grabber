package org.a1tsign.vds.support

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.db.TariffLocationTable
import org.a1tsign.vds.db.TariffPriceTable
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant

class CatalogFixture(private val database: Database, private val providers: ProviderRepository = ProviderRepository()) {
    private val locations = LocationRepository()
    private val tariffs = TariffRepository()
    fun createProvider(slug: String, at: Instant): Provider = transaction(database) {
        providers.insert(slug, "Provider $slug", "https://$slug.example", null, ProviderStatus.ACTIVE, at)
    }
    fun createLegalInfo(providerId: Long, at: Instant): LegalInfo = transaction(database) {
        providers.upsertLegalInfo(providerId, "Example Ltd", "US", "123", null, LegalDataSource.MANUAL, Confidence.HIGH, null, null, at)
        providers.legalInfo(providerId)!!
    }
    fun createPaymentMethods(providerId: Long, methods: List<Pair<PaymentMethod, String?>>) = transaction(database) {
        providers.replacePaymentMethods(providerId, methods)
    }
    fun createLocation(providerId: Long, countryCode: String, city: String? = null, datacenterName: String? = null, at: Instant): Location = transaction(database) {
        locations.insert(providerId, countryCode, null, city, datacenterName, null, null, at)
    }

    fun createTariff(
        providerId: Long,
        name: String,
        at: Instant,
        externalId: String? = null,
        vcpuCount: Short = 1,
        ramMb: Int = 1024,
        diskGb: Int = 20,
        diskType: DiskType = DiskType.SSD,
        virtualization: Virtualization = Virtualization.KVM,
        ipv4Count: Short = 1,
        isActive: Boolean = true,
        inStock: Boolean = true,
    ): Tariff = transaction(database) {
        tariffs.insert(
            providerId, externalId, name, null, vcpuCount, null, ramMb, diskGb, diskType,
            null, null, ipv4Count, null, virtualization, isActive, inStock, at,
        )
    }

    fun linkTariffLocation(tariffId: Long, locationId: Long, providerId: Long) = transaction(database) {
        TariffLocationTable.insert {
            it[TariffLocationTable.tariffId] = tariffId
            it[TariffLocationTable.locationId] = locationId
            it[TariffLocationTable.providerId] = providerId
        }
    }

    /**
     * Inserts one `tariff_price` row directly (bypassing `PriceService`). `amountUsd`/`effectiveTo`
     * default to `null` for the common case; pass them explicitly to seed a frozen USD value or a
     * closed row without needing a second write.
     */
    fun createTariffPrice(
        tariffId: Long,
        amount: BigDecimal,
        currency: String,
        billingPeriod: BillingPeriod,
        effectiveFrom: Instant,
        dataSource: PriceDataSource = PriceDataSource.MANUAL,
        amountUsd: BigDecimal? = null,
        effectiveTo: Instant? = null,
    ) = transaction(database) {
        TariffPriceTable.insert {
            it[TariffPriceTable.tariffId] = tariffId
            it[TariffPriceTable.billingPeriod] = billingPeriod.name
            it[TariffPriceTable.amount] = amount
            it[TariffPriceTable.currency] = currency
            it[TariffPriceTable.amountUsd] = amountUsd
            it[TariffPriceTable.usdRate] = null
            it[TariffPriceTable.usdRateAt] = null
            it[TariffPriceTable.effectiveFrom] = effectiveFrom.toUtcOffset()
            it[TariffPriceTable.effectiveTo] = effectiveTo?.toUtcOffset()
            it[TariffPriceTable.dataSource] = dataSource.name
            it[TariffPriceTable.recordedBy] = null
            it[TariffPriceTable.createdAt] = effectiveFrom.toUtcOffset()
            it[TariffPriceTable.lastConfirmedAt] = effectiveFrom.toUtcOffset()
        }
    }
}
