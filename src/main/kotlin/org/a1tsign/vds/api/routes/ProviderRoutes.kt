package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.*
import org.a1tsign.vds.api.pageRequest
import org.a1tsign.vds.api.pageResponse
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.db.ProviderListFilter
import org.a1tsign.vds.domain.*
import org.a1tsign.vds.service.CatalogQueryService
import org.a1tsign.vds.service.ProviderService

fun Route.providerRoutes(service: ProviderService, queryService: CatalogQueryService) {
    post("/api/v1/providers") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<ProviderWriteRequest>()
        val v = Validation()
        val data = validateProvider(req, v)
        // Optional and defaulted to CONFIGURED: a vds-001 create body stays valid and visible.
        val systemStatus = req.systemStatus?.let { v.enumValue("systemStatus", it, SystemStatus.entries.toTypedArray()) }
        v.throwIfInvalid()
        val result = service.create(
            data.slug!!, data.name!!, data.url!!, req.description, data.status!!,
            systemStatus ?: SystemStatus.CONFIGURED,
        )
        call.response.header("Location", "/api/v1/providers/${result.id}")
        call.respond(HttpStatusCode.Created, result.toDto())
    }

    put("/api/v1/providers/{id}") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<ProviderWriteRequest>()
        val v = Validation()
        val data = validateProvider(req, v)
        // Absent = leave unchanged, never "default to CONFIGURED": otherwise an ordinary field
        // update by the crawler would promote a candidate as a side effect.
        val systemStatus = req.systemStatus?.let { v.enumValue("systemStatus", it, SystemStatus.entries.toTypedArray()) }
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(
            service.update(id, data.slug!!, data.name!!, data.url!!, req.description, data.status!!, systemStatus).toDto(),
        )
    }

    put("/api/v1/providers/{id}/system-status") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<ProviderSystemStatusWriteRequest>()
        val v = Validation()
        val systemStatus = v.enumValue("systemStatus", req.systemStatus, SystemStatus.entries.toTypedArray())
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(service.setSystemStatus(id, systemStatus!!).toDto())
    }

    delete("/api/v1/providers/{id}") {
        call.requireRole(Role.EDITOR)
        val id = providerId(call.parameters["id"])
        service.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/v1/providers/{id}/legal-info") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<LegalInfoWriteRequest>()
        val v = Validation()
        val entity = req.legalEntityName?.also { v.requireNonBlank("legalEntityName", it); v.maxLength("legalEntityName", it, 255) }
        val country = req.registrationCountry?.let { v.validateCountryCode("registrationCountry", it) }
        val number = req.registrationNumber?.let { v.maxLength("registrationNumber", it, 128) }
        val address = req.legalAddress
        val source = v.enumValue("dataSource", req.dataSource, LegalDataSource.entries.toTypedArray())
        val confidence = v.enumValue("confidence", req.confidence, Confidence.entries.toTypedArray())
        val url = req.sourceUrl?.let { v.validateUrl("sourceUrl", it) }
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(service.putLegalInfo(id, entity, country, number, address, source!!, confidence!!, url, req.verifiedAt).toDto())
    }

    put("/api/v1/providers/{id}/payment-methods") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<List<PaymentMethodWriteRequest>>()
        val v = Validation()
        val methods = req.mapNotNull { row -> v.enumValue("method", row.method, PaymentMethod.entries.toTypedArray())?.let { it to row.note } }
        v.rejectDuplicates("method", req.map { it.method })
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(service.putPaymentMethods(id, methods).map { it.toDto() })
    }

    // ---- Read endpoints (task vds-001.15) -- any authenticated role (VIEWER included) ----

    get("/api/v1/providers") {
        call.requireRole(Role.VIEWER)
        val page = call.pageRequest(setOf("id", "name", "slug", "status", "createdAt", "minMonthlyPriceUsd"))
        val query = call.request.queryParameters
        val v = Validation()
        val status = query["status"]?.let { v.enumValue("status", it, ProviderStatus.entries.toTypedArray()) }
        val registrationCountry = query["registrationCountry"]?.let { v.validateCountryCode("registrationCountry", it) }
        val locationCountry = query["locationCountry"]?.let { v.validateCountryCode("locationCountry", it) }
        val paymentMethods = query.getAll("paymentMethod").orEmpty()
            .mapNotNull { v.enumValue("paymentMethod", it, PaymentMethod.entries.toTypedArray()) }
        val hasActiveTariffs = parseOptionalBoolean(v, "hasActiveTariffs", query["hasActiveTariffs"])
        // Repeatable, like paymentMethod; **absent means CONFIGURED only**, so a discovered
        // candidate never appears in the default catalog listing.
        val requestedSystemStatuses = query.getAll("systemStatus").orEmpty()
        val systemStatuses = requestedSystemStatuses
            .mapNotNull { v.enumValue("systemStatus", it, SystemStatus.entries.toTypedArray()) }
            .ifEmpty { listOf(SystemStatus.CONFIGURED) }
        v.throwIfInvalid()
        val filter = ProviderListFilter(
            q = query["q"],
            status = status,
            registrationCountry = registrationCountry,
            locationCountry = locationCountry,
            paymentMethods = paymentMethods,
            hasActiveTariffs = hasActiveTariffs,
            systemStatuses = systemStatuses,
        )
        val (total, items) = queryService.listProviders(filter, page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, items))
    }

    get("/api/v1/providers/{id}") {
        call.requireRole(Role.VIEWER)
        val id = providerId(call.parameters["id"])
        call.respond(queryService.getProvider(id))
    }

    get("/api/v1/providers/{id}/tariffs") {
        call.requireRole(Role.VIEWER)
        val id = providerId(call.parameters["id"])
        val page = call.pageRequest(setOf("id", "name", "createdAt"))
        val v = Validation()
        val activeOnly = parseOptionalBoolean(v, "activeOnly", call.request.queryParameters["activeOnly"]) ?: false
        v.throwIfInvalid()
        val (total, items) = queryService.listProviderTariffs(id, activeOnly, page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, items.map { it.toDto() }))
    }
}

private data class ProviderFields(val slug: String?, val name: String?, val url: String?, val status: ProviderStatus?)

private fun validateProvider(req: ProviderWriteRequest, v: Validation): ProviderFields {
    val slug = v.validateSlug("slug", req.slug)
    v.requireNonBlank("name", req.name)
    val name = v.maxLength("name", req.name, 255)
    val url = v.validateUrl("websiteUrl", req.websiteUrl)
    val status = v.enumValue("status", req.status, ProviderStatus.entries.toTypedArray())
    return ProviderFields(slug, name, url, status)
}

private fun providerId(raw: String?) = raw?.toLongOrNull() ?: throw NotFoundException("Provider not found")

/** `true`/`false`/absent; anything else is recorded on [v] as a field error and `null` is returned. */
private fun parseOptionalBoolean(v: Validation, field: String, raw: String?): Boolean? = when (raw) {
    null -> null
    "true" -> true
    "false" -> false
    else -> { v.reject(field, "must be true or false"); null }
}
