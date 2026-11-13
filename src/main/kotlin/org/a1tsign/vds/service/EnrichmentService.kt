package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ApiJson
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.dto.EnrichmentApplyResultDto
import org.a1tsign.vds.api.dto.EnrichmentEvidenceDto
import org.a1tsign.vds.config.nowUtc
import org.a1tsign.vds.db.EnrichmentRepository
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.SuccessfulEvidence
import org.a1tsign.vds.domain.DismissalFacet
import org.a1tsign.vds.domain.EnrichmentFacet
import org.a1tsign.vds.domain.EnrichmentOutcome
import org.a1tsign.vds.domain.FieldOrigin
import org.a1tsign.vds.domain.FindingConfidence
import org.a1tsign.vds.domain.LegalField
import org.a1tsign.vds.domain.LegalInfo
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.RowOrigin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

/** One validated `PUT /api/v1/providers/{id}/enrichment` body. */
data class EnrichmentCommand(
    val outcome: EnrichmentOutcome,
    val message: String?,
    val pagesFetched: Int,
    val observedFacets: Set<EnrichmentFacet>,
    val legalEntityName: AutoLegalValue?,
    val registrationCountry: AutoLegalValue?,
    val paymentMethods: List<Pair<PaymentMethod, FindingConfidence>>,
    val locations: List<Pair<String, FindingConfidence>>,
    /** Every finding, written or not; stored as the evidence document. */
    val evidence: EnrichmentEvidenceDto,
)

class EnrichmentService(
    private val database: Database,
    private val providers: ProviderRepository,
    private val locations: LocationRepository,
    private val enrichment: EnrichmentRepository,
    private val clock: Clock,
) {
    /**
     * Applies one result atomically. The provider row lock serialises this with every human write
     * path, and everything the rules depend on is read in separate statements after the lock
     * returns, so a human edit committed while this call waited is seen and wins.
     */
    suspend fun apply(providerId: Long, command: EnrichmentCommand): EnrichmentApplyResultDto = withContext(Dispatchers.IO) {
        transaction(database) {
            providers.lockProvider(providerId) ?: throw NotFoundException("Provider not found")
            val now = clock.nowUtc()
            val legal = providers.legalInfo(providerId)
            val paymentMethods = providers.paymentMethods(providerId).map { it.method }.toSet()
            val locationCountries = locations.byProvider(providerId).map { it.countryCode }.toSet()
            val dismissals = enrichment.dismissals(providerId)

            fun writes(facet: EnrichmentFacet) = facet !in command.observedFacets
            val entity = command.legalEntityName.takeIf { writes(EnrichmentFacet.LEGAL_ENTITY_NAME) }
            val country = command.registrationCountry.takeIf { writes(EnrichmentFacet.REGISTRATION_COUNTRY) }
            val newLegal = LegalInfoRules.applyEnrichment(providerId, legal, entity, country, now)
            if (newLegal != null) providers.writeLegalInfo(newLegal, now)

            val addedMethods = if (!writes(EnrichmentFacet.PAYMENT_METHOD)) emptyList() else command.paymentMethods
                .filter { (method, confidence) ->
                    confidence.isWritable() && method !in paymentMethods &&
                        (DismissalFacet.PAYMENT_METHOD to method.name) !in dismissals
                }
                .map { it.first }
            addedMethods.forEach { providers.addPaymentMethod(providerId, it, note = null, origin = RowOrigin.AUTO) }

            // A country that already has any location row, even one with a city, is not added again.
            val addedCountries = if (!writes(EnrichmentFacet.LOCATION)) emptyList() else command.locations
                .filter { (country, confidence) ->
                    confidence.isWritable() && country !in locationCountries &&
                        (DismissalFacet.LOCATION to country) !in dismissals
                }
                .map { it.first }
            addedCountries.forEach {
                locations.insert(providerId, it, null, null, null, null, null, now, RowOrigin.AUTO)
            }

            val success = when (command.outcome) {
                EnrichmentOutcome.SUCCESS, EnrichmentOutcome.NOTHING_FOUND ->
                    SuccessfulEvidence(ApiJson.encodeToString(EnrichmentEvidenceDto.serializer(), command.evidence))
                else -> null
            }
            enrichment.recordAttempt(providerId, command.outcome, command.message, command.pagesFetched, now, success)

            val appliedEntity = newLegal?.legalEntityName?.takeIf { it != legal?.legalEntityName || newLegal.legalEntityNameOrigin != legal.legalEntityNameOrigin }
            val appliedCountry = newLegal?.registrationCountry?.takeIf { it != legal?.registrationCountry || newLegal.registrationCountryOrigin != legal.registrationCountryOrigin }
            EnrichmentApplyResultDto(
                legalEntityName = appliedEntity,
                registrationCountry = appliedCountry,
                paymentMethods = addedMethods,
                locations = addedCountries,
                valuesApplied = listOfNotNull(appliedEntity, appliedCountry).size + addedMethods.size + addedCountries.size,
            )
        }
    }

    /** Makes [fields] `MANUAL` (values kept) and stamps `verified_at` with the server clock. */
    suspend fun confirm(providerId: Long, fields: Set<LegalField>): LegalInfo =
        updateOrigins(providerId, fields, FieldOrigin.MANUAL, verify = true)

    /** Clears the origin of [fields] (values kept), so the next enrichment may overwrite them. */
    suspend fun release(providerId: Long, fields: Set<LegalField>): LegalInfo =
        updateOrigins(providerId, fields, origin = null, verify = false)

    private suspend fun updateOrigins(providerId: Long, fields: Set<LegalField>, origin: FieldOrigin?, verify: Boolean): LegalInfo =
        withContext(Dispatchers.IO) {
            transaction(database) {
                providers.lockProvider(providerId) ?: throw NotFoundException("Provider not found")
                val current = providers.legalInfo(providerId) ?: throw NotFoundException("Legal info not found")
                val now = clock.nowUtc()
                var next = current
                if (LegalField.LEGAL_ENTITY_NAME in fields) next = next.copy(legalEntityNameOrigin = origin)
                if (LegalField.REGISTRATION_COUNTRY in fields) next = next.copy(registrationCountryOrigin = origin)
                if (verify) next = next.copy(verifiedAt = now)
                providers.writeLegalInfo(next, now)
                providers.legalInfo(providerId)!!
            }
        }

    private fun FindingConfidence.isWritable() = this == FindingConfidence.HIGH || this == FindingConfidence.MEDIUM
}
