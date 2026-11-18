package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.EnrichmentOutcome
import org.a1tsign.vds.domain.PaymentMethod
import java.time.Instant

/** Evidence items are ordered strongest first; the first one is what `source_url` points at. */
@Serializable
data class EvidenceItemDto(val url: String, val kind: String, val snippet: String)

/** `status` is `CONFIRMED` or `PRESUMED`. */
@Serializable
data class LegalFindingDto(
    val value: String,
    val status: String,
    val basis: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val evidence: List<EvidenceItemDto> = emptyList(),
)

/** `confidence` is `HIGH`, `MEDIUM` or `LOW`; [pages] is how many fetched pages mentioned the value. */
@Serializable
data class ValueFindingDto(
    val value: String,
    val confidence: String,
    val pages: Int = 0,
    val evidence: List<EvidenceItemDto> = emptyList(),
)

/** Body of `PUT /api/v1/providers/{id}/enrichment`. Facets are allowed only with `outcome = SUCCESS`. */
@Serializable
data class EnrichmentApplyRequest(
    val outcome: String,
    val message: String? = null,
    val pagesFetched: Int = 0,
    val observedFacets: List<String> = emptyList(),
    val legalEntityName: LegalFindingDto? = null,
    val registrationCountry: LegalFindingDto? = null,
    val paymentMethods: List<ValueFindingDto> = emptyList(),
    val locations: List<ValueFindingDto> = emptyList(),
)

/** Evidence includes every finding from the latest successful enrichment, including findings skipped during writes. */
@Serializable
data class EnrichmentEvidenceDto(
    val legalEntityName: LegalFindingDto? = null,
    val registrationCountry: LegalFindingDto? = null,
    val paymentMethods: List<ValueFindingDto> = emptyList(),
    val locations: List<ValueFindingDto> = emptyList(),
)

/** What one apply wrote. Absent legal fields were left unchanged. */
@Serializable
data class EnrichmentApplyResultDto(
    val legalEntityName: String? = null,
    val registrationCountry: String? = null,
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val locations: List<String> = emptyList(),
    val valuesApplied: Int,
)

@Serializable
data class ProviderEnrichmentDto(
    @Contextual val lastAttemptAt: Instant,
    val lastOutcome: EnrichmentOutcome,
    val lastMessage: String? = null,
    val lastPagesFetched: Int,
    @Contextual val lastSuccessAt: Instant? = null,
    val evidence: EnrichmentEvidenceDto? = null,
)

@Serializable
data class LegalFieldsRequest(val fields: List<String>)
