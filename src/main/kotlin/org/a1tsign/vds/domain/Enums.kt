package org.a1tsign.vds.domain

/**
 * Mirrors the `ck_provider_status` CHECK constraint on `provider.status` in
 * `V1__core_schema.sql`. Same spelling, same order -- keep the two in lockstep
 * (`SchemaEnumParityTest` proves it on every build).
 */
enum class ProviderStatus { ACTIVE, INACTIVE, UNKNOWN }

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
 */
private val rolePrivilegeOrder = listOf(Role.ADMIN, Role.EDITOR, Role.VIEWER)

fun Role.includes(other: Role): Boolean =
    rolePrivilegeOrder.indexOf(this) <= rolePrivilegeOrder.indexOf(other)
