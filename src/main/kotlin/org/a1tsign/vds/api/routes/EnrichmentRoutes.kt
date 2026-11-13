package org.a1tsign.vds.api.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.EnrichmentApplyRequest
import org.a1tsign.vds.api.dto.EnrichmentEvidenceDto
import org.a1tsign.vds.api.dto.EvidenceItemDto
import org.a1tsign.vds.api.dto.LegalFieldsRequest
import org.a1tsign.vds.api.dto.LegalFindingDto
import org.a1tsign.vds.api.dto.ValueFindingDto
import org.a1tsign.vds.api.dto.toDto
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.EnrichmentFacet
import org.a1tsign.vds.domain.EnrichmentOutcome
import org.a1tsign.vds.domain.FieldOrigin
import org.a1tsign.vds.domain.FindingConfidence
import org.a1tsign.vds.domain.FindingStatus
import org.a1tsign.vds.domain.LegalField
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.service.AutoLegalValue
import org.a1tsign.vds.service.EnrichmentCommand
import org.a1tsign.vds.service.EnrichmentService

private const val MAX_MESSAGE = 500
private const val MAX_EVIDENCE = 3
private const val MAX_ALTERNATIVES = 3
private const val MAX_BASIS = 10
private const val MAX_LABEL = 64
private const val MAX_SNIPPET = 220
private const val MAX_PAYMENT_METHODS = 20
private const val MAX_LOCATIONS = 25

fun Route.enrichmentRoutes(service: EnrichmentService) {
    put("/api/v1/providers/{id}/enrichment") {
        call.requireRole(Role.EDITOR)
        val command = validateEnrichment(call.receive<EnrichmentApplyRequest>())
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Provider not found")
        call.respond(service.apply(id, command))
    }

    post("/api/v1/providers/{id}/legal-info/confirm") {
        call.requireRole(Role.EDITOR)
        val fields = validateLegalFields(call.receive<LegalFieldsRequest>())
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Provider not found")
        call.respond(service.confirm(id, fields).toDto())
    }

    post("/api/v1/providers/{id}/legal-info/release") {
        call.requireRole(Role.EDITOR)
        val fields = validateLegalFields(call.receive<LegalFieldsRequest>())
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Provider not found")
        call.respond(service.release(id, fields).toDto())
    }
}

private fun validateLegalFields(req: LegalFieldsRequest): Set<LegalField> {
    val v = Validation()
    if (req.fields.isEmpty()) v.reject("fields", "must not be empty")
    val fields = req.fields.mapNotNull { v.enumValue("fields", it, LegalField.entries.toTypedArray()) }.toSet()
    v.throwIfInvalid()
    return fields
}

/** Validates the whole body before anything is written: one failure anywhere is a `400` and no writes. */
private fun validateEnrichment(req: EnrichmentApplyRequest): EnrichmentCommand {
    val v = Validation()
    val outcome = v.enumValue("outcome", req.outcome, EnrichmentOutcome.entries.toTypedArray())
    if (outcome != null && !outcome.isAttempt) {
        v.reject("outcome", "must be one of ${EnrichmentOutcome.entries.filter { it.isAttempt }.joinToString { it.name }}")
    }
    val message = req.message?.let { v.maxLength("message", it, MAX_MESSAGE) }
    if (req.pagesFetched < 0) v.reject("pagesFetched", "must not be negative")
    val observed = req.observedFacets.mapNotNull { v.enumValue("observedFacets", it, EnrichmentFacet.entries.toTypedArray()) }.toSet()

    val hasFacets = req.legalEntityName != null || req.registrationCountry != null ||
        req.paymentMethods.isNotEmpty() || req.locations.isNotEmpty()
    if (hasFacets && outcome != null && outcome != EnrichmentOutcome.SUCCESS) {
        v.reject("outcome", "facets are allowed only when outcome is SUCCESS")
    }

    val entity = req.legalEntityName?.let { finding ->
        v.requireNonBlank("legalEntityName.value", finding.value)
        v.maxLength("legalEntityName.value", finding.value, 255)
        validateLegalFinding(v, "legalEntityName", finding)
    }
    val country = req.registrationCountry?.let { finding ->
        v.validateCountryCode("registrationCountry.value", finding.value)
        validateLegalFinding(v, "registrationCountry", finding)
    }

    if (req.paymentMethods.size > MAX_PAYMENT_METHODS) v.reject("paymentMethods", "must have at most $MAX_PAYMENT_METHODS items")
    v.rejectDuplicates("paymentMethods.value", req.paymentMethods.map { it.value })
    val methods = req.paymentMethods.mapIndexedNotNull { i, finding ->
        val method = v.enumValue("paymentMethods[$i].value", finding.value, PaymentMethod.entries.toTypedArray())
        val confidence = validateValueFinding(v, "paymentMethods[$i]", finding)
        if (method != null && confidence != null) method to confidence else null
    }

    if (req.locations.size > MAX_LOCATIONS) v.reject("locations", "must have at most $MAX_LOCATIONS items")
    v.rejectDuplicates("locations.value", req.locations.map { it.value })
    val countries = req.locations.mapIndexedNotNull { i, finding ->
        val code = v.validateCountryCode("locations[$i].value", finding.value)
        val confidence = validateValueFinding(v, "locations[$i]", finding)
        if (code != null && confidence != null) code to confidence else null
    }

    v.throwIfInvalid()
    return EnrichmentCommand(
        outcome = outcome!!,
        message = message,
        pagesFetched = req.pagesFetched,
        observedFacets = observed,
        legalEntityName = entity,
        registrationCountry = country,
        paymentMethods = methods,
        locations = countries,
        evidence = EnrichmentEvidenceDto(req.legalEntityName, req.registrationCountry, req.paymentMethods, req.locations),
    )
}

private fun validateLegalFinding(v: Validation, field: String, finding: LegalFindingDto): AutoLegalValue? {
    val status = v.enumValue("$field.status", finding.status, FindingStatus.entries.toTypedArray())
    if (finding.basis.size > MAX_BASIS) v.reject("$field.basis", "must have at most $MAX_BASIS items")
    finding.basis.forEach { v.maxLength("$field.basis", it, MAX_LABEL) }
    if (finding.alternatives.size > MAX_ALTERNATIVES) v.reject("$field.alternatives", "must have at most $MAX_ALTERNATIVES items")
    finding.alternatives.forEach { v.maxLength("$field.alternatives", it, 255) }
    validateEvidence(v, field, finding.evidence)
    val origin = when (status) {
        FindingStatus.CONFIRMED -> FieldOrigin.AUTO_CONFIRMED
        FindingStatus.PRESUMED -> FieldOrigin.AUTO_PRESUMED
        null -> return null
    }
    return AutoLegalValue(finding.value, origin, finding.evidence.firstOrNull()?.url)
}

private fun validateValueFinding(v: Validation, field: String, finding: ValueFindingDto): FindingConfidence? {
    if (finding.pages < 0) v.reject("$field.pages", "must not be negative")
    validateEvidence(v, field, finding.evidence)
    return v.enumValue("$field.confidence", finding.confidence, FindingConfidence.entries.toTypedArray())
}

private fun validateEvidence(v: Validation, field: String, evidence: List<EvidenceItemDto>) {
    if (evidence.size > MAX_EVIDENCE) v.reject("$field.evidence", "must have at most $MAX_EVIDENCE items")
    evidence.forEachIndexed { i, item ->
        v.validateUrl("$field.evidence[$i].url", item.url)
        v.requireNonBlank("$field.evidence[$i].kind", item.kind)
        v.maxLength("$field.evidence[$i].kind", item.kind, MAX_LABEL)
        v.maxLength("$field.evidence[$i].snippet", item.snippet, MAX_SNIPPET)
    }
}
