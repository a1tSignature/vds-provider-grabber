package org.a1tsign.vds.crawler.write

/** Catalog mutations used by the reconciler. Implementations apply writes or record an equivalent dry-run plan. */
interface CrawlWriter {

    /** Set the provider label for subsequent recorded operations until the next [beginProvider]. */
    suspend fun beginProvider(slug: String) {}

    /** Recorded mutations in call order, capped by the implementation. */
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

    suspend fun updateLocation(locationId: Long, request: LocationWrite)

    suspend fun createTariff(providerId: Long, request: TariffWrite): Long

    /** `PUT /api/v1/tariffs/{id}`. [TariffWrite.isActive] must echo the catalog's current value. */
    suspend fun updateTariff(tariffId: Long, request: TariffWrite)

    /** `PUT /api/v1/tariffs/{id}/locations` -- replaces the whole link set. */
    suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>)

    /** `PUT /api/v1/tariffs/{id}/price` -> appended-vs-unchanged plus the `NO_FX_RATE` flag. */
    suspend fun putPrice(tariffId: Long, request: PriceWrite): PriceOutcome

    /** Promote a CANDIDATE to CONFIGURED without exposing arbitrary status changes. */
    suspend fun promoteProviderToConfigured(providerId: Long)

    /**
     * `PUT /api/v1/providers/{id}/enrichment` -> how many values the catalog changed. [current] is
     * the provider as the enrichment step just read it; only a recording writer uses it, to
     * approximate that count without a write.
     */
    suspend fun submitEnrichment(providerId: Long, request: EnrichmentApplyWrite, current: CatalogProviderDetail): Int
}

/** Narrower than [PriceWriteOutcome]: a dry-run writer has no stored row to return. */
data class PriceOutcome(val appended: Boolean, val noFxRate: Boolean)
