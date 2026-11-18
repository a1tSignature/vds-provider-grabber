package org.a1tsign.vds.service

import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.FieldOrigin
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.LegalInfo
import java.time.Instant

/** A value automation wants to put into one legal field. [origin] is always an `AUTO_*` value. */
data class AutoLegalValue(val value: String, val origin: FieldOrigin, val sourceUrl: String? = null)

data class LegalInfoWrite(
    val legalEntityName: String?,
    val registrationCountry: String?,
    val registrationNumber: String?,
    val legalAddress: String?,
    val dataSource: LegalDataSource,
    val confidence: Confidence,
    val sourceUrl: String?,
    val verifiedAt: Instant?,
    val legalEntityNameOrigin: FieldOrigin?,
    val registrationCountryOrigin: FieldOrigin?,
)

object LegalInfoRules {
    /**
     * The enrichment apply. Only the two legal fields change; number, address and `verified_at` are
     * carried over. Record-level provenance is recomputed only when no field is `MANUAL`. Returns
     * `null` when neither field changes, so nothing is written.
     */
    fun applyEnrichment(
        providerId: Long,
        current: LegalInfo?,
        entity: AutoLegalValue?,
        country: AutoLegalValue?,
        now: Instant,
    ): LegalInfo? {
        val (entityValue, entityOrigin) = merge(current?.legalEntityName, current?.legalEntityNameOrigin, entity)
        val (countryValue, countryOrigin) = merge(current?.registrationCountry, current?.registrationCountryOrigin, country)
        val unchanged = entityValue == current?.legalEntityName && entityOrigin == current?.legalEntityNameOrigin &&
            countryValue == current?.registrationCountry && countryOrigin == current?.registrationCountryOrigin
        if (unchanged) return null

        val base = current ?: LegalInfo(
            providerId = providerId, legalEntityName = null, registrationCountry = null, registrationNumber = null,
            legalAddress = null, dataSource = LegalDataSource.PROVIDER_SITE, confidence = Confidence.LOW,
            sourceUrl = null, verifiedAt = null, updatedAt = now,
        )
        val merged = base.copy(
            legalEntityName = entityValue, legalEntityNameOrigin = entityOrigin,
            registrationCountry = countryValue, registrationCountryOrigin = countryOrigin,
        )
        if (merged.hasManualField()) return merged
        return merged.copy(
            dataSource = LegalDataSource.PROVIDER_SITE,
            confidence = autoConfidence(merged),
            sourceUrl = entity?.sourceUrl ?: country?.sourceUrl ?: merged.sourceUrl,
        )
    }

    /**
     * `AUTOMATIC` `PUT …/legal-info` (the declarative reconciler). A null field in [write] means
     * "not found" and leaves the stored field alone. The remaining columns follow [write] only while
     * no field is `MANUAL`; `verified_at` is never touched.
     */
    fun applyAutomaticWrite(providerId: Long, current: LegalInfo?, write: LegalInfoWrite, now: Instant): LegalInfo {
        val entity = write.legalEntityName?.let { AutoLegalValue(it, write.legalEntityNameOrigin ?: FieldOrigin.AUTO_CONFIRMED) }
        val country = write.registrationCountry?.let { AutoLegalValue(it, write.registrationCountryOrigin ?: FieldOrigin.AUTO_CONFIRMED) }
        val (entityValue, entityOrigin) = merge(current?.legalEntityName, current?.legalEntityNameOrigin, entity)
        val (countryValue, countryOrigin) = merge(current?.registrationCountry, current?.registrationCountryOrigin, country)
        val fields = LegalInfo(
            providerId = providerId,
            legalEntityName = entityValue, legalEntityNameOrigin = entityOrigin,
            registrationCountry = countryValue, registrationCountryOrigin = countryOrigin,
            registrationNumber = write.registrationNumber, legalAddress = write.legalAddress,
            dataSource = write.dataSource, confidence = write.confidence, sourceUrl = write.sourceUrl,
            verifiedAt = current?.verifiedAt, updatedAt = now,
        )
        if (current == null || !fields.hasManualField()) return fields
        return fields.copy(
            registrationNumber = current.registrationNumber, legalAddress = current.legalAddress,
            dataSource = current.dataSource, confidence = current.confidence, sourceUrl = current.sourceUrl,
        )
    }

    /**
     * `HUMAN` `PUT …/legal-info`: a full replace of the row. An absent origin is
     * `MANUAL` for a field that has a value or is being cleared. A field that was empty and stays
     * empty keeps its origin, so saving a form with a blank field does not lock automation out.
     */
    fun applyHumanWrite(providerId: Long, current: LegalInfo?, write: LegalInfoWrite, now: Instant): LegalInfo =
        LegalInfo(
            providerId = providerId,
            legalEntityName = write.legalEntityName,
            legalEntityNameOrigin = humanOrigin(write.legalEntityName, write.legalEntityNameOrigin, current?.legalEntityName, current?.legalEntityNameOrigin),
            registrationCountry = write.registrationCountry,
            registrationCountryOrigin = humanOrigin(write.registrationCountry, write.registrationCountryOrigin, current?.registrationCountry, current?.registrationCountryOrigin),
            registrationNumber = write.registrationNumber, legalAddress = write.legalAddress,
            dataSource = write.dataSource, confidence = write.confidence, sourceUrl = write.sourceUrl,
            verifiedAt = write.verifiedAt, updatedAt = now,
        )

    private fun merge(value: String?, origin: FieldOrigin?, found: AutoLegalValue?): Pair<String?, FieldOrigin?> =
        if (found == null || origin == FieldOrigin.MANUAL) value to origin else found.value to found.origin

    private fun humanOrigin(value: String?, requested: FieldOrigin?, currentValue: String?, currentOrigin: FieldOrigin?): FieldOrigin? = when {
        requested != null -> requested
        value != null || currentValue != null -> FieldOrigin.MANUAL
        else -> currentOrigin
    }

    private fun LegalInfo.hasManualField() =
        legalEntityNameOrigin == FieldOrigin.MANUAL || registrationCountryOrigin == FieldOrigin.MANUAL

    private fun autoConfidence(info: LegalInfo): Confidence {
        val origins = listOfNotNull(info.legalEntityNameOrigin, info.registrationCountryOrigin)
        return if (origins.all { it == FieldOrigin.AUTO_CONFIRMED }) Confidence.MEDIUM else Confidence.LOW
    }
}
