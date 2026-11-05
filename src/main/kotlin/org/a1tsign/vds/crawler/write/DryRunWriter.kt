package org.a1tsign.vds.crawler.write

import java.util.concurrent.atomic.AtomicLong

/** How many planned-operation lines one run keeps (spec req. 36); the rest are dropped. */
const val PLANNED_OPERATIONS_LIMIT: Int = 200

/**
 * The preview [CrawlWriter] (ADR-05): it performs **no** mutation at all, records one
 * operator-readable line per mutation the reconciler intended, and returns ids that let planning
 * continue as if the write had happened.
 *
 * Three properties make the preview realistic rather than decorative:
 *
 * - **Fabricated ids are consistent inside the run.** A created entity gets one synthetic id,
 *   memoised by what identifies it in the catalog (slug / the location unique index / the tariff's
 *   `externalId` or lower-cased name), so a second create of the same thing returns the same id and
 *   the follow-up operations (links, prices) are still planned against it. The ids are **negative**
 *   so one that escaped into a URL could not possibly address a real row -- but they never leave
 *   this class: the reconciler only hands them back to the writer, and the run summary reports
 *   counters and lines.
 * - **Counters come out identical to a real run's**, because they are produced by the reconciler
 *   from these return values; [putPrice] therefore reports `appended = true` (a dry run cannot know
 *   whether vds-001 would have answered `200`, and "would append" is the honest preview) and
 *   `noFxRate = false` (the FX state is not consulted without a write).
 * - **The line list is bounded** at [PLANNED_OPERATIONS_LIMIT]: a broken adapter claiming thousands
 *   of tariffs must not grow the in-memory run history. Dropping lines never affects the counters.
 *
 * One instance per run, shared by every adapter of it; [beginProvider] is what names the provider in
 * each line. All state is behind one lock, so an HTTP thread can read the summary while the run
 * writes it -- `CrawlerRunner` runs adapters one at a time (`VDS_CRAWLER_MAX_CONCURRENT_ADAPTERS > 1`
 * is refused at startup), so the lock is not there for concurrent adapters.
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

    private fun record(line: String) = synchronized(lock) {
        if (operations.size < PLANNED_OPERATIONS_LIMIT) operations += line
    }

    private fun fabricateId(key: String): Long = synchronized(lock) {
        fabricated.getOrPut(key) { -ids.incrementAndGet() }
    }

    /** The tariff's name once this run has seen it, otherwise the id it was read from the catalog by. */
    private fun tariffLabel(tariffId: Long): String =
        synchronized(lock) { tariffNames[tariffId] } ?: "#$tariffId"

    private fun renderLocation(request: LocationWrite): String =
        "country=${request.countryCode} city=${request.city ?: "-"} dc=${request.datacenterName ?: "-"}"

    /** Mirrors vds-001's `uq_location_provider_country_city_dc`, so the same row keeps the same id. */
    private fun locationKey(request: LocationWrite): String =
        "${request.countryCode}|${request.city.orEmpty().lowercase()}|${request.datacenterName.orEmpty().lowercase()}"
}
