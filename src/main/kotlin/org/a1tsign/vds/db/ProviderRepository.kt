package org.a1tsign.vds.db

import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Instant

/**
 * The cheapest current monthly price and the cheapest current annual price divided by 12, both in
 * USD; [lowest] is the smaller of the two, the value the provider list sorts by.
 */
data class MinPricesUsd(val monthly: BigDecimal?, val annualPerMonth: BigDecimal?) {
    val lowest: BigDecimal? get() = listOfNotNull(monthly, annualPerMonth).minOrNull()
}

data class ProviderListFilter(
    val q: String? = null,
    val status: ProviderStatus? = null,
    val registrationCountry: String? = null,
    val locationCountry: String? = null,
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val hasActiveTariffs: Boolean? = null,
    /** Restricts catalog listings to visible statuses; every list query applies it before counting. */
    val systemStatuses: List<SystemStatus> = PUBLIC_CATALOG_SYSTEM_STATUSES.toList(),
)

/** Repository methods require a transaction opened by [org.a1tsign.vds.service.ProviderService]. */
class ProviderRepository {
    fun insert(
        slug: String,
        name: String,
        websiteUrl: String,
        description: String?,
        status: ProviderStatus,
        now: Instant,
        systemStatus: SystemStatus = SystemStatus.CONFIGURED,
    ): Provider {
        val id = ProviderTable.insert {
            it[ProviderTable.slug] = slug; it[ProviderTable.name] = name; it[ProviderTable.websiteUrl] = websiteUrl
            it[ProviderTable.description] = description; it[ProviderTable.status] = status.name
            it[ProviderTable.systemStatus] = systemStatus.name
            it[ProviderTable.createdAt] = now.toUtcOffset(); it[ProviderTable.updatedAt] = now.toUtcOffset()
        }[ProviderTable.id]
        return find(id)!!
    }
    fun find(id: Long): Provider? = ProviderTable.selectAll().where { ProviderTable.id eq id }.firstOrNull()?.toProvider()
    fun findCatalogVisible(id: Long): Provider? = ProviderTable.selectAll()
        .where { (ProviderTable.id eq id) and (ProviderTable.systemStatus inList PUBLIC_CATALOG_SYSTEM_STATUSES.map { it.name }) }
        .firstOrNull()?.toProvider()
    fun lockProvider(id: Long): Provider? = ProviderTable.selectAll()
        .where { ProviderTable.id eq id }
        .forUpdate(ForUpdateOption.ForUpdate)
        .firstOrNull()?.toProvider()
    /** Null leaves the column untouched so an ordinary update cannot promote a candidate. */
    fun update(
        id: Long,
        slug: String,
        name: String,
        websiteUrl: String,
        description: String?,
        status: ProviderStatus,
        now: Instant,
        systemStatus: SystemStatus? = null,
    ) {
        ProviderTable.update({ ProviderTable.id eq id }) {
            it[ProviderTable.slug] = slug; it[ProviderTable.name] = name; it[ProviderTable.websiteUrl] = websiteUrl
            it[ProviderTable.description] = description; it[ProviderTable.status] = status.name; it[ProviderTable.updatedAt] = now.toUtcOffset()
            if (systemStatus != null) it[ProviderTable.systemStatus] = systemStatus.name
        }
    }

    /** `PUT /api/v1/providers/{id}/system-status`: that one column and `updated_at`, nothing else. */
    fun updateSystemStatus(id: Long, systemStatus: SystemStatus, now: Instant) {
        ProviderTable.update({ ProviderTable.id eq id }) {
            it[ProviderTable.systemStatus] = systemStatus.name; it[ProviderTable.updatedAt] = now.toUtcOffset()
        }
    }
    fun delete(id: Long) { ProviderTable.deleteWhere { ProviderTable.id eq id } }
    /** Keeps the stored field origins; use [writeLegalInfo] to set them. */
    fun upsertLegalInfo(id: Long, entity: String?, country: String?, number: String?, address: String?, source: LegalDataSource, confidence: Confidence, sourceUrl: String?, verifiedAt: Instant?, now: Instant) {
        val current = legalInfo(id)
        writeLegalInfo(
            LegalInfo(
                id, entity, country, number, address, source, confidence, sourceUrl, verifiedAt, now,
                current?.legalEntityNameOrigin, current?.registrationCountryOrigin,
            ),
            now,
        )
    }

    fun writeLegalInfo(info: LegalInfo, now: Instant) {
        val exists = ProviderLegalInfoTable.select(ProviderLegalInfoTable.providerId)
            .where { ProviderLegalInfoTable.providerId eq info.providerId }.any()
        val assign: (UpdateBuilder<*>) -> Unit = {
            it[ProviderLegalInfoTable.legalEntityName] = info.legalEntityName
            it[ProviderLegalInfoTable.registrationCountry] = info.registrationCountry
            it[ProviderLegalInfoTable.registrationNumber] = info.registrationNumber
            it[ProviderLegalInfoTable.legalAddress] = info.legalAddress
            it[ProviderLegalInfoTable.dataSource] = info.dataSource.name
            it[ProviderLegalInfoTable.confidence] = info.confidence.name
            it[ProviderLegalInfoTable.sourceUrl] = info.sourceUrl
            it[ProviderLegalInfoTable.verifiedAt] = info.verifiedAt?.toUtcOffset()
            it[ProviderLegalInfoTable.legalEntityNameOrigin] = info.legalEntityNameOrigin?.name
            it[ProviderLegalInfoTable.registrationCountryOrigin] = info.registrationCountryOrigin?.name
            it[ProviderLegalInfoTable.updatedAt] = now.toUtcOffset()
        }
        if (exists) {
            ProviderLegalInfoTable.update({ ProviderLegalInfoTable.providerId eq info.providerId }) { assign(it) }
        } else {
            ProviderLegalInfoTable.insert { it[ProviderLegalInfoTable.providerId] = info.providerId; assign(it) }
        }
    }
    fun legalInfo(id: Long): LegalInfo? = ProviderLegalInfoTable.selectAll().where { ProviderLegalInfoTable.providerId eq id }.firstOrNull()?.toLegalInfo()
    /** Methods that were already present keep their origin; new ones are `MANUAL`. */
    fun replacePaymentMethods(id: Long, methods: List<Pair<PaymentMethod, String?>>) {
        val origins = paymentMethods(id).associate { it.method to it.origin }
        replacePaymentMethodRows(id, methods.map { (method, note) -> ProviderPaymentMethod(id, method, note, origins[method] ?: RowOrigin.MANUAL) })
    }
    fun replacePaymentMethodRows(id: Long, rows: List<ProviderPaymentMethod>) {
        ProviderPaymentMethodTable.deleteWhere { providerId eq id }
        rows.forEach { row -> addPaymentMethod(id, row.method, row.note, row.origin) }
    }
    fun addPaymentMethod(id: Long, method: PaymentMethod, note: String?, origin: RowOrigin) {
        ProviderPaymentMethodTable.insert {
            it[ProviderPaymentMethodTable.providerId] = id; it[ProviderPaymentMethodTable.method] = method.name
            it[ProviderPaymentMethodTable.note] = note; it[ProviderPaymentMethodTable.origin] = origin.name
        }
    }
    fun paymentMethods(id: Long): List<ProviderPaymentMethod> = ProviderPaymentMethodTable.selectAll().where { ProviderPaymentMethodTable.providerId eq id }.map { it.toProviderPaymentMethod() }


    fun countFiltered(filter: ProviderListFilter): Long =
        ProviderTable.selectAll().where { providerFilter(filter) }.count()

    /** Every provider id matching [filter], in no particular order -- used to sort by `minMonthlyPriceUsd` in memory. */
    fun filteredIds(filter: ProviderListFilter): List<Long> =
        ProviderTable.select(ProviderTable.id).where { providerFilter(filter) }.map { it[ProviderTable.id] }

    /** One page of providers matching [filter], ordered by [sort] (never `minMonthlyPriceUsd` -- see [filteredIds]). */
    fun filteredPage(filter: ProviderListFilter, sort: List<SortField>, limit: Int, offset: Int): List<Provider> {
        val orders = sort.map { field ->
            val column: Column<*> = when (field.field) {
                "name" -> ProviderTable.name
                "slug" -> ProviderTable.slug
                "status" -> ProviderTable.status
                "createdAt" -> ProviderTable.createdAt
                else -> ProviderTable.id
            }
            column to if (field.descending) SortOrder.DESC else SortOrder.ASC
        }
        return ProviderTable.selectAll().where { providerFilter(filter) }
            .orderBy(*orders.toTypedArray())
            .limit(limit).offset(offset.toLong())
            .map { it.toProvider() }
    }

    fun findByIds(ids: Collection<Long>): List<Provider> =
        if (ids.isEmpty()) emptyList() else ProviderTable.selectAll().where { ProviderTable.id inList ids }.map { it.toProvider() }

    fun paymentMethodsByProvider(ids: Collection<Long>): Map<Long, Set<PaymentMethod>> {
        if (ids.isEmpty()) return emptyMap()
        return ProviderPaymentMethodTable.selectAll().where { ProviderPaymentMethodTable.providerId inList ids }
            .toList()
            .groupBy({ it[ProviderPaymentMethodTable.providerId] }) {
                parseDbEnum<PaymentMethod>("provider_payment_method", "method", it[ProviderPaymentMethodTable.method])
            }
            .mapValues { it.value.toSet() }
    }

    fun legalInfoByProvider(ids: Collection<Long>): Map<Long, LegalInfo> {
        if (ids.isEmpty()) return emptyMap()
        return ProviderLegalInfoTable.selectAll().where { ProviderLegalInfoTable.providerId inList ids }
            .associate { it[ProviderLegalInfoTable.providerId] to it.toLegalInfo() }
    }

    fun locationCountriesByProvider(ids: Collection<Long>): Map<Long, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        return LocationTable.selectAll().where { LocationTable.providerId inList ids }
            .toList()
            .groupBy({ it[LocationTable.providerId] }) { it[LocationTable.countryCode] }
            .mapValues { it.value.toSet() }
    }

    fun activeTariffCountsByProvider(ids: Collection<Long>): Map<Long, Int> {
        if (ids.isEmpty()) return emptyMap()
        val countExpr = TariffTable.id.count()
        return TariffTable.select(TariffTable.providerId, countExpr)
            .where { (TariffTable.providerId inList ids) and (TariffTable.isActive eq true) }
            .groupBy(TariffTable.providerId)
            .associate { it[TariffTable.providerId] to it[countExpr].toInt() }
    }

    /** Aggregate current prices from active, in-stock tariffs. Use a parameterized query and divide annual USD prices by 12. */
    fun minPricesUsdByProvider(ids: Collection<Long>): Map<Long, MinPricesUsd> {
        if (ids.isEmpty()) return emptyMap()
        val idList = ids.distinct()
        val placeholders = idList.joinToString(",") { "?" }
        val sql = """
            SELECT t.provider_id AS provider_id,
                   MIN(CASE WHEN tp.billing_period = 'MONTHLY' THEN tp.amount_usd END) AS min_monthly,
                   MIN(CASE WHEN tp.billing_period = 'ANNUAL' THEN tp.amount_usd END) AS min_annual
            FROM tariff t
            JOIN tariff_price tp ON tp.tariff_id = t.id
            WHERE t.is_active = TRUE
              AND t.in_stock = TRUE
              AND tp.billing_period IN ('MONTHLY', 'ANNUAL')
              AND tp.effective_to IS NULL
              AND tp.amount_usd IS NOT NULL
              AND t.provider_id IN ($placeholders)
            GROUP BY t.provider_id
        """.trimIndent()
        val args: List<Pair<IColumnType<*>, Any?>> = idList.map { LongColumnType() to it }
        val result = mutableMapOf<Long, MinPricesUsd>()
        TransactionManager.current().exec(sql, args, StatementType.SELECT) { rs ->
            while (rs.next()) {
                val annual: BigDecimal? = rs.getBigDecimal("min_annual")
                result[rs.getLong("provider_id")] = MinPricesUsd(
                    monthly = rs.getBigDecimal("min_monthly"),
                    annualPerMonth = annual?.annualUsdPerMonth(),
                )
            }
        }
        return result
    }

    private fun providerFilter(filter: ProviderListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        // Always constrained -- an empty list matches nothing, never everything.
        op = op and (ProviderTable.systemStatus inList filter.systemStatuses.map { it.name })
        filter.q?.takeIf { it.isNotBlank() }?.let { q ->
            val pattern = "%${q.lowercase().replace("%", "\\%").replace("_", "\\_")}%"
            op = op and ((ProviderTable.name.lowerCase() like pattern) or (ProviderTable.slug.lowerCase() like pattern))
        }
        filter.status?.let { op = op and (ProviderTable.status eq it.name) }
        filter.registrationCountry?.let { country ->
            op = op and exists(
                ProviderLegalInfoTable.selectAll().where {
                    (ProviderLegalInfoTable.providerId eq ProviderTable.id) and (ProviderLegalInfoTable.registrationCountry eq country)
                },
            )
        }
        filter.locationCountry?.let { country ->
            op = op and exists(
                LocationTable.selectAll().where {
                    (LocationTable.providerId eq ProviderTable.id) and (LocationTable.countryCode eq country)
                },
            )
        }
        if (filter.paymentMethods.isNotEmpty()) {
            val names = filter.paymentMethods.map { it.name }
            val matchingAll = ProviderPaymentMethodTable.select(ProviderPaymentMethodTable.providerId)
                .where { ProviderPaymentMethodTable.method inList names }
                .groupBy(ProviderPaymentMethodTable.providerId)
                .having { ProviderPaymentMethodTable.method.countDistinct() eq names.size.toLong() }
            op = op and (ProviderTable.id inSubQuery matchingAll)
        }
        filter.hasActiveTariffs?.let { has ->
            val activeExists = exists(
                TariffTable.selectAll().where {
                    (TariffTable.providerId eq ProviderTable.id) and (TariffTable.isActive eq true)
                },
            )
            op = op and (if (has) activeExists else not(activeExists))
        }
        return op
    }
}
