package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.Location
import java.math.BigDecimal
import java.time.Instant

@Serializable
data class LocationWriteRequest(
    val countryCode: String,
    val region: String? = null,
    val city: String? = null,
    val datacenterName: String? = null,
    @Contextual val latitude: BigDecimal? = null,
    @Contextual val longitude: BigDecimal? = null,
)

@Serializable
data class LocationDto(
    val id: Long,
    val providerId: Long,
    val countryCode: String,
    val region: String?,
    val city: String?,
    val datacenterName: String?,
    @Contextual val latitude: BigDecimal?,
    @Contextual val longitude: BigDecimal?,
    @Contextual val createdAt: Instant,
    @Contextual val updatedAt: Instant,
)

fun Location.toDto() = LocationDto(id, providerId, countryCode, region, city, datacenterName, latitude, longitude, createdAt, updatedAt)
