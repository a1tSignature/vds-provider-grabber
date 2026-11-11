package org.a1tsign.vds.api.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.domain.Virtualization

/**
 * `GET /api/v1/dictionaries`: every enum value set, built from `entries` so it cannot drift from the
 * enums.
 *
 * [legalDataSources] and [priceDataSources] stay separate keys: `MANUAL`/`CRAWLER` mean different
 * things for `provider_legal_info` and `tariff_price`.
 */
@Serializable
data class DictionariesDto(
    val paymentMethods: List<String>,
    val billingPeriods: List<String>,
    val diskTypes: List<String>,
    val virtualizationTypes: List<String>,
    val providerStatuses: List<String>,
    /** Our own relationship to the provider, independent of [providerStatuses]. */
    val systemStatuses: List<String>,
    val confidenceLevels: List<String>,
    val legalDataSources: List<String>,
    val priceDataSources: List<String>,
)

fun Route.dictionaryRoutes() {
    get("/api/v1/dictionaries") {
        call.requireRole(Role.VIEWER)
        call.respond(
            DictionariesDto(
                paymentMethods = PaymentMethod.entries.map { it.name },
                billingPeriods = BillingPeriod.entries.map { it.name },
                diskTypes = DiskType.entries.map { it.name },
                virtualizationTypes = Virtualization.entries.map { it.name },
                providerStatuses = ProviderStatus.entries.map { it.name },
                systemStatuses = SystemStatus.entries.map { it.name },
                confidenceLevels = Confidence.entries.map { it.name },
                legalDataSources = LegalDataSource.entries.map { it.name },
                priceDataSources = PriceDataSource.entries.map { it.name },
            ),
        )
    }
}
