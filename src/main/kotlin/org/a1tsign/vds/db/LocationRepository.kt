package org.a1tsign.vds.db

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.Location
import org.a1tsign.vds.domain.RowOrigin
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Instant

/** Repository methods require a transaction opened by [org.a1tsign.vds.service.LocationService]. */
class LocationRepository {
    fun insert(providerId: Long, countryCode: String, region: String?, city: String?, datacenterName: String?, latitude: BigDecimal?, longitude: BigDecimal?, now: Instant, origin: RowOrigin = RowOrigin.MANUAL): Location {
        val id = LocationTable.insert {
            it[LocationTable.origin] = origin.name
            it[LocationTable.providerId] = providerId; it[LocationTable.countryCode] = countryCode
            it[LocationTable.region] = region; it[LocationTable.city] = city; it[LocationTable.datacenterName] = datacenterName
            it[LocationTable.latitude] = latitude; it[LocationTable.longitude] = longitude
            it[LocationTable.createdAt] = now.toUtcOffset(); it[LocationTable.updatedAt] = now.toUtcOffset()
        }[LocationTable.id]
        return find(id)!!
    }

    fun find(id: Long): Location? = LocationTable.selectAll().where { LocationTable.id eq id }.firstOrNull()?.toLocation()

    fun byProvider(providerId: Long): List<Location> =
        LocationTable.selectAll().where { LocationTable.providerId eq providerId }.map { it.toLocation() }

    /** [origin] `null` leaves the column as it is. */
    fun update(id: Long, countryCode: String, region: String?, city: String?, datacenterName: String?, latitude: BigDecimal?, longitude: BigDecimal?, now: Instant, origin: RowOrigin? = null) {
        LocationTable.update({ LocationTable.id eq id }) {
            if (origin != null) it[LocationTable.origin] = origin.name
            it[LocationTable.countryCode] = countryCode; it[LocationTable.region] = region; it[LocationTable.city] = city
            it[LocationTable.datacenterName] = datacenterName; it[LocationTable.latitude] = latitude
            it[LocationTable.longitude] = longitude; it[LocationTable.updatedAt] = now.toUtcOffset()
        }
    }

    fun delete(id: Long) { LocationTable.deleteWhere { LocationTable.id eq id } }
}
