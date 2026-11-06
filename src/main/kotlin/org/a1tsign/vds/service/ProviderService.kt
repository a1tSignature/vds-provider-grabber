package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.time.Clock
import org.a1tsign.vds.config.nowUtc
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.service.ProviderService")

class ProviderService(private val database: Database, private val providers: ProviderRepository, private val clock: Clock) {
    suspend fun create(slug: String, name: String, websiteUrl: String, description: String?, status: ProviderStatus, systemStatus: SystemStatus = SystemStatus.CONFIGURED): Provider = withContext(Dispatchers.IO) {
        mapSlugConflict { transaction(database) { providers.insert(slug, name, websiteUrl, description, status, clock.nowUtc(), systemStatus) } }
    }

    /** [systemStatus] `null` leaves the column untouched -- an ordinary update never promotes a candidate. */
    suspend fun update(id: Long, slug: String, name: String, websiteUrl: String, description: String?, status: ProviderStatus, systemStatus: SystemStatus? = null): Provider = withContext(Dispatchers.IO) {
        mapSlugConflict { transaction(database) { requireProvider(id); providers.update(id, slug, name, websiteUrl, description, status, clock.nowUtc(), systemStatus); providers.find(id)!! } }
    }

    /**
     * `PUT /api/v1/providers/{id}/system-status` -- the only way to reach that column deliberately.
     * Any of the three values is accepted (a human may reject a candidate, or un-reject it); the
     * transition is logged at INFO with both values and no audit table is kept.
     */
    suspend fun setSystemStatus(id: Long, systemStatus: SystemStatus): Provider = withContext(Dispatchers.IO) {
        transaction(database) {
            val current = providers.lockProvider(id) ?: throw NotFoundException("Provider not found")
            providers.updateSystemStatus(id, systemStatus, clock.nowUtc())
            logger.info(
                "provider {} ('{}') systemStatus {} -> {}",
                id,
                current.slug,
                current.systemStatus,
                systemStatus,
            )
            providers.find(id)!!
        }
    }
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { transaction(database) { requireProvider(id); providers.delete(id) } }
    suspend fun putLegalInfo(id: Long, entity: String?, country: String?, number: String?, address: String?, source: LegalDataSource, confidence: Confidence, sourceUrl: String?, verifiedAt: java.time.Instant?): LegalInfo = withContext(Dispatchers.IO) {
        transaction(database) {
            providers.lockProvider(id) ?: throw NotFoundException("Provider not found")
            providers.upsertLegalInfo(id, entity, country, number, address, source, confidence, sourceUrl, verifiedAt, clock.nowUtc())
            providers.legalInfo(id)!!
        }
    }
    suspend fun putPaymentMethods(id: Long, methods: List<Pair<PaymentMethod, String?>>): List<ProviderPaymentMethod> = withContext(Dispatchers.IO) {
        transaction(database) { requireProvider(id); providers.replacePaymentMethods(id, methods); providers.paymentMethods(id) }
    }
    private fun requireProvider(id: Long): Provider = providers.find(id) ?: throw NotFoundException("Provider not found")
    private inline fun <T> mapSlugConflict(block: () -> T): T = try { block() } catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            val pg = cause as? PSQLException
            if (pg?.sqlState == "23505" && pg.serverErrorMessage?.constraint == "uq_provider_slug") throw ConflictException("Provider slug already exists")
            cause = cause.cause
        }
        throw e
    }
}
