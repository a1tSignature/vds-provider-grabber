package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.Tariff
import java.time.Instant

/**
 * `vcpuCount`/`ipv4Count` are `Short` in the domain/table but modelled as `Int` on the wire --
 * plain JSON numbers are simpler for a client than a type that silently wraps above 32767. The
 * route layer validates the range explicitly (`api/Validation.validateIntRange`) and narrows to
 * `Short` only after that check passes.
 */
@Serializable
data class TariffWriteRequest(
    val externalId: String? = null,
    val name: String,
    val url: String? = null,
    val vcpuCount: Int,
    val cpuModel: String? = null,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: String,
    val bandwidthMbps: Int? = null,
    // NULL/absent = unmetered traffic -- never coerced to 0.
    val trafficLimitGb: Int? = null,
    val ipv4Count: Int,
    val ipv6Supported: Boolean? = null,
    val virtualization: String,
    val isActive: Boolean = true,
    val inStock: Boolean = true,
)

@Serializable
data class TariffDto(
    val id: Long,
    val providerId: Long,
    val externalId: String?,
    val name: String,
    val url: String?,
    val vcpuCount: Int,
    val cpuModel: String?,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: String,
    val bandwidthMbps: Int?,
    val trafficLimitGb: Int?,
    val ipv4Count: Int,
    val ipv6Supported: Boolean?,
    val virtualization: String,
    val isActive: Boolean,
    val inStock: Boolean,
    @Contextual val createdAt: Instant,
    @Contextual val updatedAt: Instant,
)

fun Tariff.toDto() = TariffDto(
    id = id,
    providerId = providerId,
    externalId = externalId,
    name = name,
    url = url,
    vcpuCount = vcpuCount.toInt(),
    cpuModel = cpuModel,
    ramMb = ramMb,
    diskGb = diskGb,
    diskType = diskType.name,
    bandwidthMbps = bandwidthMbps,
    trafficLimitGb = trafficLimitGb,
    ipv4Count = ipv4Count.toInt(),
    ipv6Supported = ipv6Supported,
    virtualization = virtualization.name,
    isActive = isActive,
    inStock = inStock,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
