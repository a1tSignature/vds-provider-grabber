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
import org.a1tsign.vds.auth.requireAuthenticatedRole
import org.a1tsign.vds.auth.requireCrawlerIdentity
import org.a1tsign.vds.db.ProviderListFilter
import org.a1tsign.vds.domain.*
import org.a1tsign.vds.service.CatalogQueryService
import org.a1tsign.vds.service.LegalInfoWrite
import org.a1tsign.vds.service.ProviderService

fun Route.providerRoutes(service: ProviderService, queryService: CatalogQueryService) {
    post("/api/v1/providers") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val req = call.receive<ProviderWriteRequest>()
        val v = Validation()
        val data = validateProvider(req, v)
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
        call.requireAuthenticatedRole(Role.EDITOR)
        val req = call.receive<ProviderWriteRequest>()
        val v = Validation()
        val data = validateProvider(req, v)
        // Omission leaves the status unchanged so ordinary crawler writes cannot promote candidates.
        val systemStatus = req.systemStatus?.let { v.enumValue("systemStatus", it, SystemStatus.entries.toTypedArray()) }
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(
            service.update(id, data.slug!!, data.name!!, data.url!!, req.description, data.status!!, systemStatus).toDto(),
        )
    }

    put("/api/v1/providers/{id}/system-status") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val req = call.receive<ProviderSystemStatusWriteRequest>()
        val v = Validation()
        val systemStatus = v.enumValue("systemStatus", req.systemStatus, SystemStatus.entries.toTypedArray())
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(service.setSystemStatus(id, systemStatus!!).toDto())
    }

    delete("/api/v1/providers/{id}") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val id = providerId(call.parameters["id"])
        service.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/v1/providers/{id}/legal-info") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val req = call.receive<LegalInfoWriteRequest>()
        val v = Validation()
        val mode = parseWriteMode(v, req.writeMode)
        val entity = req.legalEntityName?.also { v.requireNonBlank("legalEntityName", it); v.maxLength("legalEntityName", it, 255) }
        val country = req.registrationCountry?.let { v.validateCountryCode("registrationCountry", it) }
        val number = req.registrationNumber?.let { v.maxLength("registrationNumber", it, 128) }
        val address = req.legalAddress
        val source = v.enumValue("dataSource", req.dataSource, LegalDataSource.entries.toTypedArray())
        val confidence = v.enumValue("confidence", req.confidence, Confidence.entries.toTypedArray())
        val url = req.sourceUrl?.let { v.validateUrl("sourceUrl", it) }
        val entityOrigin = parseFieldOrigin(v, "legalEntityNameOrigin", req.legalEntityNameOrigin, req.legalEntityName, mode)
        val countryOrigin = parseFieldOrigin(v, "registrationCountryOrigin", req.registrationCountryOrigin, req.registrationCountry, mode)
        if (mode == WriteMode.AUTOMATIC && req.verifiedAt != null) v.reject("verifiedAt", "must be absent when writeMode is AUTOMATIC")
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        val write = LegalInfoWrite(entity, country, number, address, source!!, confidence!!, url, req.verifiedAt, entityOrigin, countryOrigin)
        call.respond(service.putLegalInfo(id, write, mode!!).toDto())
    }

    // The body is a JSON array, so the write mode travels as `?writeMode=`.
    put("/api/v1/providers/{id}/payment-methods") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val req = call.receive<List<PaymentMethodWriteRequest>>()
        val v = Validation()
        val mode = parseWriteMode(v, call.request.queryParameters["writeMode"])
        val methods = req.mapNotNull { row -> v.enumValue("method", row.method, PaymentMethod.entries.toTypedArray())?.let { it to row.note } }
        v.rejectDuplicates("method", req.map { it.method })
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(service.putPaymentMethods(id, methods, mode!!).map { it.toDto() })
    }

    providerListReadRoute("/api/v1/providers", queryService, includeRejected = false)

    get("/api/v1/providers/{id}") {
        val id = providerId(call.parameters["id"])
        call.respond(queryService.getProvider(id))
    }

    get("/api/v1/providers/{id}/tariffs") {
        val id = providerId(call.parameters["id"])
        val page = call.pageRequest(setOf("id", "name", "createdAt"))
        val v = Validation()
        val activeOnly = parseOptionalBoolean(v, "activeOnly", call.request.queryParameters["activeOnly"]) ?: false
        v.throwIfInvalid()
        val (total, items) = queryService.listProviderTariffs(id, activeOnly, page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, items.map { it.toDto() }))
    }
}

fun Route.internalCatalogRoutes(queryService: CatalogQueryService, crawlerUsername: String?) {
    providerListReadRoute(
        "/api/v1/internal/catalog/providers",
        queryService,
        includeRejected = true,
        crawlerUsername = crawlerUsername,
    )

    get("/api/v1/internal/catalog/providers/{id}") {
        call.requireCrawlerIdentity(crawlerUsername)
        val id = providerId(call.parameters["id"])
        call.respond(queryService.getProviderForService(id))
    }

    get("/api/v1/internal/catalog/providers/{id}/tariffs") {
        call.requireCrawlerIdentity(crawlerUsername)
        val id = providerId(call.parameters["id"])
        val page = call.pageRequest(setOf("id", "name", "createdAt"))
        val v = Validation()
        val activeOnly = parseOptionalBoolean(v, "activeOnly", call.request.queryParameters["activeOnly"]) ?: false
        v.throwIfInvalid()
        val (total, items) = queryService.listProviderTariffsForService(id, activeOnly, page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, items.map { it.toDto() }))
    }
}

private fun Route.providerListReadRoute(
    path: String,
    queryService: CatalogQueryService,
    includeRejected: Boolean,
    crawlerUsername: String? = null,
) {
    get(path) {
        if (includeRejected) call.requireCrawlerIdentity(crawlerUsername)
        val page = call.pageRequest(setOf("id", "name", "slug", "status", "createdAt", "minMonthlyPriceUsd"))
        val query = call.request.queryParameters
        val v = Validation()
        val status = query["status"]?.let { v.enumValue("status", it, ProviderStatus.entries.toTypedArray()) }
        val registrationCountry = query["registrationCountry"]?.let { v.validateCountryCode("registrationCountry", it) }
        val locationCountry = query["locationCountry"]?.let { v.validateCountryCode("locationCountry", it) }
        val paymentMethods = query.getAll("paymentMethod").orEmpty()
            .mapNotNull { v.enumValue("paymentMethod", it, PaymentMethod.entries.toTypedArray()) }
        val hasActiveTariffs = parseOptionalBoolean(v, "hasActiveTariffs", query["hasActiveTariffs"])
        val requestedSystemStatuses = query.getAll("systemStatus").orEmpty()
        val systemStatuses = requestedSystemStatuses
            .mapNotNull { v.enumValue("systemStatus", it, SystemStatus.entries.toTypedArray()) }
        if (!includeRejected && SystemStatus.REJECTED in systemStatuses) {
            v.reject("systemStatus", "REJECTED is not available in the public catalog")
        }
        val defaultStatuses = if (includeRejected) SystemStatus.entries else PUBLIC_CATALOG_SYSTEM_STATUSES
        val filter = ProviderListFilter(
            q = query["q"],
            status = status,
            registrationCountry = registrationCountry,
            locationCountry = locationCountry,
            paymentMethods = paymentMethods,
            hasActiveTariffs = hasActiveTariffs,
            systemStatuses = systemStatuses.ifEmpty { defaultStatuses.toList() },
        )
        v.throwIfInvalid()
        val (total, items) = queryService.listProviders(filter, page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, items))
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

/** Absent means [WriteMode.HUMAN]. */
internal fun parseWriteMode(v: Validation, raw: String?): WriteMode? =
    if (raw == null) WriteMode.HUMAN else v.enumValue("writeMode", raw, WriteMode.entries.toTypedArray())

/** An `AUTO_*` origin needs a value to describe, and only a human may claim `MANUAL`. */
private fun parseFieldOrigin(v: Validation, field: String, raw: String?, value: String?, mode: WriteMode?): FieldOrigin? {
    val origin = raw?.let { v.enumValue(field, it, FieldOrigin.entries.toTypedArray()) } ?: return null
    if (origin != FieldOrigin.MANUAL && value == null) v.reject(field, "must be MANUAL or absent when the field has no value")
    if (origin == FieldOrigin.MANUAL && mode == WriteMode.AUTOMATIC) v.reject(field, "must not be MANUAL when writeMode is AUTOMATIC")
    return origin
}
