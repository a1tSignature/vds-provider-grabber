package org.a1tsign.vds.domain

/** Keep names and order aligned with the ck_provider_status constraint. */
enum class ProviderStatus { ACTIVE, INACTIVE, UNKNOWN }

/**
 * Catalog relationship independent of [ProviderStatus]. Candidates are visible. A material crawl write can promote them.
 * Rejected providers are hidden and cannot be promoted automatically.
 */
enum class SystemStatus { CANDIDATE, CONFIGURED, REJECTED }

val PUBLIC_CATALOG_SYSTEM_STATUSES = setOf(SystemStatus.CONFIGURED, SystemStatus.CANDIDATE)

/** Mirrors `ck_provider_legal_info_data_source` on `provider_legal_info.data_source`. */
enum class LegalDataSource { MANUAL, CRAWLER, WHOIS, PROVIDER_SITE, UNKNOWN }

/** Mirrors `ck_provider_legal_info_confidence` on `provider_legal_info.confidence`. */
enum class Confidence { HIGH, MEDIUM, LOW, UNVERIFIED }

/** Mirrors ck_provider_payment_method_method. For OTHER, keep the free-text method description in the row's note. */
enum class PaymentMethod {
    VISA_MASTERCARD, AMERICAN_EXPRESS, UNIONPAY, MIR, SBP, RU_BANK_TRANSFER,
    BANK_WIRE, PAYPAL, ALIPAY, WECHAT_PAY, APPLE_PAY, GOOGLE_PAY, CRYPTO_BTC,
    CRYPTO_ETH, CRYPTO_USDT, CRYPTO_OTHER, WEBMONEY, PERFECT_MONEY, CASH, OTHER,
}

/**
 * Who stands behind a legal field's value. Mirrors `ck_provider_legal_info_legal_entity_name_origin`
 * and `ck_provider_legal_info_registration_country_origin` in `V4__site_enrichment.sql`.
 *
 * A `null` origin (no enum value) means nobody claimed the field. Automation may overwrite `null`
 * and `AUTO_*`, never [MANUAL] -- not even when the value itself is null.
 */
enum class FieldOrigin { AUTO_CONFIRMED, AUTO_PRESUMED, MANUAL }

/** Mirrors `ck_provider_payment_method_origin` and `ck_location_origin`. */
enum class RowOrigin { AUTO, MANUAL }

/**
 * Mirrors `ck_provider_enrichment_last_outcome`. [isAttempt] outcomes come from an actual visit to
 * the site; the others describe why a run did not attempt a provider.
 */
enum class EnrichmentOutcome(val isAttempt: Boolean) {
    SUCCESS(true), NOTHING_FOUND(true), BLOCKED(true), UNREACHABLE(true), ROBOTS_DISALLOWED(true),
    FAILED(true), TIMEOUT(true),
    DEFERRED(false), NOT_DUE(false), SKIPPED(false), NO_PROVIDER(false), DISABLED(false),
}

/** Mirrors `ck_provider_enrichment_dismissal_facet`. */
enum class DismissalFacet { PAYMENT_METHOD, LOCATION }


/** What an enrichment result can carry; also the vocabulary of `observedFacets`. */
enum class EnrichmentFacet { LEGAL_ENTITY_NAME, REGISTRATION_COUNTRY, PAYMENT_METHOD, LOCATION }

enum class LegalField { LEGAL_ENTITY_NAME, REGISTRATION_COUNTRY }

/** Strength of a legal finding; maps to [FieldOrigin.AUTO_CONFIRMED] / [FieldOrigin.AUTO_PRESUMED]. */
enum class FindingStatus { CONFIRMED, PRESUMED }

/** Strength of a payment-method or location finding; only `HIGH`/`MEDIUM` are ever written. */
enum class FindingConfidence { HIGH, MEDIUM, LOW }

/**
 * Who is writing through an ordinary write endpoint. Absent means [HUMAN], so a client that knows
 * nothing about provenance produces protected `MANUAL` data.
 */
enum class WriteMode { HUMAN, AUTOMATIC }

/** Mirrors `ck_tariff_disk_type` on `tariff.disk_type`. */
enum class DiskType { NVME, SSD, HDD, UNKNOWN }

/** Mirrors `ck_tariff_virtualization` on `tariff.virtualization`. */
enum class Virtualization { KVM, OPENVZ, LXC, XEN, HYPERV, UNKNOWN }

/** Mirrors `ck_tariff_price_billing_period` on `tariff_price.billing_period`. */
enum class BillingPeriod { HOURLY, MONTHLY, QUARTERLY, SEMIANNUAL, ANNUAL, BIENNIAL, TRIENNIAL }

/** Mirrors `ck_tariff_price_data_source` on `tariff_price.data_source`. */
enum class PriceDataSource { MANUAL, CRAWLER }

/** Mirrors `ck_app_user_status` on `app_user.status`. */
enum class UserStatus { ACTIVE, DISABLED }

/** Mirrors the seeded `role.code` values in `V1__core_schema.sql`. */
enum class Role { ADMIN, EDITOR, VIEWER }

/** Keep enum columns as VARCHAR so unknown values fail with the table, column and raw value in the error. */
inline fun <reified E : Enum<E>> parseDbEnum(table: String, column: String, raw: String): E =
    enumValues<E>().firstOrNull { it.name == raw } ?: throw IllegalStateException(
        "Unknown value '$raw' for $table.$column; expected one of " +
            enumValues<E>().joinToString { it.name },
    )

/** Keep [Role] in descending privilege order. The ordinal comparison relies on that ordering. */
fun Role.includes(other: Role): Boolean = this.ordinal <= other.ordinal
