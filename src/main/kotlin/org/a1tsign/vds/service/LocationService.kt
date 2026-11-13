package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.db.EnrichmentRepository
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.DismissalFacet
import org.a1tsign.vds.domain.Location
import org.a1tsign.vds.domain.RowOrigin
import org.a1tsign.vds.domain.WriteMode
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.time.Clock
import org.a1tsign.vds.config.nowUtc

/**
 * Every write locks the owning provider row first, which serialises it with the enrichment apply
 * ([EnrichmentService.apply]) so a dismissal and an automatic add can never interleave.
 */
class LocationService(
    private val database: Database,
    private val locations: LocationRepository,
    private val providers: ProviderRepository,
    private val clock: Clock,
    private val enrichment: EnrichmentRepository,
) {
    /** A human create of a dismissed country clears the dismissal; an automatic create makes an `AUTO` row. */
    suspend fun create(providerId: Long, countryCode: String, region: String?, city: String?, datacenterName: String?, latitude: BigDecimal?, longitude: BigDecimal?, mode: WriteMode = WriteMode.HUMAN): Location = withContext(Dispatchers.IO) {
        mapDuplicate { transaction(database) {
            providers.lockProvider(providerId) ?: throw NotFoundException("Provider not found")
            val origin = if (mode == WriteMode.HUMAN) RowOrigin.MANUAL else RowOrigin.AUTO
            if (mode == WriteMode.HUMAN) enrichment.removeDismissal(providerId, DismissalFacet.LOCATION, countryCode)
            locations.insert(providerId, countryCode, region, city, datacenterName, latitude, longitude, clock.nowUtc(), origin)
        } }
    }

    /**
     * A human edit makes the row `MANUAL`; an automatic one keeps its origin. A human edit that moves
     * an `AUTO` row away from its country is remembered like a delete of that country.
     */
    suspend fun update(id: Long, countryCode: String, region: String?, city: String?, datacenterName: String?, latitude: BigDecimal?, longitude: BigDecimal?, mode: WriteMode = WriteMode.HUMAN): Location = withContext(Dispatchers.IO) {
        mapDuplicate { transaction(database) {
            val existing = lockedLocation(id)
            val now = clock.nowUtc()
            if (mode == WriteMode.HUMAN) {
                if (existing.countryCode != countryCode) dismissIfLastAutoOfCountry(existing, now)
                enrichment.removeDismissal(existing.providerId, DismissalFacet.LOCATION, countryCode)
            }
            locations.update(id, countryCode, region, city, datacenterName, latitude, longitude, now, if (mode == WriteMode.HUMAN) RowOrigin.MANUAL else null)
            locations.find(existing.id)!!
        } }
    }

    /** Deleting the last `AUTO` row of a country records a dismissal so enrichment never re-adds it. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        transaction(database) {
            val existing = lockedLocation(id)
            dismissIfLastAutoOfCountry(existing, clock.nowUtc())
            locations.delete(id)
        }
    }

    /** Locks the owning provider, then re-reads the location so a concurrent change is seen. */
    private fun lockedLocation(id: Long): Location {
        val providerId = locations.find(id)?.providerId ?: throw NotFoundException("Location not found")
        providers.lockProvider(providerId)
        return locations.find(id) ?: throw NotFoundException("Location not found")
    }

    private fun dismissIfLastAutoOfCountry(location: Location, now: java.time.Instant) {
        if (location.origin != RowOrigin.AUTO) return
        val othersOfCountry = locations.byProvider(location.providerId)
            .any { it.id != location.id && it.countryCode == location.countryCode }
        if (!othersOfCountry) enrichment.addDismissal(location.providerId, DismissalFacet.LOCATION, location.countryCode, now)
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
