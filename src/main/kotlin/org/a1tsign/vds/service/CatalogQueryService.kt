package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.api.dto.*
import org.a1tsign.vds.db.LocationTable
import org.a1tsign.vds.db.PriceRepository
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

/**
 * Read-only cross-table queries for the provider catalog -- `GET /api/v1/providers` (filtered,
 * paginated, decorated with `minMonthlyPriceUsd`), `GET /api/v1/providers/{id}` (full detail),
 * `GET /api/v1/providers/{id}/tariffs`, the cross-provider `GET /api/v1/tariffs` search, tariff
 * detail and price history (task vds-001.16). Every method opens exactly one transaction and never
 * writes, reaching [LocationTable]/[TariffTable]/[TariffPriceTable] directly for the
 * detail/tariffs endpoints, which have no cross-table filtering/sorting of their own (unlike the
 * provider/tariff lists).
 */
class CatalogQueryService(
    private val database: Database,
    private val providers: ProviderRepository,
    private val tariffs: TariffRepository,
    private val prices: PriceRepository,
) {
    /**
     * Returns `(totalElements, page content)` -- the caller (the route) builds the final
     * `PageResponse`, the same split [UserService.findPage] uses.
     *
     * Not N+1: exactly seven SQL statements regardless of page size or sort field -- one
     * id/page resolution (filters+order+limit/offset via [ProviderRepository.filteredPage], or
     * filters alone via [ProviderRepository.filteredIds] when sorting by `minMonthlyPriceUsd`,
     * because that value has no column to `ORDER BY`), one `minMonthlyPriceUsd` aggregate (raw
     * SQL, [ProviderRepository.minMonthlyPriceUsdByProvider]) and one decoration query per
     * auxiliary fact (payment methods, location countries, active tariff counts, registration
     * country) -- never one query per row.
     */
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
            val minPriceForPage: Map<Long, BigDecimal?>
            if (minPriceSort != null) {
                // The sort key has no column: resolve every matching id, compute the aggregate for
                // all of them (one raw-SQL statement), sort/paginate that already-materialised,
                // filtered id list in memory -- still a fixed number of SQL statements, not N+1.
                val allIds = providers.filteredIds(filter)
                total = allIds.size.toLong()
                val priceByAll = providers.minMonthlyPriceUsdByProvider(allIds)
                val descending = minPriceSort.descending
                val orderedIds = allIds.sortedWith { a, b ->
                    val pa = priceByAll[a]
                    val pb = priceByAll[b]
                    val primary = when {
                        pa == null && pb == null -> 0
                        pa == null -> 1 // NULLs last in both directions.
                        pb == null -> -1
                        descending -> pb.compareTo(pa)
                        else -> pa.compareTo(pb)
                    }
                    if (primary != 0) primary else a.compareTo(b) // id ASC tie-breaker.
                }
                val pageIds = orderedIds.drop(page * size).take(size)
                val byId = providers.findByIds(pageIds).associateBy { it.id }
                pageProviders = pageIds.mapNotNull { byId[it] }
                minPriceForPage = priceByAll
            } else {
                total = providers.countFiltered(filter)
                pageProviders = providers.filteredPage(filter, sort, size, page * size)
                minPriceForPage = providers.minMonthlyPriceUsdByProvider(pageProviders.map { it.id })
            }
            val ids = pageProviders.map { it.id }
            val paymentMethods = providers.paymentMethodsByProvider(ids)
            val locationCountries = providers.locationCountriesByProvider(ids)
            val activeTariffCounts = providers.activeTariffCountsByProvider(ids)
            val registrationCountries = providers.registrationCountriesByProvider(ids)
            val summaries = pageProviders.map { p ->
                ProviderSummaryDto(
                    id = p.id,
                    slug = p.slug,
                    name = p.name,
                    websiteUrl = p.websiteUrl,
                    status = p.status,
                    systemStatus = p.systemStatus,
                    registrationCountry = registrationCountries[p.id],
                    paymentMethods = paymentMethods[p.id] ?: emptySet(),
                    locationCountryCodes = locationCountries[p.id] ?: emptySet(),
                    activeTariffCount = activeTariffCounts[p.id] ?: 0,
                    minMonthlyPriceUsd = minPriceForPage[p.id],
                )
            }
            total to summaries
        }
    }

    /** `GET /api/v1/providers/{id}` -- `404` if the provider does not exist. */
    suspend fun getProvider(id: Long): ProviderDetailDto = withContext(Dispatchers.IO) {
        transaction(database) {
            val provider = providers.find(id) ?: throw NotFoundException("Provider not found")
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
            ProviderDetailDto(
                provider = provider.toDto(),
                legalInfo = legalInfo?.toDto(),
                paymentMethods = paymentMethods.map { it.toDto() },
                locations = locations.map { it.toDto() },
                tariffs = activeTariffs.map { t ->
                    TariffWithPricesDto(t.toDto(), (currentPricesByTariff[t.id] ?: emptyList()).map { it.toDto() })
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
    ): Pair<Long, List<Tariff>> = withContext(Dispatchers.IO) {
        transaction(database) {
            providers.find(providerId) ?: throw NotFoundException("Provider not found")
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

    /**
     * `GET /api/v1/tariffs` -- cross-provider search. Not N+1: five SQL statements regardless of
     * page size -- [TariffRepository.searchPage]'s two raw-SQL statements (count, page ids), then
     * one decoration query each for the matching tariffs, their providers and their current
     * [billingPeriod] prices, all `inList` the page's ids -- never one query per row.
     */
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
            val currentPriceByTariff = TariffPriceTable.selectAll()
                .where {
                    (TariffPriceTable.tariffId inList ids) and
                        (TariffPriceTable.billingPeriod eq billingPeriod.name) and
                        TariffPriceTable.effectiveTo.isNull()
                }
                .map { it.toTariffPrice() }
                .associateBy { it.tariffId }

            // ids is already the correctly sorted/paginated order from searchPage -- preserve it.
            val items = ids.mapNotNull { id ->
                val t = tariffById[id] ?: return@mapNotNull null
                val p = providerById[t.providerId] ?: return@mapNotNull null
                TariffSearchItemDto(
                    tariff = t.toDto(),
                    provider = p.toDto(),
                    currentPrice = currentPriceByTariff[id]?.toDto(),
                )
            }
            total to items
        }
    }

    /** `GET /api/v1/tariffs/{id}` -- `404` if the tariff does not exist. */
    suspend fun getTariffDetail(id: Long): TariffDetailDto = withContext(Dispatchers.IO) {
        transaction(database) {
            val tariff = tariffs.find(id) ?: throw NotFoundException("Tariff not found")
            val provider = providers.find(tariff.providerId)
                ?: error("Tariff ${tariff.id} references missing provider ${tariff.providerId}")
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
            tariffs.find(tariffId) ?: throw NotFoundException("Tariff not found")
            prices.history(tariffId, billingPeriod, from, to).map { it.toHistoryDto() }
        }
    }
}
