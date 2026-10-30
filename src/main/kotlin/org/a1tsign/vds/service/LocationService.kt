package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.Location
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.time.Clock
import org.a1tsign.vds.config.nowUtc

class LocationService(private val database: Database, private val locations: LocationRepository, private val providers: ProviderRepository, private val clock: Clock) {
    suspend fun create(providerId: Long, countryCode: String, region: String?, city: String?, datacenterName: String?, latitude: BigDecimal?, longitude: BigDecimal?): Location = withContext(Dispatchers.IO) {
        mapDuplicate { transaction(database) {
            if (providers.find(providerId) == null) throw NotFoundException("Provider not found")
            locations.insert(providerId, countryCode, region, city, datacenterName, latitude, longitude, clock.nowUtc())
        } }
    }

    suspend fun update(id: Long, countryCode: String, region: String?, city: String?, datacenterName: String?, latitude: BigDecimal?, longitude: BigDecimal?): Location = withContext(Dispatchers.IO) {
        mapDuplicate { transaction(database) {
            val existing = locations.find(id) ?: throw NotFoundException("Location not found")
            locations.update(id, countryCode, region, city, datacenterName, latitude, longitude, clock.nowUtc())
            locations.find(existing.id)!!
        } }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        transaction(database) {
            if (locations.find(id) == null) throw NotFoundException("Location not found")
            locations.delete(id)
        }
    }

    private inline fun <T> mapDuplicate(block: () -> T): T = try { block() } catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            val pg = cause as? PSQLException
            if (pg?.sqlState == "23505" && pg.serverErrorMessage?.constraint == "uq_location_provider_country_city_dc") {
                throw ConflictException("Location already exists for this provider, country, city, and datacenter")
            }
            cause = cause.cause
        }
        throw e
    }
}
