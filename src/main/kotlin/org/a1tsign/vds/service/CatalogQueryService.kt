package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.api.dto.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.a1tsign.vds.api.ApiJson
import org.a1tsign.vds.db.EnrichmentRepository
import org.a1tsign.vds.db.LocationTable
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.db.MinPricesUsd
import org.a1tsign.vds.db.ProviderListFilter
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.TariffPriceTable
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.db.TariffSearchFilter
import org.a1tsign.vds.db.TariffTable
import org.a1tsign.vds.db.toLocation
import org.a1tsign.vds.db.toTariff
import org.a1tsign.vds.db.toTariffPrice
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Provider
import org.a1tsign.vds.domain.Tariff
import org.a1tsign.vds.domain.TariffPrice
import org.a1tsign.vds.domain.annualUsdPerMonth
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(CatalogQueryService::class.java)

private class SearchPriceOptions(requested: TariffPrice?, annual: TariffPrice?) {
    val annualUsdPerMonth = annual?.amountUsd?.annualUsdPerMonth()
    private val annualIsCheaper = annualUsdPerMonth?.let { annualUsd ->
        requested?.amountUsd?.let { annualUsd < it } ?: true
    } ?: false

    val primary = if (annualIsCheaper) annual else requested ?: annual
    val other = if (annualIsCheaper) requested else annual.takeIf { requested != null }
}

class CatalogQueryService(
    private val database: Database,
    private val providers: ProviderRepository,
    private val tariffs: TariffRepository,
    private val prices: PriceRepository,
    private val enrichmentState: EnrichmentRepository = EnrichmentRepository(),
) {
    /** Resolve related models in batches so query count does not grow with the page size. */
    suspend fun listProviders(
        filter: ProviderListFilter,
        page: Int,
        size: Int,
        sort: List<SortField>,
    ): Pair<Long, List<ProviderSummaryDto>> = withContext(Dispatchers.IO) {
        transaction(database) {
            val minPriceSort = sort.firstOrNull { it.field == "minMonthlyPriceUsd" }
            val total: Long
            val pageProviders: List<Provider>
            val minPriceForPage: Map<Long, MinPricesUsd>
            if (minPriceSort != null) {
                // Sort by the computed aggregate after resolving matching IDs. Paginating the IDs first would rank only part of the result.
                val allIds = providers.filteredIds(filter)
                total = allIds.size.toLong()
                val priceByAll = providers.minPricesUsdByProvider(allIds)
                val descending = minPriceSort.descending
                val orderedIds = allIds.sortedWith { a, b ->
                    val pa = priceByAll[a]?.lowest
                    val pb = priceByAll[b]?.lowest
                    val primary = when {
                        pa == null && pb == null -> 0
                        pa == null -> 1 // NULLs last in both directions.
                        pb == null -> -1
                        descending -> pb.compareTo(pa)
                        else -> pa.compareTo(pb)
                    }
                    if (primary != 0) primary else a.compareTo(b)
                }
                val pageIds = orderedIds.drop(page * size).take(size)
                val byId = providers.findByIds(pageIds).associateBy { it.id }
                pageProviders = pageIds.mapNotNull { byId[it] }
                minPriceForPage = priceByAll
            } else {
                total = providers.countFiltered(filter)
                pageProviders = providers.filteredPage(filter, sort, size, page * size)
                minPriceForPage = providers.minPricesUsdByProvider(pageProviders.map { it.id })
            }
            val ids = pageProviders.map { it.id }
            val paymentMethods = providers.paymentMethodsByProvider(ids)
            val locationCountries = providers.locationCountriesByProvider(ids)
            val activeTariffCounts = providers.activeTariffCountsByProvider(ids)
            val legalInfo = providers.legalInfoByProvider(ids)
            val lastAttempts = enrichmentState.lastAttemptAtByProvider(ids)
            val summaries = pageProviders.map { p ->
                ProviderSummaryDto(
                    id = p.id,
                    slug = p.slug,
                    name = p.name,
                    websiteUrl = p.websiteUrl,
                    status = p.status,
                    systemStatus = p.systemStatus,
                    registrationCountry = legalInfo[p.id]?.registrationCountry,
                    legalEntityName = legalInfo[p.id]?.legalEntityName,
                    legalEntityNameOrigin = legalInfo[p.id]?.legalEntityNameOrigin,
                    registrationCountryOrigin = legalInfo[p.id]?.registrationCountryOrigin,
                    paymentMethods = paymentMethods[p.id] ?: emptySet(),
                    locationCountryCodes = locationCountries[p.id] ?: emptySet(),
                    activeTariffCount = activeTariffCounts[p.id] ?: 0,
                    minMonthlyPriceUsd = minPriceForPage[p.id]?.monthly,
                    minAnnualPriceUsdPerMonth = minPriceForPage[p.id]?.annualPerMonth,
                    enrichmentLastAttemptAt = lastAttempts[p.id],
                )
            }
            total to summaries
        }
    }

    /** `GET /api/v1/providers/{id}` -- `404` if the provider does not exist. */
    suspend fun getProvider(id: Long): ProviderDetailDto = getProvider(id, catalogVisibleOnly = true)

    suspend fun getProviderForService(id: Long): ProviderDetailDto = getProvider(id, catalogVisibleOnly = false)

    private suspend fun getProvider(id: Long, catalogVisibleOnly: Boolean): ProviderDetailDto = withContext(Dispatchers.IO) {
        transaction(database) {
            val provider = (if (catalogVisibleOnly) providers.findCatalogVisible(id) else providers.find(id))
                ?: throw NotFoundException("Provider not found")
            val legalInfo = providers.legalInfo(id)
            val paymentMethods = providers.paymentMethods(id)
            val locations = LocationTable.selectAll().where { LocationTable.providerId eq id }.map { it.toLocation() }
            val activeTariffs = TariffTable.selectAll()
                .where { (TariffTable.providerId eq id) and (TariffTable.isActive eq true) }
                .map { it.toTariff() }
            val tariffIds = activeTariffs.map { it.id }
            val currentPricesByTariff = if (tariffIds.isEmpty()) {
                emptyMap()
            } else {
                TariffPriceTable.selectAll()
                    .where { (TariffPriceTable.tariffId inList tariffIds) and TariffPriceTable.effectiveTo.isNull() }
                    .map { it.toTariffPrice() }
                    .groupBy { it.tariffId }
            }
            val enrichment = enrichmentState.state(id)
            ProviderDetailDto(
                provider = provider.toDto(),
                legalInfo = legalInfo?.toDto(),
                paymentMethods = paymentMethods.map { it.toDto() },
                locations = locations.map { it.toDto() },
                tariffs = activeTariffs.map { t ->
                    TariffWithPricesDto(t.toDto(), (currentPricesByTariff[t.id] ?: emptyList()).map { it.toDto() })
                },
                enrichment = enrichment?.let { state ->
                    ProviderEnrichmentDto(
                        lastAttemptAt = state.lastAttemptAt,
                        lastOutcome = state.lastOutcome,
                        lastMessage = state.lastMessage,
                        lastPagesFetched = state.lastPagesFetched,
                        lastSuccessAt = state.lastSuccessAt,
                        evidence = state.evidenceJson?.let { decodeStoredEvidence(id, it) },
                    )
                },
            )
        }
    }

    /** `GET /api/v1/providers/{id}/tariffs` -- `404` if the provider does not exist, never an empty page. */
    suspend fun listProviderTariffs(
        providerId: Long,
        activeOnly: Boolean,
        page: Int,
        size: Int,
        sort: List<SortField>,
    ): Pair<Long, List<Tariff>> = listProviderTariffs(providerId, activeOnly, page, size, sort, catalogVisibleOnly = true)

    suspend fun listProviderTariffsForService(
        providerId: Long,
        activeOnly: Boolean,
        page: Int,
        size: Int,
        sort: List<SortField>,
    ): Pair<Long, List<Tariff>> = listProviderTariffs(providerId, activeOnly, page, size, sort, catalogVisibleOnly = false)

    private suspend fun listProviderTariffs(
        providerId: Long,
        activeOnly: Boolean,
        page: Int,
        size: Int,
        sort: List<SortField>,
        catalogVisibleOnly: Boolean,
    ): Pair<Long, List<Tariff>> = withContext(Dispatchers.IO) {
        transaction(database) {
            val provider = if (catalogVisibleOnly) providers.findCatalogVisible(providerId) else providers.find(providerId)
            provider ?: throw NotFoundException("Provider not found")
            val condition: () -> Op<Boolean> = {
                if (activeOnly) (TariffTable.providerId eq providerId) and (TariffTable.isActive eq true) else TariffTable.providerId eq providerId
            }
            val total = TariffTable.selectAll().where(condition).count()
            val orders = sort.map { field ->
                val column = when (field.field) {
                    "name" -> TariffTable.name
                    "createdAt" -> TariffTable.createdAt
                    else -> TariffTable.id
                }
                column to if (field.descending) SortOrder.DESC else SortOrder.ASC
            }
            val rows = TariffTable.selectAll().where(condition)
                .orderBy(*orders.toTypedArray())
                .limit(size).offset(page.toLong() * size)
                .map { it.toTariff() }
            total to rows
        }
    }

    suspend fun searchTariffs(
        filter: TariffSearchFilter,
        billingPeriod: BillingPeriod,
        page: Int,
        size: Int,
        sort: List<SortField>,
    ): Pair<Long, List<TariffSearchItemDto>> = withContext(Dispatchers.IO) {
        transaction(database) {
            val primarySort = sort.firstOrNull() ?: SortField("id", false)
            val (total, ids) = tariffs.searchPage(filter, billingPeriod, primarySort, size, page * size)
            if (ids.isEmpty()) return@transaction total to emptyList()

            val tariffById = tariffs.findByIds(ids).associateBy { it.id }
            val providerIds = tariffById.values.map { it.providerId }.distinct()
            val providerById = providers.findByIds(providerIds).associateBy { it.id }
            val currentPriceByTariff = currentPrices(ids, billingPeriod)
            val annualPriceByTariff = if (filter.includeAnnual) currentPrices(ids, BillingPeriod.ANNUAL) else emptyMap()

            // ids is already the correctly sorted/paginated order from searchPage -- preserve it.
            val items = ids.mapNotNull { id ->
                val t = tariffById[id] ?: return@mapNotNull null
                val p = providerById[t.providerId] ?: return@mapNotNull null
                val options = SearchPriceOptions(currentPriceByTariff[id], annualPriceByTariff[id])
                TariffSearchItemDto(
                    tariff = t.toDto(),
                    provider = p.toDto(),
                    currentPrice = options.primary?.toDto(),
                    otherPrice = options.other?.toDto(),
                    annualPriceUsdPerMonth = options.annualUsdPerMonth,
                )
            }
            total to items
        }
    }

    suspend fun cheapestTariffByProvider(
        filter: TariffSearchFilter,
        page: Int,
        size: Int,
        sort: SortField,
    ): Pair<Long, List<ProviderCheapestTariffDto>> = withContext(Dispatchers.IO) {
        transaction(database) {
            val (total, headlines) = tariffs.headlinesByProvider(filter, BillingPeriod.MONTHLY, sort, size, page * size)
            if (headlines.isEmpty()) return@transaction total to emptyList()

            val tariffIds = headlines.map { it.tariffId }
            val providerById = providers.findByIds(headlines.map { it.providerId }).associateBy { it.id }
            val tariffById = tariffs.findByIds(tariffIds).associateBy { it.id }
            val priceByTariff = currentPrices(tariffIds, BillingPeriod.MONTHLY)
            val annualPriceByTariff = if (filter.includeAnnual) currentPrices(tariffIds, BillingPeriod.ANNUAL) else emptyMap()

            val items = headlines.mapNotNull { headline ->
                val options = SearchPriceOptions(priceByTariff[headline.tariffId], annualPriceByTariff[headline.tariffId])
                ProviderCheapestTariffDto(
                    provider = providerById[headline.providerId]?.toDto() ?: return@mapNotNull null,
                    cheapestTariff = tariffById[headline.tariffId]?.toDto() ?: return@mapNotNull null,
                    cheapestPrice = options.primary?.toDto() ?: return@mapNotNull null,
                    matchingTariffCount = headline.matchingTariffCount,
                    otherPrice = options.other?.toDto(),
                    annualPriceUsdPerMonth = options.annualUsdPerMonth,
                )
            }
            total to items
        }
    }

    private fun currentPrices(tariffIds: List<Long>, billingPeriod: BillingPeriod) = TariffPriceTable.selectAll()
        .where {
            (TariffPriceTable.tariffId inList tariffIds) and
                (TariffPriceTable.billingPeriod eq billingPeriod.name) and
                TariffPriceTable.effectiveTo.isNull()
        }
        .map { it.toTariffPrice() }
        .associateBy { it.tariffId }

    /** `GET /api/v1/tariffs/{id}` -- `404` if the tariff does not exist. */
    suspend fun getTariffDetail(id: Long): TariffDetailDto = withContext(Dispatchers.IO) {
        transaction(database) {
            val tariff = tariffs.find(id) ?: throw NotFoundException("Tariff not found")
            val provider = providers.findCatalogVisible(tariff.providerId)
                ?: throw NotFoundException("Tariff not found")
            val locationIds = tariffs.locationIds(id)
            val locations = if (locationIds.isEmpty()) {
                emptyList()
            } else {
                LocationTable.selectAll().where { LocationTable.id inList locationIds }.map { it.toLocation() }
            }
            val currentPrices = TariffPriceTable.selectAll()
                .where { (TariffPriceTable.tariffId eq id) and TariffPriceTable.effectiveTo.isNull() }
                .map { it.toTariffPrice() }
            TariffDetailDto(
                tariff = tariff.toDto(),
                provider = provider.toDto(),
                locations = locations.map { it.toDto() },
                currentPrices = currentPrices.map { it.toDto() },
            )
        }
    }

    /**
     * `GET /api/v1/tariffs/{id}/price-history` -- `404` if the tariff does not exist; an empty
     * history is a valid `200`.
     */
    suspend fun getPriceHistory(
        tariffId: Long,
        billingPeriod: BillingPeriod?,
        from: Instant?,
        to: Instant?,
    ): List<PriceHistoryItemDto> = withContext(Dispatchers.IO) {
        transaction(database) {
            val tariff = tariffs.find(tariffId) ?: throw NotFoundException("Tariff not found")
            if (providers.findCatalogVisible(tariff.providerId) == null) throw NotFoundException("Tariff not found")
            prices.history(tariffId, billingPeriod, from, to).map { it.toHistoryDto() }
        }
    }
}

/**
 * Stored evidence outlives the code that wrote it, so it is read leniently, and an unreadable
 * document hides the evidence instead of failing the whole provider card.
 */
private val storedEvidenceJson = Json(from = ApiJson) { ignoreUnknownKeys = true }

private fun decodeStoredEvidence(providerId: Long, raw: String): EnrichmentEvidenceDto? =
    try {
        storedEvidenceJson.decodeFromString(EnrichmentEvidenceDto.serializer(), raw)
    } catch (e: SerializationException) {
        logger.warn("Stored enrichment evidence of provider {} is unreadable; omitting it", providerId, e)
        null
    }
