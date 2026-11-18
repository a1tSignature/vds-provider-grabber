package org.a1tsign.vds.db

import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.MONTHS_PER_YEAR
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.PUBLIC_CATALOG_SYSTEM_STATUSES
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.domain.Tariff
import org.a1tsign.vds.domain.USD_AMOUNT_SCALE
import org.a1tsign.vds.domain.Virtualization
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Instant

/**
 * The tariff search filter set -- every field `null`/unset means "no constraint". [hasPrice] refers
 * to a current price row of the period the query joins, or either period with [includeAnnual].
 */
data class TariffSearchFilter(
    val providerId: Long? = null,
    val country: String? = null,
    val minVcpu: Int? = null,
    val minRamMb: Int? = null,
    val minDiskGb: Int? = null,
    val diskType: DiskType? = null,
    val paymentMethod: PaymentMethod? = null,
    val unmeteredTraffic: Boolean? = null,
    val activeOnly: Boolean = true,
    val inStock: Boolean? = null,
    val maxPriceUsd: BigDecimal? = null,
    val hasPrice: Boolean? = null,
    val includeAnnual: Boolean = false,
)

/** One provider of the grouped search: its cheapest matching tariff and how many of its tariffs match. */
data class ProviderHeadline(val providerId: Long, val tariffId: Long, val matchingTariffCount: Int)

/** Repository methods require a transaction opened by [org.a1tsign.vds.service.TariffService]. */
class TariffRepository {
    fun insert(
        providerId: Long,
        externalId: String?,
        name: String,
        url: String?,
        vcpuCount: Short,
        cpuModel: String?,
        ramMb: Int,
        diskGb: Int,
        diskType: DiskType,
        bandwidthMbps: Int?,
        trafficLimitGb: Int?,
        ipv4Count: Short,
        ipv6Supported: Boolean?,
        virtualization: Virtualization,
        isActive: Boolean,
        inStock: Boolean,
        now: Instant,
    ): Tariff {
        val id = TariffTable.insert {
            it[TariffTable.providerId] = providerId
            it[TariffTable.externalId] = externalId
            it[TariffTable.name] = name
            it[TariffTable.url] = url
            it[TariffTable.vcpuCount] = vcpuCount
            it[TariffTable.cpuModel] = cpuModel
            it[TariffTable.ramMb] = ramMb
            it[TariffTable.diskGb] = diskGb
            it[TariffTable.diskType] = diskType.name
            it[TariffTable.bandwidthMbps] = bandwidthMbps
            it[TariffTable.trafficLimitGb] = trafficLimitGb
            it[TariffTable.ipv4Count] = ipv4Count
            it[TariffTable.ipv6Supported] = ipv6Supported
            it[TariffTable.virtualization] = virtualization.name
            it[TariffTable.isActive] = isActive
            it[TariffTable.inStock] = inStock
            it[TariffTable.createdAt] = now.toUtcOffset()
            it[TariffTable.updatedAt] = now.toUtcOffset()
        }[TariffTable.id]
        return find(id)!!
    }

    fun find(id: Long): Tariff? = TariffTable.selectAll().where { TariffTable.id eq id }.firstOrNull()?.toTariff()

    fun update(
        id: Long,
        externalId: String?,
        name: String,
        url: String?,
        vcpuCount: Short,
        cpuModel: String?,
        ramMb: Int,
        diskGb: Int,
        diskType: DiskType,
        bandwidthMbps: Int?,
        trafficLimitGb: Int?,
        ipv4Count: Short,
        ipv6Supported: Boolean?,
        virtualization: Virtualization,
        isActive: Boolean,
        inStock: Boolean,
        now: Instant,
    ) {
        TariffTable.update({ TariffTable.id eq id }) {
            it[TariffTable.externalId] = externalId
            it[TariffTable.name] = name
            it[TariffTable.url] = url
            it[TariffTable.vcpuCount] = vcpuCount
            it[TariffTable.cpuModel] = cpuModel
            it[TariffTable.ramMb] = ramMb
            it[TariffTable.diskGb] = diskGb
            it[TariffTable.diskType] = diskType.name
            it[TariffTable.bandwidthMbps] = bandwidthMbps
            it[TariffTable.trafficLimitGb] = trafficLimitGb
            it[TariffTable.ipv4Count] = ipv4Count
            it[TariffTable.ipv6Supported] = ipv6Supported
            it[TariffTable.virtualization] = virtualization.name
            it[TariffTable.isActive] = isActive
            it[TariffTable.inStock] = inStock
            it[TariffTable.updatedAt] = now.toUtcOffset()
        }
    }

    fun delete(id: Long) {
        TariffTable.deleteWhere { TariffTable.id eq id }
    }

    fun locationIds(tariffId: Long): Set<Long> =
        TariffLocationTable.selectAll().where { TariffLocationTable.tariffId eq tariffId }
            .map { it[TariffLocationTable.locationId] }.toSet()

    /**
     * Delete-then-insert of the whole link set, both inside the caller's transaction -- never
     * committed independently, so a failure (including the composite FK's own `23503`) rolls both
     * halves back and leaves the previous link set exactly as it was.
     */
    fun replaceLocations(tariffId: Long, providerId: Long, locationIds: List<Long>) {
        TariffLocationTable.deleteWhere { TariffLocationTable.tariffId eq tariffId }
        locationIds.forEach { locationId ->
            TariffLocationTable.insert {
                it[TariffLocationTable.tariffId] = tariffId
                it[TariffLocationTable.locationId] = locationId
                it[TariffLocationTable.providerId] = providerId
            }
        }
    }

    // ---- Read queries added for the tariff search/detail endpoints (task vds-001.16) ----

    fun findByIds(ids: Collection<Long>): List<Tariff> =
        if (ids.isEmpty()) emptyList() else TariffTable.selectAll().where { TariffTable.id inList ids }.map { it.toTariff() }

    fun searchPage(
        filter: TariffSearchFilter,
        billingPeriod: BillingPeriod,
        sort: SortField,
        limit: Int,
        offset: Int,
    ): Pair<Long, List<Long>> {
        val prices = SearchPrices(filter.includeAnnual)
        val (whereSql, whereArgs) = buildWhere(filter, prices)
        val periodArg: Pair<IColumnType<*>, Any?> = VarCharColumnType() to billingPeriod.name
        val matching = """
            FROM tariff t
            ${prices.joins}
            WHERE $whereSql
        """.trimIndent()

        val countSql = """
            SELECT COUNT(t.id) AS total
            $matching
        """.trimIndent()
        var total = 0L
        TransactionManager.current().exec(countSql, listOf(periodArg) + whereArgs, StatementType.SELECT) { rs ->
            if (rs.next()) total = rs.getLong("total")
        }

        val sortColumn = when (sort.field) {
            "name" -> "t.name"
            "ramMb" -> "t.ram_mb"
            "vcpuCount" -> "t.vcpu_count"
            "diskGb" -> "t.disk_gb"
            "priceUsd" -> prices.amountUsd
            else -> "t.id"
        }
        val direction = if (sort.descending) "DESC" else "ASC"
        val orderBy = if (sortColumn == "t.id") {
            "t.id $direction"
        } else {
            val nullsLast = if (sort.field == "priceUsd") " NULLS LAST" else ""
            "$sortColumn $direction$nullsLast, t.id ASC"
        }

        val pageSql = """
            SELECT t.id AS id
            $matching
            ORDER BY $orderBy
            LIMIT ? OFFSET ?
        """.trimIndent()
        val pageArgs = listOf(periodArg) + whereArgs +
            listOf<Pair<IColumnType<*>, Any?>>(IntegerColumnType() to limit, IntegerColumnType() to offset)
        val ids = mutableListOf<Long>()
        TransactionManager.current().exec(pageSql, pageArgs, StatementType.SELECT) { rs ->
            while (rs.next()) ids += rs.getLong("id")
        }
        return total to ids
    }

    /**
     * `(number of providers, one page of them)` for the provider-grouped search: a provider is in
     * when at least one of its tariffs matches [filter] and has a current price for a selected
     * period. Its headline is the first tariff under [searchPage]'s ascending `priceUsd` order.
     */
    fun headlinesByProvider(
        filter: TariffSearchFilter,
        billingPeriod: BillingPeriod,
        sort: SortField,
        limit: Int,
        offset: Int,
    ): Pair<Long, List<ProviderHeadline>> {
        val prices = SearchPrices(filter.includeAnnual)
        val (whereSql, whereArgs) = buildWhere(filter.copy(hasPrice = true), prices)
        val periodArg: Pair<IColumnType<*>, Any?> = VarCharColumnType() to billingPeriod.name
        val matching = """
            FROM tariff t
            ${prices.joins}
            WHERE $whereSql
        """.trimIndent()

        var total = 0L
        TransactionManager.current().exec(
            "SELECT COUNT(DISTINCT t.provider_id) AS total $matching",
            listOf(periodArg) + whereArgs,
            StatementType.SELECT,
        ) { rs ->
            if (rs.next()) total = rs.getLong("total")
        }

        val direction = if (sort.descending) "DESC" else "ASC"
        val orderBy = when (sort.field) {
            "priceUsd" -> "r.amount_usd $direction NULLS LAST, r.provider_id ASC"
            "name" -> "p.name $direction, r.provider_id ASC"
            else -> "r.provider_id $direction"
        }
        // Raw SQL: ranking the tariffs inside each provider needs window functions, which the
        // Exposed DSL cannot express readably.
        val pageSql = """
            SELECT r.provider_id, r.tariff_id, r.matching
            FROM (
                SELECT t.provider_id, t.id AS tariff_id, ${prices.amountUsd} AS amount_usd,
                       ROW_NUMBER() OVER (PARTITION BY t.provider_id ORDER BY ${prices.amountUsd} ASC NULLS LAST, t.id ASC) AS rank,
                       COUNT(*) OVER (PARTITION BY t.provider_id) AS matching
                $matching
            ) r
            JOIN provider p ON p.id = r.provider_id
            WHERE r.rank = 1
            ORDER BY $orderBy
            LIMIT ? OFFSET ?
        """.trimIndent()
        val pageArgs = listOf(periodArg) + whereArgs +
            listOf<Pair<IColumnType<*>, Any?>>(IntegerColumnType() to limit, IntegerColumnType() to offset)
        val headlines = mutableListOf<ProviderHeadline>()
        TransactionManager.current().exec(pageSql, pageArgs, StatementType.SELECT) { rs ->
            while (rs.next()) {
                headlines += ProviderHeadline(rs.getLong("provider_id"), rs.getLong("tariff_id"), rs.getInt("matching"))
            }
        }
        return total to headlines
    }

    private class SearchPrices(includeAnnual: Boolean) {
        val joins = """
            LEFT JOIN tariff_price tp ON tp.tariff_id = t.id AND tp.billing_period = ? AND tp.effective_to IS NULL
            ${if (includeAnnual) """
                LEFT JOIN tariff_price annual ON annual.tariff_id = t.id
                    AND annual.billing_period = 'ANNUAL' AND annual.effective_to IS NULL
            """.trimIndent() else ""}
        """.trimIndent()

        val id = if (includeAnnual) "COALESCE(tp.id, annual.id)" else "tp.id"
        val amountUsd = if (includeAnnual) {
            "LEAST(tp.amount_usd, ROUND(annual.amount_usd / $MONTHS_PER_YEAR, $USD_AMOUNT_SCALE))"
        } else {
            "tp.amount_usd"
        }
    }

    private fun buildWhere(filter: TariffSearchFilter, prices: SearchPrices): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()

        // The cross-provider search is a *listing*: a tariff whose provider is not catalog-visible
        // must not surface here. EXISTS rather than
        // a join so the row cannot be duplicated, and the value is bound, never concatenated.
        val visibleStatuses = PUBLIC_CATALOG_SYSTEM_STATUSES.map { it.name }
        clauses += "EXISTS (SELECT 1 FROM provider p WHERE p.id = t.provider_id AND p.system_status IN (${visibleStatuses.joinToString(",") { "?" }}))"
        visibleStatuses.forEach { args += VarCharColumnType() to it }
        if (filter.activeOnly) clauses += "t.is_active = TRUE"
        filter.providerId?.let { clauses += "t.provider_id = ?"; args += LongColumnType() to it }
        filter.diskType?.let { clauses += "t.disk_type = ?"; args += VarCharColumnType() to it.name }
        filter.minVcpu?.let { clauses += "t.vcpu_count >= ?"; args += IntegerColumnType() to it }
        filter.minRamMb?.let { clauses += "t.ram_mb >= ?"; args += IntegerColumnType() to it }
        filter.minDiskGb?.let { clauses += "t.disk_gb >= ?"; args += IntegerColumnType() to it }
        filter.unmeteredTraffic?.let { unmetered ->
            clauses += if (unmetered) "t.traffic_limit_gb IS NULL" else "t.traffic_limit_gb IS NOT NULL"
        }
        filter.inStock?.let { clauses += "t.in_stock = ?"; args += BooleanColumnType() to it }
        filter.country?.let {
            clauses += "EXISTS (SELECT 1 FROM tariff_location tl JOIN location l ON l.id = tl.location_id " +
                "WHERE tl.tariff_id = t.id AND l.country_code = ?)"
            args += VarCharColumnType() to it
        }
        filter.paymentMethod?.let {
            clauses += "EXISTS (SELECT 1 FROM provider_payment_method ppm " +
                "WHERE ppm.provider_id = t.provider_id AND ppm.method = ?)"
            args += VarCharColumnType() to it.name
        }
        filter.maxPriceUsd?.let {
            clauses += "${prices.amountUsd} <= ?"
            args += DecimalColumnType(12, 4) to it
        }
        filter.hasPrice?.let { clauses += if (it) "${prices.id} IS NOT NULL" else "${prices.id} IS NULL" }

        val where = if (clauses.isEmpty()) "TRUE" else clauses.joinToString(" AND ")
        return where to args
    }
}
