package org.a1tsign.vds.support

import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

class CatalogFixture(private val database: Database, private val providers: ProviderRepository = ProviderRepository()) {
    private val locations = LocationRepository()
    fun createProvider(slug: String, at: Instant): Provider = transaction(database) {
        providers.insert(slug, "Provider $slug", "https://$slug.example", null, ProviderStatus.ACTIVE, at)
    }
    fun createLegalInfo(providerId: Long, at: Instant): LegalInfo = transaction(database) {
        providers.upsertLegalInfo(providerId, "Example Ltd", "US", "123", null, LegalDataSource.MANUAL, Confidence.HIGH, null, null, at)
        providers.legalInfo(providerId)!!
    }
    fun createPaymentMethods(providerId: Long, methods: List<Pair<PaymentMethod, String?>>) = transaction(database) {
        providers.replacePaymentMethods(providerId, methods)
    }
    fun createLocation(providerId: Long, countryCode: String, city: String? = null, datacenterName: String? = null, at: Instant): Location = transaction(database) {
        locations.insert(providerId, countryCode, null, city, datacenterName, null, null, at)
    }
}
