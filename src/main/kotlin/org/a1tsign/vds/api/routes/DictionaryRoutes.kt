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
import org.a1tsign.vds.domain.Virtualization

/**
 * `GET /api/v1/dictionaries` -- every enum value set a future frontend needs, generated from
 * `EnumClass.entries` (never a hand-written copy, which would silently drift from the schema).
 *
 * There are two unrelated `data_source` enums in `domain/Enums.kt`: [LegalDataSource] (how
 * `provider_legal_info` was sourced) and [PriceDataSource] (how a `tariff_price` row was sourced).
 * They are kept as two separate keys ([legalDataSources]/[priceDataSources]) rather than merged
 * into one `dataSources` set -- collapsing them would silently imply they share one value space,
 * which they do not (`MANUAL`/`CRAWLER` mean different things depending which entity they tag).
 */
@Serializable
data class DictionariesDto(
    val paymentMethods: List<String>,
    val billingPeriods: List<String>,
    val diskTypes: List<String>,
    val virtualizationTypes: List<String>,
    val providerStatuses: List<String>,
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
                confidenceLevels = Confidence.entries.map { it.name },
                legalDataSources = LegalDataSource.entries.map { it.name },
                priceDataSources = PriceDataSource.entries.map { it.name },
            ),
        )
    }
}
