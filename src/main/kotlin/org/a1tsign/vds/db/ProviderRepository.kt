package org.a1tsign.vds.db

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** Repository methods require a transaction opened by [org.a1tsign.vds.service.ProviderService]. */
class ProviderRepository {
    fun insert(slug: String, name: String, websiteUrl: String, description: String?, status: ProviderStatus, now: Instant): Provider {
        val id = ProviderTable.insert {
            it[ProviderTable.slug] = slug; it[ProviderTable.name] = name; it[ProviderTable.websiteUrl] = websiteUrl
            it[ProviderTable.description] = description; it[ProviderTable.status] = status.name
            it[ProviderTable.createdAt] = now.toUtcOffset(); it[ProviderTable.updatedAt] = now.toUtcOffset()
        }[ProviderTable.id]
        return find(id)!!
    }
    fun find(id: Long): Provider? = ProviderTable.selectAll().where { ProviderTable.id eq id }.firstOrNull()?.toProvider()
    fun lockProvider(id: Long): Provider? = ProviderTable.selectAll()
        .where { ProviderTable.id eq id }
        .forUpdate(ForUpdateOption.ForUpdate)
        .firstOrNull()?.toProvider()
    fun update(id: Long, slug: String, name: String, websiteUrl: String, description: String?, status: ProviderStatus, now: Instant) {
        ProviderTable.update({ ProviderTable.id eq id }) {
            it[ProviderTable.slug] = slug; it[ProviderTable.name] = name; it[ProviderTable.websiteUrl] = websiteUrl
            it[ProviderTable.description] = description; it[ProviderTable.status] = status.name; it[ProviderTable.updatedAt] = now.toUtcOffset()
        }
    }
    fun delete(id: Long) { ProviderTable.deleteWhere { ProviderTable.id eq id } }
    fun upsertLegalInfo(id: Long, entity: String?, country: String?, number: String?, address: String?, source: LegalDataSource, confidence: Confidence, sourceUrl: String?, verifiedAt: Instant?, now: Instant) {
        val values = ProviderLegalInfoTable.selectAll().where { ProviderLegalInfoTable.providerId eq id }.firstOrNull()
        if (values == null) ProviderLegalInfoTable.insert {
            it[ProviderLegalInfoTable.providerId] = id; it[ProviderLegalInfoTable.legalEntityName] = entity; it[ProviderLegalInfoTable.registrationCountry] = country; it[ProviderLegalInfoTable.registrationNumber] = number
            it[ProviderLegalInfoTable.legalAddress] = address; it[ProviderLegalInfoTable.dataSource] = source.name; it[ProviderLegalInfoTable.confidence] = confidence.name
            it[ProviderLegalInfoTable.sourceUrl] = sourceUrl; it[ProviderLegalInfoTable.verifiedAt] = verifiedAt?.toUtcOffset(); it[ProviderLegalInfoTable.updatedAt] = now.toUtcOffset()
        } else ProviderLegalInfoTable.update({ ProviderLegalInfoTable.providerId eq id }) {
            it[ProviderLegalInfoTable.legalEntityName] = entity; it[ProviderLegalInfoTable.registrationCountry] = country; it[ProviderLegalInfoTable.registrationNumber] = number
            it[ProviderLegalInfoTable.legalAddress] = address; it[ProviderLegalInfoTable.dataSource] = source.name; it[ProviderLegalInfoTable.confidence] = confidence.name
            it[ProviderLegalInfoTable.sourceUrl] = sourceUrl; it[ProviderLegalInfoTable.verifiedAt] = verifiedAt?.toUtcOffset(); it[ProviderLegalInfoTable.updatedAt] = now.toUtcOffset()
        }
    }
    fun legalInfo(id: Long): LegalInfo? = ProviderLegalInfoTable.selectAll().where { ProviderLegalInfoTable.providerId eq id }.firstOrNull()?.toLegalInfo()
    fun replacePaymentMethods(id: Long, methods: List<Pair<PaymentMethod, String?>>) {
        ProviderPaymentMethodTable.deleteWhere { providerId eq id }
        methods.forEach { (method, note) -> ProviderPaymentMethodTable.insert { it[ProviderPaymentMethodTable.providerId] = id; it[ProviderPaymentMethodTable.method] = method.name; it[ProviderPaymentMethodTable.note] = note } }
    }
    fun paymentMethods(id: Long): List<ProviderPaymentMethod> = ProviderPaymentMethodTable.selectAll().where { ProviderPaymentMethodTable.providerId eq id }.map { it.toProviderPaymentMethod() }
}
