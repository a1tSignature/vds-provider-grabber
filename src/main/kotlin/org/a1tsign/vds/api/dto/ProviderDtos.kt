package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.*
import java.time.Instant

/**
 * `systemStatus` is optional and additive (the precedent is vds-002's `dataSource` on the price
 * write): absent means `CONFIGURED` on `POST` and **leave unchanged** on `PUT`, so no vds-001/vds-002
 * request body has to change and an ordinary update cannot promote a candidate by omission.
 */
@Serializable data class ProviderWriteRequest(val slug: String, val name: String, val websiteUrl: String, val description: String? = null, val status: String, val systemStatus: String? = null)
@Serializable data class ProviderSystemStatusWriteRequest(val systemStatus: String)
@Serializable data class LegalInfoWriteRequest(val legalEntityName: String? = null, val registrationCountry: String? = null, val registrationNumber: String? = null, val legalAddress: String? = null, val dataSource: String, val confidence: String, val sourceUrl: String? = null, @Contextual val verifiedAt: Instant? = null)
@Serializable data class PaymentMethodWriteRequest(val method: String, val note: String? = null)
@Serializable data class ProviderDto(val id: Long, val slug: String, val name: String, val websiteUrl: String, val description: String?, val status: ProviderStatus, val systemStatus: SystemStatus, @Contextual val createdAt: Instant, @Contextual val updatedAt: Instant)
@Serializable data class LegalInfoDto(val providerId: Long, val legalEntityName: String?, val registrationCountry: String?, val registrationNumber: String?, val legalAddress: String?, val dataSource: LegalDataSource, val confidence: Confidence, val sourceUrl: String?, @Contextual val verifiedAt: Instant?, @Contextual val updatedAt: Instant)
@Serializable data class ProviderPaymentMethodDto(val method: PaymentMethod, val note: String?)

fun Provider.toDto() = ProviderDto(id, slug, name, websiteUrl, description, status, systemStatus, createdAt, updatedAt)
fun LegalInfo.toDto() = LegalInfoDto(providerId, legalEntityName, registrationCountry, registrationNumber, legalAddress, dataSource, confidence, sourceUrl, verifiedAt, updatedAt)
fun ProviderPaymentMethod.toDto() = ProviderPaymentMethodDto(method, note)
