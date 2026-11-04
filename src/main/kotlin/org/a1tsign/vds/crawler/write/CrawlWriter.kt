package org.a1tsign.vds.crawler.write

/**
 * The write boundary of the crawler (ADR-05): **one method per catalog mutation the reconciler is
 * allowed to perform**, and nothing else.
 *
 * Two implementations follow in task vds-002.5 -- `ApiCrawlWriter` (delegates to
 * [CatalogApiClient]) and `DryRunWriter` (records the intended mutation, fabricates consistent ids,
 * writes nothing) -- so the reconciler contains no `if (dryRun)` branch and cannot tell which one
 * it was given. Reads are **not** behind this interface: both modes read the real catalog through
 * [CatalogApiClient], because a preview against a fake catalog would be worthless.
 *
 * ### Why this interface is this small
 *
 * There is deliberately **no delete and no deactivate method**, and no method that takes a
 * "remove"/"deactivate" flag. The never-destroy rule of ADR-02/ADR-03 is therefore structurally
 * impossible to express here, instead of being a convention every call site has to remember. The
 * only disappearance signal a writer can carry is [TariffWrite.inStock] `false`, and
 * [TariffWrite.isActive] has no default precisely so that an update must echo the value the
 * reconciler just read from the catalog.
 *
 * Every method returns what the reconciler needs to keep going: the id of a created row, or the
 * created-vs-unchanged outcome of a price write. A failure is a [CatalogApiException] -- notably
 * [CatalogApiException.Conflict], which the reconciler absorbs by re-reading, and
 * [CatalogApiException.Unauthorized], which it must **not** swallow.
 *
 * The two non-mutating members ([beginProvider], [plannedOperations]) exist because a recording
 * writer has to produce an operator-readable plan out of calls that carry only ids: they are
 * no-ops for a writer that really writes, and they are what keeps `if (dryRun)` out of the
 * reconciler (task vds-002.5 report).
 */
interface CrawlWriter {

    /**
     * Declares that every following call belongs to the provider identified by [slug], until the
     * next [beginProvider]. A writer that really writes ignores it; a recording writer needs it
     * because the mutation methods below carry only ids, and `provider=acme` in a planned-operation
     * line is what makes the plan readable (ADR-05).
     */
    suspend fun beginProvider(slug: String) {}

    /**
     * The operator-readable lines of the mutations this writer **intended** instead of performing,
     * in call order and capped by the implementation. Empty for a writer that really writes, so the
     * reconciler reports it without knowing which writer it was handed.
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
}

/**
 * What a price write tells the reconciler's counters: `appended` feeds `pricesAppended`, its
 * negation `pricesUnchanged`, and [noFxRate] feeds `noFxRateCount` (a counter, never a failure).
 *
 * Narrower than [PriceWriteOutcome] on purpose: a dry-run writer has no stored row to return.
 */
data class PriceOutcome(val appended: Boolean, val noFxRate: Boolean)
