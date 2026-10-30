package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.*
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.*
import org.a1tsign.vds.service.ProviderService

fun Route.providerRoutes(service: ProviderService) {
    post("/api/v1/providers") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<ProviderWriteRequest>()
        val v = Validation()
        val data = validateProvider(req, v)
        v.throwIfInvalid()
        val result = service.create(data.slug!!, data.name!!, data.url!!, req.description, data.status!!)
        call.response.header("Location", "/api/v1/providers/${result.id}")
        call.respond(HttpStatusCode.Created, result.toDto())
    }

    put("/api/v1/providers/{id}") {
        call.requireRole(Role.EDITOR)
        val req = call.receive<ProviderWriteRequest>()
        val v = Validation()
        val data = validateProvider(req, v)
        v.throwIfInvalid()
        val id = providerId(call.parameters["id"])
        call.respond(service.update(id, data.slug!!, data.name!!, data.url!!, req.description, data.status!!).toDto())
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
