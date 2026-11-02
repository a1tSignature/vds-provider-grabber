package org.a1tsign.vds.db

import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.Tariff
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

/** The `GET /api/v1/tariffs` filter set -- every field `null`/unset means "no constraint". */
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
)

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

    /**
     * ADR-04's second and last hand-written-SQL exception: resolves `(total, one page of tariff
     * ids)` matching [filter], `LEFT JOIN`ed to each tariff's *current* price for [billingPeriod]
     * (`effective_to IS NULL`, so at most one row per tariff by `uq_tariff_price_current` -- the
     * join can never duplicate a row) so [TariffSearchFilter.maxPriceUsd] and a `sort.field ==
     * "priceUsd"` can be expressed as plain `WHERE`/`ORDER BY` clauses with Postgres's own `NULLS
     * LAST`, which reads far more naturally as SQL than as nested DSL aggregates. All filter/sort
     * values are bound as `?` placeholders -- never concatenated -- including the `LIMIT`/`OFFSET`.
     * `country` and `paymentMethod` are `EXISTS` subqueries, not joins, so a tariff linked to two
     * locations in the same country (or a provider with several matching payment methods) is never
     * counted or returned twice.
     */
    fun searchPage(
        filter: TariffSearchFilter,
        billingPeriod: BillingPeriod,
        sort: SortField,
        limit: Int,
        offset: Int,
    ): Pair<Long, List<Long>> {
        val (whereSql, whereArgs) = buildWhere(filter)
        val periodArg: Pair<IColumnType<*>, Any?> = VarCharColumnType() to billingPeriod.name

        val countSql = """
            SELECT COUNT(t.id) AS total
            FROM tariff t
            LEFT JOIN tariff_price tp ON tp.tariff_id = t.id AND tp.billing_period = ? AND tp.effective_to IS NULL
            WHERE $whereSql
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
            "priceUsd" -> "tp.amount_usd"
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
            FROM tariff t
            LEFT JOIN tariff_price tp ON tp.tariff_id = t.id AND tp.billing_period = ? AND tp.effective_to IS NULL
            WHERE $whereSql
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

    /** `WHERE` builder shared by [searchPage]'s count and page statements. */
    private fun buildWhere(filter: TariffSearchFilter): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()

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
            clauses += "tp.amount_usd IS NOT NULL AND tp.amount_usd <= ?"
            args += DecimalColumnType(12, 4) to it
        }

        val where = if (clauses.isEmpty()) "TRUE" else clauses.joinToString(" AND ")
        return where to args
    }
}
