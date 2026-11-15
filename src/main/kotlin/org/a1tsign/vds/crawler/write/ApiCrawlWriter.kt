package org.a1tsign.vds.crawler.write

import org.a1tsign.vds.domain.SystemStatus

/**
 * The [CrawlWriter] that really writes, by delegating to [CatalogApiClient]. Catches nothing: every
 * [CatalogApiException] is the reconciler's to interpret.
 */
class ApiCrawlWriter(private val client: CatalogApiClient) : CrawlWriter {

    override suspend fun createProvider(request: ProviderWrite): Long = client.createProvider(request).id

    override suspend fun updateProvider(providerId: Long, request: ProviderWrite) {
        client.updateProvider(providerId, request)
    }

    override suspend fun putLegalInfo(providerId: Long, request: LegalInfoWrite) {
        client.putLegalInfo(providerId, request)
    }

    override suspend fun putPaymentMethods(providerId: Long, methods: List<PaymentMethodWrite>) {
        client.putPaymentMethods(providerId, methods)
    }

    override suspend fun createLocation(providerId: Long, request: LocationWrite): Long =
        client.createLocation(providerId, request).id

    override suspend fun updateLocation(locationId: Long, request: LocationWrite) {
        client.updateLocation(locationId, request)
    }

    override suspend fun createTariff(providerId: Long, request: TariffWrite): Long =
        client.createTariff(providerId, request).id

    override suspend fun updateTariff(tariffId: Long, request: TariffWrite) {
        client.updateTariff(tariffId, request)
    }

    override suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>) {
        client.replaceTariffLocations(tariffId, locationIds)
    }

    override suspend fun putPrice(tariffId: Long, request: PriceWrite): PriceOutcome {
        val outcome = client.putPrice(tariffId, request)
        return PriceOutcome(appended = outcome.appended, noFxRate = outcome.noFxRate)
    }

    override suspend fun promoteProviderToConfigured(providerId: Long) {
        client.setSystemStatus(providerId, SystemStatus.CONFIGURED.name)
    }

    override suspend fun submitEnrichment(providerId: Long, request: EnrichmentApplyWrite, current: CatalogProviderDetail): Int =
        client.putEnrichment(providerId, request).valuesApplied
}
