package org.a1tsign.vds.crawler.write

import org.a1tsign.vds.domain.SystemStatus
import java.util.concurrent.atomic.AtomicLong

/** How many planned-operation lines one run keeps; the rest are dropped. */
const val PLANNED_OPERATIONS_LIMIT: Int = 200

/**
 * The preview [CrawlWriter]: performs no mutation, records one line per intended mutation, and
 * returns ids that let planning continue as if the write had happened.
 *
 * - A created entity gets one synthetic id per catalog identity (slug, location unique key, tariff
 *   `externalId` or lower-cased name), so follow-up links and prices are planned against it. The
 *   ids are negative so one that leaked into a URL could not address a real row.
 * - [putPrice] reports `appended = true` (whether the server would answer `200` is unknowable) and
 *   `noFxRate = false` (the FX state is not consulted without a write).
 * - Lines are capped at [PLANNED_OPERATIONS_LIMIT] to bound the in-memory run history; counters
 *   are unaffected.
 *
 * One instance per run, shared by its adapters. The lock lets an HTTP thread read the summary while
 * the run writes it; adapters themselves run one at a time.
 */
class DryRunWriter : CrawlWriter {

    private val lock = Any()
    private val ids = AtomicLong()
    private val fabricated = mutableMapOf<String, Long>()
    private val tariffNames = mutableMapOf<Long, String>()
    private val operations = mutableListOf<String>()

    @Volatile
    private var providerSlug: String = "?"

    override suspend fun beginProvider(slug: String) {
        providerSlug = slug
    }

    override fun plannedOperations(): List<String> = synchronized(lock) { operations.toList() }

    override suspend fun createProvider(request: ProviderWrite): Long {
        record("CREATE_PROVIDER provider=${request.slug} name=${request.name}")
        return fabricateId("provider:${request.slug}")
    }

    override suspend fun updateProvider(providerId: Long, request: ProviderWrite) {
        record("UPDATE_PROVIDER provider=${request.slug} name=${request.name} status=${request.status}")
    }

    override suspend fun putLegalInfo(providerId: Long, request: LegalInfoWrite) {
        record("PUT_LEGAL_INFO provider=$providerSlug entity=${request.legalEntityName ?: "-"}")
    }

    override suspend fun putPaymentMethods(providerId: Long, methods: List<PaymentMethodWrite>) {
        val rendered = if (methods.isEmpty()) "<none>" else methods.joinToString(",") { it.method }
        record("PUT_PAYMENT_METHODS provider=$providerSlug methods=$rendered")
    }

    override suspend fun createLocation(providerId: Long, request: LocationWrite): Long {
        record("CREATE_LOCATION provider=$providerSlug ${renderLocation(request)}")
        return fabricateId("location:$providerId:${locationKey(request)}")
    }

    override suspend fun updateLocation(locationId: Long, request: LocationWrite) {
        record("UPDATE_LOCATION provider=$providerSlug ${renderLocation(request)}")
    }

    override suspend fun createTariff(providerId: Long, request: TariffWrite): Long {
        record("CREATE_TARIFF provider=$providerSlug name=${request.name}")
        val id = fabricateId("tariff:$providerId:${request.externalId ?: request.name.lowercase()}")
        synchronized(lock) { tariffNames[id] = request.name }
        return id
    }

    override suspend fun updateTariff(tariffId: Long, request: TariffWrite) {
        synchronized(lock) { tariffNames[tariffId] = request.name }
        record(
            "UPDATE_TARIFF provider=$providerSlug name=${request.name} " +
                "inStock=${request.inStock} isActive=${request.isActive}",
        )
    }

    override suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>) {
        record("REPLACE_TARIFF_LOCATIONS provider=$providerSlug tariff=${tariffLabel(tariffId)} count=${locationIds.size}")
    }

    override suspend fun putPrice(tariffId: Long, request: PriceWrite): PriceOutcome {
        record(
            "PUT_PRICE provider=$providerSlug tariff=${tariffLabel(tariffId)} " +
                "period=${request.billingPeriod} amount=${request.amount.toPlainString()} currency=${request.currency}",
        )
        return PriceOutcome(appended = true, noFxRate = false)
    }

    override suspend fun promoteProviderToConfigured(providerId: Long) {
        record("PROMOTE_PROVIDER provider=$providerSlug systemStatus=${SystemStatus.CONFIGURED.name}")
    }

    /**
     * Mirrors the catalog's apply rules against [current]: a legal field is planned unless it is
     * `MANUAL` or would not change, a payment method or country only when it is new at `HIGH` or
     * `MEDIUM`. Dismissals are not visible here, so the count is an upper bound.
     */
    override suspend fun submitEnrichment(providerId: Long, request: EnrichmentApplyWrite, current: CatalogProviderDetail): Int {
        if (request.outcome != SUCCESS_OUTCOME) {
            record("ENRICH provider=$providerSlug outcome=${request.outcome}")
            return 0
        }
        val legal = current.legalInfo
        val observed = request.observedFacets.toSet()
        val planned = mutableListOf<String>()
        fun planLegal(field: String, finding: LegalFindingWrite?, currentValue: String?, currentOrigin: String?) {
            if (finding == null || field in observed || currentOrigin == MANUAL_ORIGIN) return
            val origin = if (finding.status == CONFIRMED_STATUS) "AUTO_CONFIRMED" else "AUTO_PRESUMED"
            if (finding.value == currentValue && origin == currentOrigin) return
            planned += "${legalFieldName(field)}=${finding.value} ($origin)"
        }
        planLegal(LEGAL_ENTITY_FACET, request.legalEntityName, legal?.legalEntityName, legal?.legalEntityNameOrigin)
        planLegal(REGISTRATION_COUNTRY_FACET, request.registrationCountry, legal?.registrationCountry, legal?.registrationCountryOrigin)
        if (PAYMENT_METHOD_FACET !in observed) {
            val existing = current.paymentMethods.map { it.method }.toSet()
            request.paymentMethods.filter { it.confidence in WRITABLE_CONFIDENCES && it.value !in existing }
                .forEach { planned += "paymentMethod=${it.value} (AUTO)" }
        }
        if (LOCATION_FACET !in observed) {
            val existing = current.locations.map { it.countryCode }.toSet()
            request.locations.filter { it.confidence in WRITABLE_CONFIDENCES && it.value !in existing }
                .forEach { planned += "location=${it.value} (AUTO)" }
        }
        planned.forEach { record("ENRICH provider=$providerSlug $it") }
        return planned.size
    }

    private fun record(line: String) = synchronized(lock) {
        if (operations.size < PLANNED_OPERATIONS_LIMIT) operations += line
    }

    private fun fabricateId(key: String): Long = synchronized(lock) {
        fabricated.getOrPut(key) { -ids.incrementAndGet() }
    }

    /** The tariff's name once this run has seen it, otherwise its id. */
    private fun tariffLabel(tariffId: Long): String =
        synchronized(lock) { tariffNames[tariffId] } ?: "#$tariffId"

    private fun legalFieldName(facet: String): String =
        if (facet == LEGAL_ENTITY_FACET) "legalEntityName" else "registrationCountry"

    private fun renderLocation(request: LocationWrite): String =
        "country=${request.countryCode} city=${request.city ?: "-"} dc=${request.datacenterName ?: "-"}"

    /** Mirrors `uq_location_provider_country_city_dc`, so the same row keeps the same id. */
    private fun locationKey(request: LocationWrite): String =
        "${request.countryCode}|${request.city.orEmpty().lowercase()}|${request.datacenterName.orEmpty().lowercase()}"

    private companion object {
        const val SUCCESS_OUTCOME = "SUCCESS"
        const val CONFIRMED_STATUS = "CONFIRMED"
        const val MANUAL_ORIGIN = "MANUAL"
        const val LEGAL_ENTITY_FACET = "LEGAL_ENTITY_NAME"
        const val REGISTRATION_COUNTRY_FACET = "REGISTRATION_COUNTRY"
        const val PAYMENT_METHOD_FACET = "PAYMENT_METHOD"
        const val LOCATION_FACET = "LOCATION"
        val WRITABLE_CONFIDENCES = setOf("HIGH", "MEDIUM")
    }
}
