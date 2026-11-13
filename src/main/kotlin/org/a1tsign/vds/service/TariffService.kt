package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.config.nowUtc
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.Tariff
import org.a1tsign.vds.domain.Virtualization
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.time.Clock

private val DUPLICATE_NAME_CONSTRAINT = "uq_tariff_provider_name"
private val DUPLICATE_EXTERNAL_ID_CONSTRAINT = "uq_tariff_provider_external_id"
private val LOCATION_LINK_CONSTRAINTS = setOf("fk_tariff_location_location", "fk_tariff_location_tariff")

class TariffService(
    private val database: Database,
    private val tariffs: TariffRepository,
    private val providers: ProviderRepository,
    private val locations: LocationRepository,
    private val clock: Clock,
) {
    suspend fun create(
        providerId: Long,
        externalId: String?,
        name: String,
        url: String?,
        vcpuCount: Short,
        cpuModel: String?,
        ramMb: Int,
        diskGb: Int,
        diskType: DiskType,
        bandwidthMbps: Int?,
        trafficLimitGb: Int?,
        ipv4Count: Short,
        ipv6Supported: Boolean?,
        virtualization: Virtualization,
        isActive: Boolean,
        inStock: Boolean,
    ): Tariff = withContext(Dispatchers.IO) {
        mapDuplicateConflict {
            transaction(database) {
                providers.find(providerId) ?: throw NotFoundException("Provider not found")
                tariffs.insert(
                    providerId, externalId, name, url, vcpuCount, cpuModel, ramMb, diskGb, diskType,
                    bandwidthMbps, trafficLimitGb, ipv4Count, ipv6Supported, virtualization, isActive, inStock,
                    clock.nowUtc(),
                )
            }
        }
    }

    suspend fun update(
        id: Long,
        externalId: String?,
        name: String,
        url: String?,
        vcpuCount: Short,
        cpuModel: String?,
        ramMb: Int,
        diskGb: Int,
        diskType: DiskType,
        bandwidthMbps: Int?,
        trafficLimitGb: Int?,
        ipv4Count: Short,
        ipv6Supported: Boolean?,
        virtualization: Virtualization,
        isActive: Boolean,
        inStock: Boolean,
    ): Tariff = withContext(Dispatchers.IO) {
        mapDuplicateConflict {
            transaction(database) {
                tariffs.find(id) ?: throw NotFoundException("Tariff not found")
                tariffs.update(
                    id, externalId, name, url, vcpuCount, cpuModel, ramMb, diskGb, diskType,
                    bandwidthMbps, trafficLimitGb, ipv4Count, ipv6Supported, virtualization, isActive, inStock,
                    clock.nowUtc(),
                )
                tariffs.find(id)!!
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        transaction(database) {
            lockedTariff(id)
            tariffs.delete(id)
        }
    }

    /**
     * Replaces the whole `tariff_location` link set for [tariffId] atomically: the cross-provider
     * check (every candidate location's own `provider_id` against the tariff's) and the
     * delete-then-insert happen inside the same transaction, so any rejection -- a missing
     * location, a cross-provider one, or the composite FK as a last-resort safety net -- rolls the
     * whole transaction back and leaves the previous link set untouched.
     */
    suspend fun replaceLocations(tariffId: Long, locationIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        mapLocationConflict {
            transaction(database) {
                val tariff = lockedTariff(tariffId)
                locationIds.forEach { locationId ->
                    val location = locations.find(locationId) ?: throw NotFoundException("Location not found")
                    if (location.providerId != tariff.providerId) {
                        throw ConflictException("Location belongs to a different provider than the tariff")
                    }
                }
                tariffs.replaceLocations(tariffId, tariff.providerId, locationIds)
                tariffs.locationIds(tariffId)
            }
        }
    }

    /**
     * Locks the owning provider row, then re-reads the tariff so one deleted meanwhile is a `404`.
     * Location writes take the same lock first; without it, writes that cascade through
     * `tariff_location` lock its rows and the tariff/location rows in opposite orders and deadlock.
     */
    private fun lockedTariff(id: Long): Tariff {
        val providerId = tariffs.find(id)?.providerId ?: throw NotFoundException("Tariff not found")
        providers.lockProvider(providerId)
        return tariffs.find(id) ?: throw NotFoundException("Tariff not found")
    }

    private inline fun <T> mapDuplicateConflict(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            val pg = cause as? PSQLException
            if (pg?.sqlState == "23505") {
                when (pg.serverErrorMessage?.constraint) {
                    DUPLICATE_NAME_CONSTRAINT -> throw ConflictException("Tariff name already exists for this provider")
                    DUPLICATE_EXTERNAL_ID_CONSTRAINT -> throw ConflictException("Tariff externalId already exists for this provider")
                }
            }
            cause = cause.cause
        }
        throw e
    }

    private inline fun <T> mapLocationConflict(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            val pg = cause as? PSQLException
            if (pg?.sqlState == "23503" && pg.serverErrorMessage?.constraint in LOCATION_LINK_CONSTRAINTS) {
                throw ConflictException("Location belongs to a different provider than the tariff")
            }
            cause = cause.cause
        }
        throw e
    }
}
