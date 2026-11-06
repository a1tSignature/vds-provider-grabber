package org.a1tsign.vds.db

import org.a1tsign.vds.domain.AppUser
import org.a1tsign.vds.domain.CurrencyRate
import org.a1tsign.vds.domain.LegalInfo
import org.a1tsign.vds.domain.Location
import org.a1tsign.vds.domain.Provider
import org.a1tsign.vds.domain.ProviderPaymentMethod
import org.a1tsign.vds.domain.Tariff
import org.a1tsign.vds.domain.TariffPrice
import org.a1tsign.vds.domain.parseDbEnum
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * One mapper per entity: `ResultRow` -> framework-free `domain.*` model. Every `OffsetDateTime`
 * column becomes an [java.time.Instant] via the plain `.toInstant()` (the reverse direction,
 * `Instant.toUtcOffset()`, already exists in `config/TimeSource.kt`); every enum-like `varchar`
 * column goes through [parseDbEnum], never a silent fallback.
 */

fun ResultRow.toProvider(): Provider =
    Provider(
        id = this[ProviderTable.id],
        slug = this[ProviderTable.slug],
        name = this[ProviderTable.name],
        websiteUrl = this[ProviderTable.websiteUrl],
        description = this[ProviderTable.description],
        status = parseDbEnum("provider", "status", this[ProviderTable.status]),
        systemStatus = parseDbEnum("provider", "system_status", this[ProviderTable.systemStatus]),
        createdAt = this[ProviderTable.createdAt].toInstant(),
        updatedAt = this[ProviderTable.updatedAt].toInstant(),
    )

fun ResultRow.toLegalInfo(): LegalInfo =
    LegalInfo(
        providerId = this[ProviderLegalInfoTable.providerId],
        legalEntityName = this[ProviderLegalInfoTable.legalEntityName],
        registrationCountry = this[ProviderLegalInfoTable.registrationCountry],
        registrationNumber = this[ProviderLegalInfoTable.registrationNumber],
        legalAddress = this[ProviderLegalInfoTable.legalAddress],
        dataSource = parseDbEnum(
            "provider_legal_info",
            "data_source",
            this[ProviderLegalInfoTable.dataSource],
        ),
        confidence = parseDbEnum("provider_legal_info", "confidence", this[ProviderLegalInfoTable.confidence]),
        sourceUrl = this[ProviderLegalInfoTable.sourceUrl],
        verifiedAt = this[ProviderLegalInfoTable.verifiedAt]?.toInstant(),
        updatedAt = this[ProviderLegalInfoTable.updatedAt].toInstant(),
    )

fun ResultRow.toProviderPaymentMethod(): ProviderPaymentMethod =
    ProviderPaymentMethod(
        providerId = this[ProviderPaymentMethodTable.providerId],
        method = parseDbEnum("provider_payment_method", "method", this[ProviderPaymentMethodTable.method]),
        note = this[ProviderPaymentMethodTable.note],
    )

fun ResultRow.toLocation(): Location =
    Location(
        id = this[LocationTable.id],
        providerId = this[LocationTable.providerId],
        countryCode = this[LocationTable.countryCode],
        region = this[LocationTable.region],
        city = this[LocationTable.city],
        datacenterName = this[LocationTable.datacenterName],
        latitude = this[LocationTable.latitude],
        longitude = this[LocationTable.longitude],
        createdAt = this[LocationTable.createdAt].toInstant(),
        updatedAt = this[LocationTable.updatedAt].toInstant(),
    )

fun ResultRow.toTariff(): Tariff =
    Tariff(
        id = this[TariffTable.id],
        providerId = this[TariffTable.providerId],
        externalId = this[TariffTable.externalId],
        name = this[TariffTable.name],
        url = this[TariffTable.url],
        vcpuCount = this[TariffTable.vcpuCount],
        cpuModel = this[TariffTable.cpuModel],
        ramMb = this[TariffTable.ramMb],
        diskGb = this[TariffTable.diskGb],
        diskType = parseDbEnum("tariff", "disk_type", this[TariffTable.diskType]),
        bandwidthMbps = this[TariffTable.bandwidthMbps],
        trafficLimitGb = this[TariffTable.trafficLimitGb],
        ipv4Count = this[TariffTable.ipv4Count],
        ipv6Supported = this[TariffTable.ipv6Supported],
        virtualization = parseDbEnum("tariff", "virtualization", this[TariffTable.virtualization]),
        isActive = this[TariffTable.isActive],
        inStock = this[TariffTable.inStock],
        createdAt = this[TariffTable.createdAt].toInstant(),
        updatedAt = this[TariffTable.updatedAt].toInstant(),
    )

fun ResultRow.toTariffPrice(): TariffPrice =
    TariffPrice(
        id = this[TariffPriceTable.id],
        tariffId = this[TariffPriceTable.tariffId],
        billingPeriod = parseDbEnum("tariff_price", "billing_period", this[TariffPriceTable.billingPeriod]),
        amount = this[TariffPriceTable.amount],
        currency = this[TariffPriceTable.currency],
        amountUsd = this[TariffPriceTable.amountUsd],
        usdRate = this[TariffPriceTable.usdRate],
        usdRateAt = this[TariffPriceTable.usdRateAt]?.toInstant(),
        effectiveFrom = this[TariffPriceTable.effectiveFrom].toInstant(),
        effectiveTo = this[TariffPriceTable.effectiveTo]?.toInstant(),
        dataSource = parseDbEnum("tariff_price", "data_source", this[TariffPriceTable.dataSource]),
        recordedBy = this[TariffPriceTable.recordedBy],
        createdAt = this[TariffPriceTable.createdAt].toInstant(),
    )

fun ResultRow.toCurrencyRate(): CurrencyRate =
    CurrencyRate(
        id = this[CurrencyRateTable.id],
        currency = this[CurrencyRateTable.currency],
        rateToUsd = this[CurrencyRateTable.rateToUsd],
        sourceRateAt = this[CurrencyRateTable.sourceRateAt].toInstant(),
        fetchedAt = this[CurrencyRateTable.fetchedAt].toInstant(),
        source = this[CurrencyRateTable.sourceName],
    )

fun ResultRow.toAppUser(): AppUser =
    AppUser(
        id = this[AppUserTable.id],
        username = this[AppUserTable.username],
        email = this[AppUserTable.email],
        passwordHash = this[AppUserTable.passwordHash],
        status = parseDbEnum("app_user", "status", this[AppUserTable.status]),
        tokenVersion = this[AppUserTable.tokenVersion],
        createdAt = this[AppUserTable.createdAt].toInstant(),
        updatedAt = this[AppUserTable.updatedAt].toInstant(),
        lastLoginAt = this[AppUserTable.lastLoginAt]?.toInstant(),
    )
