package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.TariffWriteRequest
import org.a1tsign.vds.api.dto.toDto
import org.a1tsign.vds.api.pageRequest
import org.a1tsign.vds.api.pageResponse
import org.a1tsign.vds.auth.requireAuthenticatedRole
import org.a1tsign.vds.db.TariffSearchFilter
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.Virtualization
import org.a1tsign.vds.service.CatalogQueryService
import org.a1tsign.vds.service.TariffService
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun Route.tariffRoutes(service: TariffService, queryService: CatalogQueryService) {
    post("/api/v1/providers/{id}/tariffs") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val providerId = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Provider not found")
        val req = call.receive<TariffWriteRequest>()
        val fields = validateTariff(req)
        val created = service.create(
            providerId = providerId,
            externalId = fields.externalId,
            name = fields.name,
            url = fields.url,
            vcpuCount = fields.vcpuCount,
            cpuModel = fields.cpuModel,
            ramMb = fields.ramMb,
            diskGb = fields.diskGb,
            diskType = fields.diskType,
            bandwidthMbps = fields.bandwidthMbps,
            trafficLimitGb = fields.trafficLimitGb,
            ipv4Count = fields.ipv4Count,
            ipv6Supported = req.ipv6Supported,
            virtualization = fields.virtualization,
            isActive = req.isActive,
            inStock = req.inStock,
        )
        call.response.header("Location", "/api/v1/tariffs/${created.id}")
        call.respond(HttpStatusCode.Created, created.toDto())
    }

    put("/api/v1/tariffs/{id}") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val id = tariffId(call.parameters["id"])
        val req = call.receive<TariffWriteRequest>()
        val fields = validateTariff(req)
        val updated = service.update(
            id = id,
            externalId = fields.externalId,
            name = fields.name,
            url = fields.url,
            vcpuCount = fields.vcpuCount,
            cpuModel = fields.cpuModel,
            ramMb = fields.ramMb,
            diskGb = fields.diskGb,
            diskType = fields.diskType,
            bandwidthMbps = fields.bandwidthMbps,
            trafficLimitGb = fields.trafficLimitGb,
            ipv4Count = fields.ipv4Count,
            ipv6Supported = req.ipv6Supported,
            virtualization = fields.virtualization,
            isActive = req.isActive,
            inStock = req.inStock,
        )
        call.respond(updated.toDto())
    }

    delete("/api/v1/tariffs/{id}") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val id = tariffId(call.parameters["id"])
        service.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/v1/tariffs/{id}/locations") {
        call.requireAuthenticatedRole(Role.EDITOR)
        val id = tariffId(call.parameters["id"])
        val req = call.receive<List<Long>>()
        val v = Validation()
        v.rejectDuplicates("locationIds", req)
        v.throwIfInvalid()
        val result = service.replaceLocations(id, req)
        call.respond(HttpStatusCode.OK, result.sorted())
    }

    get("/api/v1/tariffs") {
        val page = call.pageRequest(setOf("id", "priceUsd", "ramMb", "vcpuCount", "diskGb", "name"))
        val query = call.request.queryParameters
        val v = Validation()
        val filter = parseTariffFilter(v, query)
        val billingPeriod = query["billingPeriod"]?.let { v.enumValue("billingPeriod", it, BillingPeriod.entries.toTypedArray()) }
            ?: BillingPeriod.MONTHLY
        val hasPrice = parseOptionalBoolean(v, "hasPrice", query["hasPrice"])
        v.throwIfInvalid()
        val (total, items) = queryService.searchTariffs(filter.copy(hasPrice = hasPrice), billingPeriod, page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, items))
    }

    get("/api/v1/tariffs/by-provider") {
        val page = call.pageRequest(setOf("priceUsd", "name", "id"))
        val query = call.request.queryParameters
        val v = Validation()
        val filter = parseTariffFilter(v, query)
        v.throwIfInvalid()
        // pageRequest yields [id] for an absent sort, which here would hide the priceUsd default.
        val sort = if (query["sort"] == null) SortField("priceUsd", descending = false) else page.sort.first()
        val (total, items) = queryService.cheapestTariffByProvider(filter, page.page, page.size, sort)
        call.respond(pageResponse(total, page.page, page.size, items))
    }

    get("/api/v1/tariffs/{id}") {
        val id = tariffId(call.parameters["id"])
        call.respond(queryService.getTariffDetail(id))
    }

    get("/api/v1/tariffs/{id}/price-history") {
        val id = tariffId(call.parameters["id"])
        val query = call.request.queryParameters
        val v = Validation()
        val billingPeriod = query["billingPeriod"]?.let { v.enumValue("billingPeriod", it, BillingPeriod.entries.toTypedArray()) }
        val from = parseOffsetInstant(v, "from", query["from"])
        val to = parseOffsetInstant(v, "to", query["to"])
        v.throwIfInvalid()
        call.respond(queryService.getPriceHistory(id, billingPeriod, from, to))
    }
}

private data class TariffFields(
    val externalId: String?,
    val name: String,
    val url: String?,
    val vcpuCount: Short,
    val cpuModel: String?,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: DiskType,
    val bandwidthMbps: Int?,
    val trafficLimitGb: Int?,
    val ipv4Count: Short,
    val virtualization: Virtualization,
)

private fun validateTariff(req: TariffWriteRequest): TariffFields {
    val v = Validation()
    v.requireNonBlank("name", req.name)
    val name = v.maxLength("name", req.name, 255)
    val externalId = req.externalId?.let { v.maxLength("externalId", it, 128) }
    val url = req.url?.let { v.validateUrl("url", it) }
    val vcpuCount = v.validateIntRange("vcpuCount", req.vcpuCount, 1, Short.MAX_VALUE.toInt())
    val cpuModel = req.cpuModel?.let { v.maxLength("cpuModel", it, 128) }
    val ramMb = v.validateIntRange("ramMb", req.ramMb, 1, Int.MAX_VALUE)
    val diskGb = v.validateIntRange("diskGb", req.diskGb, 1, Int.MAX_VALUE)
    val diskType = v.enumValue("diskType", req.diskType, DiskType.entries.toTypedArray())
    val bandwidthMbps = req.bandwidthMbps?.let { v.validateIntRange("bandwidthMbps", it, 1, Int.MAX_VALUE) }
    val trafficLimitGb = req.trafficLimitGb?.let { v.validateIntRange("trafficLimitGb", it, 1, Int.MAX_VALUE) }
    val ipv4Count = v.validateIntRange("ipv4Count", req.ipv4Count, 0, Short.MAX_VALUE.toInt())
    val virtualization = v.enumValue("virtualization", req.virtualization, Virtualization.entries.toTypedArray())
    v.throwIfInvalid()
    return TariffFields(
        externalId, name!!, url, vcpuCount!!.toShort(), cpuModel, ramMb!!, diskGb!!, diskType!!,
        bandwidthMbps, trafficLimitGb, ipv4Count!!.toShort(), virtualization!!,
    )
}

/** The filters `GET /api/v1/tariffs` and `GET /api/v1/tariffs/by-provider` share; errors go to [v]. */
private fun parseTariffFilter(v: Validation, query: Parameters): TariffSearchFilter {
    val providerId = query["providerId"]?.let { raw ->
        raw.toLongOrNull() ?: run { v.reject("providerId", "must be an integer"); null }
    }
    return TariffSearchFilter(
        providerId = providerId,
        country = query["country"]?.let { v.validateCountryCode("country", it) },
        minVcpu = query["minVcpu"]?.let { parseQueryInt(v, "minVcpu", it) },
        minRamMb = query["minRamMb"]?.let { parseQueryInt(v, "minRamMb", it) },
        minDiskGb = query["minDiskGb"]?.let { parseQueryInt(v, "minDiskGb", it) },
        diskType = query["diskType"]?.let { v.enumValue("diskType", it, DiskType.entries.toTypedArray()) },
        maxPriceUsd = query["maxPriceUsd"]?.let { v.validateAmount("maxPriceUsd", it) },
        paymentMethod = query["paymentMethod"]?.let { v.enumValue("paymentMethod", it, PaymentMethod.entries.toTypedArray()) },
        unmeteredTraffic = parseOptionalBoolean(v, "unmeteredTraffic", query["unmeteredTraffic"]),
        activeOnly = parseOptionalBoolean(v, "activeOnly", query["activeOnly"]) ?: true,
        inStock = parseOptionalBoolean(v, "inStock", query["inStock"]),
    )
}

private fun tariffId(raw: String?) = raw?.toLongOrNull() ?: throw NotFoundException("Tariff not found")

/** `true`/`false`/absent; anything else is recorded on [v] as a field error and `null` is returned. */
private fun parseOptionalBoolean(v: Validation, field: String, raw: String?): Boolean? = when (raw) {
    null -> null
    "true" -> true
    "false" -> false
    else -> { v.reject(field, "must be true or false"); null }
}

private fun parseQueryInt(v: Validation, field: String, raw: String): Int? =
    raw.toIntOrNull() ?: run { v.reject(field, "must be an integer"); null }

/** The [org.a1tsign.vds.api.InstantSerializer] rule (explicit offset required), applied to a query parameter. */
private fun parseOffsetInstant(v: Validation, field: String, raw: String?): Instant? {
    if (raw == null) return null
    return try {
        OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        v.reject(field, "must be an ISO-8601 timestamp with an explicit offset, e.g. 2026-10-28T12:00:00Z")
        null
    }
}
