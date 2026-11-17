package org.a1tsign.vds.domain

/**
 * Mirrors the `ck_provider_status` CHECK constraint on `provider.status` in
 * `V1__core_schema.sql`. Same spelling, same order -- keep the two in lockstep
 * (`SchemaEnumParityTest` proves it on every build).
 */
enum class ProviderStatus { ACTIVE, INACTIVE, UNKNOWN }

/**
 * Mirrors the `ck_provider_system_status` CHECK on `provider.system_status` in
 * `V2__provider_system_status.sql` -- same spelling, same order (`SchemaEnumParityTest` proves it).
 *
 * **Independent of [ProviderStatus]**: that one is a claim about the provider's *business*, this one
 * is a claim about *our own* relationship to the row -- has anybody described how to read this site.
 * No combination is forbidden and neither field is derived from the other; a discovered candidate is
 * created as `status = UNKNOWN` + `systemStatus = CANDIDATE`.
 *
 * - [CANDIDATE] -- discovered, not configured. Visible in the public catalog.
 * - [CONFIGURED] -- a normal catalog member. Set automatically by the **first** crawl run for that
 *   slug that reaches `SUCCESS`/`PARTIAL` **and** performed at least one write.
 * - [REJECTED] -- terminal "won't do". Set only by a human, hidden from the public catalog, and
 *   never promoted automatically.
 */
enum class SystemStatus { CANDIDATE, CONFIGURED, REJECTED }

val PUBLIC_CATALOG_SYSTEM_STATUSES = setOf(SystemStatus.CONFIGURED, SystemStatus.CANDIDATE)

/** Mirrors `ck_provider_legal_info_data_source` on `provider_legal_info.data_source`. */
enum class LegalDataSource { MANUAL, CRAWLER, WHOIS, PROVIDER_SITE, UNKNOWN }

/** Mirrors `ck_provider_legal_info_confidence` on `provider_legal_info.confidence`. */
enum class Confidence { HIGH, MEDIUM, LOW, UNVERIFIED }

/**
 * Mirrors `ck_provider_payment_method_method` on `provider_payment_method.method` -- exactly
 * these 20 values, extensible only by a new migration (see the comment next to that constraint).
 *
 * Semantics, since the raw constant names don't carry them:
 * - [VISA_MASTERCARD] / [AMERICAN_EXPRESS] / [UNIONPAY] -- international card acquiring.
 * - [MIR] / [SBP] / [RU_BANK_TRANSFER] -- Russian payment rails.
 * - [CRYPTO_BTC] / [CRYPTO_ETH] / [CRYPTO_USDT] -- specific coins; [CRYPTO_OTHER] is any other
 *   cryptocurrency not worth its own constant.
 * - [OTHER] -- anything not yet modelled by a dedicated constant; the free-text description goes
 *   into `provider_payment_method.note`, never encoded into this enum.
 */
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

// The enums below are wire-only: no column stores them.

/** What an enrichment result can carry; also the vocabulary of `observedFacets`. */
enum class EnrichmentFacet { LEGAL_ENTITY_NAME, REGISTRATION_COUNTRY, PAYMENT_METHOD, LOCATION }

/** The two legal fields that carry an origin. */
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

/**
 * Parses a `VARCHAR` value read from an enum-like column into its Kotlin enum. Deliberately not
 * `enumerationByName`/`customEnumeration` on the Exposed [org.jetbrains.exposed.v1.core.Table]
 * side -- their failure message doesn't name the column, which makes a bad row hard to track down.
 * Used by every mapper in `db/RowMappers.kt`.
 *
 * An unknown value is a sign the database and the Kotlin mirror have drifted apart; it must fail
 * loudly, never fall back to `null` or a default.
 *
 * @throws IllegalStateException naming [table], [column] and the offending [raw] value.
 */
inline fun <reified E : Enum<E>> parseDbEnum(table: String, column: String, raw: String): E =
    enumValues<E>().firstOrNull { it.name == raw } ?: throw IllegalStateException(
        "Unknown value '$raw' for $table.$column; expected one of " +
            enumValues<E>().joinToString { it.name },
    )

/**
 * Explicit privilege ordering `ADMIN ⊃ EDITOR ⊃ VIEWER`, expressed once here so auth code checks
 * `role.includes(required)` instead of duplicating role sets at each call site. Every role
 * includes itself.
 *
 * Backed by a direct `ordinal` comparison on [Role]'s own declaration order
 * (`ADMIN, EDITOR, VIEWER`, matching the required privilege order) rather than a separate
 * `rolePrivilegeOrder` list searched with `indexOf`: `List.indexOf` returns `-1` for an element
 * not in the list, which would make `includes()` fail *open* (`-1 <= anything` is `true`) for any
 * future `Role` constant added to the enum without also being added to that list -- exactly the
 * kind of "two lists expressing the same thing" duplication this helper exists to avoid. `ordinal`
 * has no such failure mode: every enum constant has one by construction.
 * `SchemaEnumParityTest` pins the enum's declaration order explicitly so a future reordering
 * (which would silently change this method's meaning) fails loudly instead of silently.
 */
fun Role.includes(other: Role): Boolean = this.ordinal <= other.ordinal
