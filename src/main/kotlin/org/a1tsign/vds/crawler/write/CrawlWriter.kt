package org.a1tsign.vds.crawler.write

/**
 * Every catalog mutation the reconciler may perform, and nothing else. Implemented by
 * [ApiCrawlWriter] and [DryRunWriter], so the reconciler has no `if (dryRun)` branch. Reads are not
 * behind this interface: both modes read the real catalog through [CatalogApiClient].
 *
 * There is no delete or deactivate method, so destroying catalog data cannot be expressed here.
 * The only disappearance signal is [TariffWrite.inStock] `false`; [TariffWrite.isActive] has no
 * default so that an update must echo the catalog's current value.
 *
 * Failures are [CatalogApiException]s: the reconciler absorbs [CatalogApiException.Conflict] by
 * re-reading and must not swallow [CatalogApiException.Unauthorized].
 */
interface CrawlWriter {

    /**
     * Every following call belongs to the provider [slug], until the next [beginProvider]. Lets a
     * recording writer name the provider, since the mutation methods carry only ids.
     */
    suspend fun beginProvider(slug: String) {}

    /**
     * Lines for the mutations this writer recorded instead of performing, in call order and capped
     * by the implementation. Empty for a writer that really writes.
     */
    fun plannedOperations(): List<String> = emptyList()

    /** `POST /api/v1/providers` -> the new provider id. A duplicate slug is a `Conflict`. */
    suspend fun createProvider(request: ProviderWrite): Long

    /** `PUT /api/v1/providers/{id}` -- a full replace of the provider's own columns. */
    suspend fun updateProvider(providerId: Long, request: ProviderWrite)

    /** `PUT …/legal-info`. Called only for an observed (non-null) legal-info section. */
    suspend fun putLegalInfo(providerId: Long, request: LegalInfoWrite)

    /** `PUT …/payment-methods` -- replaces the whole set, so only for an observed set. */
    suspend fun putPaymentMethods(providerId: Long, methods: List<PaymentMethodWrite>)

    /** `POST …/locations` -> the new location id. The unique index makes a duplicate a `Conflict`. */
    suspend fun createLocation(providerId: Long, request: LocationWrite): Long

    /** `PUT /api/v1/locations/{id}`. */
    suspend fun updateLocation(locationId: Long, request: LocationWrite)

    /** `POST …/tariffs` -> the new tariff id. */
    suspend fun createTariff(providerId: Long, request: TariffWrite): Long

    /** `PUT /api/v1/tariffs/{id}`. [TariffWrite.isActive] must echo the catalog's current value. */
    suspend fun updateTariff(tariffId: Long, request: TariffWrite)

    /** `PUT /api/v1/tariffs/{id}/locations` -- replaces the whole link set. */
    suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>)

    /** `PUT /api/v1/tariffs/{id}/price` -> appended-vs-unchanged plus the `NO_FX_RATE` flag. */
    suspend fun putPrice(tariffId: Long, request: PriceWrite): PriceOutcome

    /**
     * `PUT /api/v1/providers/{id}/system-status {"systemStatus":"CONFIGURED"}`: promotes a
     * discovered `CANDIDATE` row. Takes no target value, so it cannot express a demotion.
     */
    suspend fun promoteProviderToConfigured(providerId: Long)
}

/** Narrower than [PriceWriteOutcome]: a dry-run writer has no stored row to return. */
data class PriceOutcome(val appended: Boolean, val noFxRate: Boolean)
