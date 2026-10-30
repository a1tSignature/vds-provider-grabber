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
import org.a1tsign.vds.api.dto.LocationWriteRequest
import org.a1tsign.vds.api.dto.toDto
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.service.LocationService
import java.math.BigDecimal

fun Route.locationRoutes(service: LocationService) {
    post("/api/v1/providers/{id}/locations") {
        call.requireRole(Role.EDITOR)
        val providerId = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Provider not found")
        val req = call.receive<LocationWriteRequest>()
        val fields = validateLocation(req)
        val created = service.create(providerId, fields.countryCode, fields.region, fields.city, fields.datacenterName, fields.latitude, fields.longitude)
        call.response.header("Location", "/api/v1/locations/${created.id}")
        call.respond(HttpStatusCode.Created, created.toDto())
    }
    put("/api/v1/locations/{id}") {
        call.requireRole(Role.EDITOR)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Location not found")
        val fields = validateLocation(call.receive<LocationWriteRequest>())
        call.respond(service.update(id, fields.countryCode, fields.region, fields.city, fields.datacenterName, fields.latitude, fields.longitude).toDto())
    }
    delete("/api/v1/locations/{id}") {
        call.requireRole(Role.EDITOR)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("Location not found")
        service.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class LocationFields(val countryCode: String, val region: String?, val city: String?, val datacenterName: String?, val latitude: BigDecimal?, val longitude: BigDecimal?)

private fun validateLocation(req: LocationWriteRequest): LocationFields {
    val v = Validation()
    val country = v.validateCountryCode("countryCode", req.countryCode)
    val region = req.region?.let { v.maxLength("region", it, 128) }
    val city = req.city?.let { v.maxLength("city", it, 128) }
    val datacenter = req.datacenterName?.let { v.maxLength("datacenterName", it, 255) }
    val latitude = req.latitude?.let { v.validateLocationCoordinate("latitude", it.toPlainString(), BigDecimal("-90"), BigDecimal("90")) }
    val longitude = req.longitude?.let { v.validateLocationCoordinate("longitude", it.toPlainString(), BigDecimal("-180"), BigDecimal("180")) }
    v.validateCoordinatePair(req.latitude, req.longitude)
    v.throwIfInvalid()
    return LocationFields(country!!, region, city, datacenter, latitude, longitude)
}
