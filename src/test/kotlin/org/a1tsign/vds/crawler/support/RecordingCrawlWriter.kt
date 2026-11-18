package org.a1tsign.vds.crawler.support

import org.a1tsign.vds.crawler.write.CatalogProviderDetail
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.EnrichmentApplyWrite
import org.a1tsign.vds.crawler.write.LegalInfoWrite
import org.a1tsign.vds.crawler.write.LocationWrite
import org.a1tsign.vds.crawler.write.PaymentMethodWrite
import org.a1tsign.vds.crawler.write.PriceOutcome
import org.a1tsign.vds.crawler.write.PriceWrite
import org.a1tsign.vds.crawler.write.ProviderWrite
import org.a1tsign.vds.crawler.write.TariffWrite
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Record attempted writes in call order without applying them to the catalog. */
class RecordingCrawlWriter(
    /** Returned by [putPrice]; a test that counts `pricesUnchanged` flips it to `false`. */
    private val priceOutcome: PriceOutcome = PriceOutcome(appended = true, noFxRate = false),
) : CrawlWriter {

    sealed interface Mutation {
        data class CreateProvider(val request: ProviderWrite) : Mutation
        data class UpdateProvider(val providerId: Long, val request: ProviderWrite) : Mutation
        data class PutLegalInfo(val providerId: Long, val request: LegalInfoWrite) : Mutation
        data class PutPaymentMethods(val providerId: Long, val methods: List<PaymentMethodWrite>) : Mutation
        data class CreateLocation(val providerId: Long, val request: LocationWrite) : Mutation
        data class UpdateLocation(val locationId: Long, val request: LocationWrite) : Mutation
        data class CreateTariff(val providerId: Long, val request: TariffWrite) : Mutation
        data class UpdateTariff(val tariffId: Long, val request: TariffWrite) : Mutation
        data class ReplaceTariffLocations(val tariffId: Long, val locationIds: List<Long>) : Mutation
        data class PutPrice(val tariffId: Long, val request: PriceWrite) : Mutation

        data class PromoteProvider(val providerId: Long) : Mutation
        data class SubmitEnrichment(val providerId: Long, val request: EnrichmentApplyWrite) : Mutation
    }

    private val recorded = CopyOnWriteArrayList<Mutation>()
    private val ids = AtomicLong()

    fun mutations(): List<Mutation> = recorded.toList()

    fun kinds(): List<String> = recorded.map { it::class.simpleName!! }

    override suspend fun createProvider(request: ProviderWrite): Long =
        record(Mutation.CreateProvider(request)).let { ids.incrementAndGet() }

    override suspend fun updateProvider(providerId: Long, request: ProviderWrite) =
        record(Mutation.UpdateProvider(providerId, request))

    override suspend fun putLegalInfo(providerId: Long, request: LegalInfoWrite) =
        record(Mutation.PutLegalInfo(providerId, request))

    override suspend fun putPaymentMethods(providerId: Long, methods: List<PaymentMethodWrite>) =
        record(Mutation.PutPaymentMethods(providerId, methods))

    override suspend fun createLocation(providerId: Long, request: LocationWrite): Long =
        record(Mutation.CreateLocation(providerId, request)).let { ids.incrementAndGet() }

    override suspend fun updateLocation(locationId: Long, request: LocationWrite) =
        record(Mutation.UpdateLocation(locationId, request))

    override suspend fun createTariff(providerId: Long, request: TariffWrite): Long =
        record(Mutation.CreateTariff(providerId, request)).let { ids.incrementAndGet() }

    override suspend fun updateTariff(tariffId: Long, request: TariffWrite) =
        record(Mutation.UpdateTariff(tariffId, request))

    override suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>) =
        record(Mutation.ReplaceTariffLocations(tariffId, locationIds))

    override suspend fun putPrice(tariffId: Long, request: PriceWrite): PriceOutcome {
        record(Mutation.PutPrice(tariffId, request))
        return priceOutcome
    }

    override suspend fun promoteProviderToConfigured(providerId: Long) =
        record(Mutation.PromoteProvider(providerId))

    override suspend fun submitEnrichment(providerId: Long, request: EnrichmentApplyWrite, current: CatalogProviderDetail): Int {
        record(Mutation.SubmitEnrichment(providerId, request))
        return 0
    }

    private fun record(mutation: Mutation) {
        recorded += mutation
    }
}
