package org.a1tsign.vds.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed DSL mirror of `V1__core_schema.sql` -- one `object` per table, column-for-column. Never
 * declares an index or a unique constraint (those live in the migration only) and never asks
 * Exposed to create or alter a table: Exposed here is a typed query builder on top of a schema
 * Flyway owns, not a schema generator.
 *
 * Enum-like columns stay plain `varchar`; the `VARCHAR` <-> `domain.*` enum conversion happens in
 * `RowMappers.kt` through `parseDbEnum`, not through `enumerationByName`/`customEnumeration` (see
 * the KDoc on `parseDbEnum` for why).
 */
object ProviderTable : Table("provider") {
    val id = long("id").autoIncrement()
    val slug = varchar("slug", 64)
    val name = varchar("name", 255)
    val websiteUrl = text("website_url")
    val description = text("description").nullable()
    val status = varchar("status", 32)

    /** Added by `V2__provider_system_status.sql`; `DEFAULT 'CONFIGURED'` lives in the migration. */
    val systemStatus = varchar("system_status", 32)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** `provider_id` is both the primary key and the FK to [ProviderTable.id] -- 0..1 per provider. */
object ProviderLegalInfoTable : Table("provider_legal_info") {
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val legalEntityName = varchar("legal_entity_name", 255).nullable()
    val registrationCountry = char("registration_country", 2).nullable()
    val registrationNumber = varchar("registration_number", 128).nullable()
    val legalAddress = text("legal_address").nullable()
    val dataSource = varchar("data_source", 32)
    val confidence = varchar("confidence", 16)
    val sourceUrl = text("source_url").nullable()
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(providerId)
}

/** Composite PK `(provider_id, method)` -- a provider accepts a set of payment methods. */
object ProviderPaymentMethodTable : Table("provider_payment_method") {
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val method = varchar("method", 32)
    val note = text("note").nullable()

    override val primaryKey = PrimaryKey(providerId, method)
}

object LocationTable : Table("location") {
    val id = long("id").autoIncrement()
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val countryCode = char("country_code", 2)
    val region = varchar("region", 128).nullable()
    val city = varchar("city", 128).nullable()
    val datacenterName = varchar("datacenter_name", 255).nullable()
    val latitude = decimal("latitude", 9, 6).nullable()
    val longitude = decimal("longitude", 9, 6).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object TariffTable : Table("tariff") {
    val id = long("id").autoIncrement()
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val externalId = varchar("external_id", 128).nullable()
    val name = varchar("name", 255)
    val url = text("url").nullable()
    val vcpuCount = short("vcpu_count")
    val cpuModel = varchar("cpu_model", 128).nullable()
    val ramMb = integer("ram_mb")
    val diskGb = integer("disk_gb")
    val diskType = varchar("disk_type", 16)
    val bandwidthMbps = integer("bandwidth_mbps").nullable()
    val trafficLimitGb = integer("traffic_limit_gb").nullable()
    val ipv4Count = short("ipv4_count")
    val ipv6Supported = bool("ipv6_supported").nullable()
    val virtualization = varchar("virtualization", 16)
    val isActive = bool("is_active")
    val inStock = bool("in_stock")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Many-to-many between [TariffTable] and [LocationTable]. The migration's two FKs are each
 * composite -- `(tariff_id, provider_id)` and `(location_id, provider_id)` -- which cannot be
 * expressed via Exposed's single-column `references(...)` helper (Exposed 1.6.0's
 * `Table.foreignKey(vararg Pair<Column<*>, Column<*>>, onDelete, name)` can express a composite
 * FK directly, but is not used here); `provider_id` is kept here as a plain column rather than
 * attached to a half-true single-column reference. The real constraint lives in the migration
 * and is exercised against the database directly, not reconstructed in Kotlin.
 */
object TariffLocationTable : Table("tariff_location") {
    val tariffId = long("tariff_id")
    val locationId = long("location_id")
    val providerId = long("provider_id")

    override val primaryKey = PrimaryKey(tariffId, locationId)
}

object AppUserTable : Table("app_user") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 64)
    val email = varchar("email", 255).nullable()
    val passwordHash = varchar("password_hash", 100)
    val status = varchar("status", 16)
    val tokenVersion = integer("token_version")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val lastLoginAt = timestampWithTimeZone("last_login_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object RoleTable : Table("role") {
    val id = long("id").autoIncrement()
    val code = varchar("code", 32)
    val description = text("description").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Composite PK `(user_id, role_id)`, both columns also FKs. */
object UserRoleTable : Table("user_role") {
    val userId = long("user_id").references(AppUserTable.id, onDelete = ReferenceOption.CASCADE)
    val roleId = long("role_id").references(RoleTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(userId, roleId)
}

object TariffPriceTable : Table("tariff_price") {
    val id = long("id").autoIncrement()
    val tariffId = long("tariff_id").references(TariffTable.id, onDelete = ReferenceOption.CASCADE)
    val billingPeriod = varchar("billing_period", 16)
    val amount = decimal("amount", 12, 4)
    val currency = char("currency", 3)
    val amountUsd = decimal("amount_usd", 12, 4).nullable()
    val usdRate = decimal("usd_rate", 20, 12).nullable()
    val usdRateAt = timestampWithTimeZone("usd_rate_at").nullable()
    val effectiveFrom = timestampWithTimeZone("effective_from")
    val effectiveTo = timestampWithTimeZone("effective_to").nullable()
    val dataSource = varchar("data_source", 32)
    val recordedBy = long("recorded_by").references(AppUserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object CurrencyRateTable : Table("currency_rate") {
    val id = long("id").autoIncrement()
    val currency = char("currency", 3)
    val rateToUsd = decimal("rate_to_usd", 20, 12)
    val sourceRateAt = timestampWithTimeZone("source_rate_at")
    val fetchedAt = timestampWithTimeZone("fetched_at")

    // Named `sourceName`, not `source` -- `Table`/`ColumnSet` already expose a member called
    // `source` (used internally for join/alias description), so the obvious name collides.
    val sourceName = varchar("source", 64)

    override val primaryKey = PrimaryKey(id)
}
