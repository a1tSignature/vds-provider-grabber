package org.a1tsign.vds.db

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.DismissalFacet
import org.a1tsign.vds.domain.EnrichmentOutcome
import org.a1tsign.vds.domain.ProviderEnrichment
import org.a1tsign.vds.domain.parseDbEnum
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** Repository methods require a transaction opened by a service. */
class EnrichmentRepository {
    fun state(providerId: Long): ProviderEnrichment? =
        ProviderEnrichmentTable.selectAll().where { ProviderEnrichmentTable.providerId eq providerId }
            .firstOrNull()?.toProviderEnrichment()

    /**
     * Records one attempt. [success] `null` keeps `last_success_at` and `evidence` as they are; a
     * non-null value replaces both.
     */
    fun recordAttempt(
        providerId: Long,
        outcome: EnrichmentOutcome,
        message: String?,
        pagesFetched: Int,
        now: Instant,
        success: SuccessfulEvidence?,
    ) {
        val exists = ProviderEnrichmentTable.select(ProviderEnrichmentTable.providerId)
            .where { ProviderEnrichmentTable.providerId eq providerId }.any()
        if (exists) {
            ProviderEnrichmentTable.update({ ProviderEnrichmentTable.providerId eq providerId }) {
                it[lastAttemptAt] = now.toUtcOffset(); it[lastOutcome] = outcome.name
                it[lastMessage] = message; it[lastPagesFetched] = pagesFetched
                if (success != null) { it[lastSuccessAt] = now.toUtcOffset(); it[evidence] = success.evidenceJson }
            }
        } else {
            ProviderEnrichmentTable.insert {
                it[ProviderEnrichmentTable.providerId] = providerId
                it[lastAttemptAt] = now.toUtcOffset(); it[lastOutcome] = outcome.name
                it[lastMessage] = message; it[lastPagesFetched] = pagesFetched
                it[lastSuccessAt] = success?.let { now.toUtcOffset() }; it[evidence] = success?.evidenceJson
            }
        }
    }

    fun dismissals(providerId: Long): Set<Pair<DismissalFacet, String>> =
        ProviderEnrichmentDismissalTable.selectAll().where { ProviderEnrichmentDismissalTable.providerId eq providerId }
            .map {
                parseDbEnum<DismissalFacet>(
                    "provider_enrichment_dismissal",
                    "facet",
                    it[ProviderEnrichmentDismissalTable.facet],
                ) to it[ProviderEnrichmentDismissalTable.value]
            }
            .toSet()

    fun addDismissal(providerId: Long, facet: DismissalFacet, value: String, now: Instant) {
        ProviderEnrichmentDismissalTable.insertIgnore {
            it[ProviderEnrichmentDismissalTable.providerId] = providerId
            it[ProviderEnrichmentDismissalTable.facet] = facet.name
            it[ProviderEnrichmentDismissalTable.value] = value
            it[createdAt] = now.toUtcOffset()
        }
    }

    fun removeDismissal(providerId: Long, facet: DismissalFacet, value: String) {
        ProviderEnrichmentDismissalTable.deleteWhere {
            (ProviderEnrichmentDismissalTable.providerId eq providerId) and
                (ProviderEnrichmentDismissalTable.facet eq facet.name) and
                (ProviderEnrichmentDismissalTable.value eq value)
        }
    }
}

/** The evidence document of a `SUCCESS`/`NOTHING_FOUND` attempt, already serialised. */
data class SuccessfulEvidence(val evidenceJson: String)
