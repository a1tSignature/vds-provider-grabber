package org.a1tsign.vds.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Flyway owns the schema and constraints. These Exposed declarations map the columns used by queries. */
object ProviderTable : Table("provider") {
    val id = long("id").autoIncrement()
    val slug = varchar("slug", 64)
    val name = varchar("name", 255)
    val websiteUrl = text("website_url")
    val description = text("description").nullable()
    val status = varchar("status", 32)

    val systemStatus = varchar("system_status", 32)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

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
    val legalEntityNameOrigin = varchar("legal_entity_name_origin", 16).nullable()
    val registrationCountryOrigin = varchar("registration_country_origin", 16).nullable()

    override val primaryKey = PrimaryKey(providerId)
}

object ProviderPaymentMethodTable : Table("provider_payment_method") {
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val method = varchar("method", 32)
    val note = text("note").nullable()
    val origin = varchar("origin", 16)

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
    val origin = varchar("origin", 16)

    override val primaryKey = PrimaryKey(id)
}

/** 0..1 per provider; `evidence` is an `EnrichmentEvidenceDto` serialised as JSON. */
object ProviderEnrichmentTable : Table("provider_enrichment") {
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val lastAttemptAt = timestampWithTimeZone("last_attempt_at")
    val lastOutcome = varchar("last_outcome", 32)
    val lastMessage = varchar("last_message", 500).nullable()
    val lastPagesFetched = integer("last_pages_fetched")
    val lastSuccessAt = timestampWithTimeZone("last_success_at").nullable()
    val evidence = text("evidence").nullable()

    override val primaryKey = PrimaryKey(providerId)
}

object ProviderEnrichmentDismissalTable : Table("provider_enrichment_dismissal") {
    val providerId = long("provider_id").references(ProviderTable.id, onDelete = ReferenceOption.CASCADE)
    val facet = varchar("facet", 32)
    val value = varchar("value", 32)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(providerId, facet, value)
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

/** Both foreign keys include provider_id so the database rejects links across providers even if callers omit the ownership check. */
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
    val lastConfirmedAt = timestampWithTimeZone("last_confirmed_at")

    override val primaryKey = PrimaryKey(id)
}

object CurrencyRateTable : Table("currency_rate") {
    val id = long("id").autoIncrement()
    val currency = char("currency", 3)
    val rateToUsd = decimal("rate_to_usd", 20, 12)
    val sourceRateAt = timestampWithTimeZone("source_rate_at")
    val fetchedAt = timestampWithTimeZone("fetched_at")

    // Exposed already uses source for table and join metadata.
    val sourceName = varchar("source", 64)

    override val primaryKey = PrimaryKey(id)
}
