package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.PriceCloseRequest
import org.a1tsign.vds.api.dto.PriceWriteRequest
import org.a1tsign.vds.api.dto.toDto
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.service.PriceService

fun Route.priceRoutes(service: PriceService) {
    put("/api/v1/tariffs/{id}/price") {
        val principal = call.requireRole(Role.EDITOR)
        val tariffId = tariffIdOrNotFound(call.parameters["id"])
        val req = call.receive<PriceWriteRequest>()
        val v = Validation()
        val billingPeriod = v.enumValue("billingPeriod", req.billingPeriod, BillingPeriod.entries.toTypedArray())
        val amount = v.validateAmount("amount", req.amount)
        val currency = v.validateCurrency("currency", req.currency)
        val dataSource = if (req.dataSource == null) {
            PriceDataSource.MANUAL
        } else {
            v.enumValue("dataSource", req.dataSource, PriceDataSource.entries.toTypedArray())
        }
        v.throwIfInvalid()
        val result = service.setPrice(
            tariffId = tariffId,
            billingPeriod = billingPeriod!!,
            amount = amount!!,
            currency = currency!!,
            requestedEffectiveFrom = req.effectiveFrom,
            dataSource = dataSource!!,
            recordedBy = principal.userId,
        )
        call.respond(
            if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
            result.price.toDto(result.warnings),
        )
    }

    post("/api/v1/tariffs/{id}/price/close") {
        call.requireRole(Role.EDITOR)
        val tariffId = tariffIdOrNotFound(call.parameters["id"])
        val req = call.receive<PriceCloseRequest>()
        val v = Validation()
        val billingPeriod = v.enumValue("billingPeriod", req.billingPeriod, BillingPeriod.entries.toTypedArray())
        v.throwIfInvalid()
        val closed = service.close(tariffId, billingPeriod!!, req.effectiveTo)
        call.respond(HttpStatusCode.OK, closed.toDto())
    }

    delete("/api/v1/tariffs/{id}/prices/{priceId}") {
        val principal = call.requireRole(Role.ADMIN)
        val tariffId = tariffIdOrNotFound(call.parameters["id"])
        val priceId = call.parameters["priceId"]?.toLongOrNull() ?: throw NotFoundException("Price not found")
        service.correct(tariffId, priceId, principal.userId)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun tariffIdOrNotFound(raw: String?) = raw?.toLongOrNull() ?: throw NotFoundException("Tariff not found")
