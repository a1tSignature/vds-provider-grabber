package org.a1tsign.vds.crawler.write

import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.LocationMatchKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.ProviderInfo
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.domain.FieldOrigin
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.SystemStatus
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/** Counters and planned operations for one adapter's reconciliation. Partial writes can survive failure. */
data class ReconcileOutcome(
    val status: AdapterRunStatus,
    val failureReason: FailureReason? = null,
    /** One operator-readable line; never a request body, a token or a page body. */
    val message: String? = null,
    val providersCreated: Int = 0,
    val locationsCreated: Int = 0,
    val tariffsCreated: Int = 0,
    val tariffsUpdated: Int = 0,
    val tariffsMarkedOutOfStock: Int = 0,
    val pricesAppended: Int = 0,
    val pricesUnchanged: Int = 0,
    val noFxRateCount: Int = 0,
    val plannedOperations: List<String> = emptyList(),
)

/** Below this many in-stock tariffs the mass-change guard never trips. */
const val MASS_CHANGE_GUARD_MIN_IN_STOCK: Int = 3

/**
 * Reconcile observed facets through the catalog API. Null leaves data alone and an observed empty set clears it.
 * Preserve tariff activation and only mark missing tariffs out of stock for complete snapshots. Run the mass-change guard before any write.
 * Writes are ordered and can partially succeed. Replaying the same snapshot converges, and a create conflict is handled by re-reading.
 */
class Reconciler(private val client: CatalogApiClient) {

    suspend fun reconcile(
        adapter: ProviderAdapter,
        snapshot: ProviderSnapshot,
        writer: CrawlWriter,
        maxOutOfStockRatio: BigDecimal,
    ): ReconcileOutcome = Pass(adapter, snapshot, writer, maxOutOfStockRatio).run()

    private inner class Pass(
        private val adapter: ProviderAdapter,
        private val snapshot: ProviderSnapshot,
        private val writer: CrawlWriter,
        private val maxOutOfStockRatio: BigDecimal,
    ) {
        private val slug = adapter.providerSlug

        private var providersCreated = 0
        private var locationsCreated = 0
        private var tariffsCreated = 0
        private var tariffsUpdated = 0
        private var tariffsMarkedOutOfStock = 0
        private var pricesAppended = 0
        private var pricesUnchanged = 0
        private var noFxRateCount = 0

        private var degraded = false
        private var firstProblem: String? = null

        /** Only a material write promotes a candidate. Reconfirming an unchanged price does not count. */
        private var wroteSomething = false

        /** Share claimed tariff IDs across planning and conflict recovery so one row cannot match two observations. */
        private val claimedTariffIds = mutableSetOf<Long>()

        suspend fun run(): ReconcileOutcome {
            writer.beginProvider(slug)
            val plannedBefore = writer.plannedOperations().size
            var status = AdapterRunStatus.SUCCESS
            var reason: FailureReason? = null
            var message: String? = null
            try {
                apply()
            } catch (halt: Halt) {
                status = halt.status
                reason = halt.reason
                message = halt.text
            } catch (unauthorized: CatalogApiException.Unauthorized) {
                throw unauthorized
            } catch (failure: CatalogApiException) {
                // Stop after an exhausted transport failure or failed read because the catalog state is uncertain. Applied writes survive.
                logger.warn("Catalog call failed for provider '{}'; the adapter is FAILED: {}", slug, failure.message)
                status = AdapterRunStatus.FAILED
                reason = FailureReason.WRITE
                message = failure.message
            }
            if (status == AdapterRunStatus.SUCCESS && degraded) {
                status = AdapterRunStatus.PARTIAL
                reason = FailureReason.WRITE
                message = firstProblem
            }
            return ReconcileOutcome(
                status = status,
                failureReason = reason,
                message = message,
                providersCreated = providersCreated,
                locationsCreated = locationsCreated,
                tariffsCreated = tariffsCreated,
                tariffsUpdated = tariffsUpdated,
                tariffsMarkedOutOfStock = tariffsMarkedOutOfStock,
                pricesAppended = pricesAppended,
                pricesUnchanged = pricesUnchanged,
                noFxRateCount = noFxRateCount,
                plannedOperations = writer.plannedOperations().drop(plannedBefore),
            )
        }

        private suspend fun apply() {
            val providerWrite = providerWrite(snapshot.provider)
            val state = resolveProvider(providerWrite)
            val plan = planTariffs(state.tariffs)
            checkMassChange(state, plan)

            if (state.detail != null && providerWrite(state.detail.provider) != providerWrite) {
                write("PUT /providers/{id}") { writer.updateProvider(state.providerId, providerWrite) }
            }
            applyLegalInfo(state)
            applyPaymentMethods(state)
            val locationIds = applyLocations(state)
            val resolved = applyTariffs(state, plan)
            applyMissingTariffs(plan)
            applyLinks(resolved, locationIds)
            applyPrices(resolved)
            promoteIfCandidate(state)
        }

        /**
         * The existing provider row, or the one created here. A `409` on the create means a
         * concurrent writer won the race: re-read and continue.
         */
        private suspend fun resolveProvider(providerWrite: ProviderWrite): CatalogState {
            readState()?.let { return it }
            val created = try {
                writer.createProvider(providerWrite)
            } catch (conflict: CatalogApiException.Conflict) {
                logger.info("Provider '{}' was created concurrently; reusing the existing row", slug)
                return readState() ?: throw Halt(
                    AdapterRunStatus.FAILED,
                    FailureReason.WRITE,
                    "Provider '$slug' was refused with 409 but cannot be found either",
                )
            } catch (clientError: CatalogApiException.ClientError) {
                logger.warn("Creating provider '{}' was refused; the adapter is PARTIAL: {}", slug, clientError.message)
                throw Halt(AdapterRunStatus.PARTIAL, FailureReason.WRITE, clientError.message)
            }
            providersCreated++
            wroteSomething = true
            // Never read a newly created ID back here. In dry runs that ID is fabricated.
            return CatalogState(created, detail = null, tariffs = emptyList())
        }

        private suspend fun readState(): CatalogState? {
            val summary = client.findProviderBySlug(slug) ?: return null
            return CatalogState(summary.id, client.getProvider(summary.id), client.listAllTariffs(summary.id))
        }

        /** The catalog enforces MANUAL precedence, so a differing protected field may be sent again on later runs. */
        private suspend fun applyLegalInfo(state: CatalogState) {
            val observed = snapshot.legalInfo ?: return
            val autoOrigin = FieldOrigin.AUTO_CONFIRMED.name
            val desired = LegalInfoWrite(
                dataSource = LegalDataSource.CRAWLER.name,
                confidence = observed.confidence.name,
                legalEntityName = observed.legalEntityName,
                registrationCountry = observed.registrationCountry,
                registrationNumber = observed.registrationNumber,
                legalAddress = observed.legalAddress,
                sourceUrl = observed.sourceUrl,
                legalEntityNameOrigin = observed.legalEntityName?.let { autoOrigin },
                registrationCountryOrigin = observed.registrationCountry?.let { autoOrigin },
            )
            val current = state.detail?.legalInfo?.let {
                LegalInfoWrite(
                    dataSource = it.dataSource,
                    confidence = it.confidence,
                    legalEntityName = it.legalEntityName,
                    registrationCountry = it.registrationCountry,
                    registrationNumber = it.registrationNumber,
                    legalAddress = it.legalAddress,
                    sourceUrl = it.sourceUrl,
                    legalEntityNameOrigin = it.legalEntityNameOrigin,
                    registrationCountryOrigin = it.registrationCountryOrigin,
                )
            }
            val comparable = current?.let {
                desired.copy(
                    legalEntityName = desired.legalEntityName ?: it.legalEntityName,
                    legalEntityNameOrigin = desired.legalEntityNameOrigin ?: it.legalEntityNameOrigin,
                    registrationCountry = desired.registrationCountry ?: it.registrationCountry,
                    registrationCountryOrigin = desired.registrationCountryOrigin ?: it.registrationCountryOrigin,
                )
            }
            if (current == null || current != comparable) {
                write("PUT /providers/{id}/legal-info") { writer.putLegalInfo(state.providerId, desired) }
            }
        }

        /**
         * Replaces the whole set, so it runs only for an observed set. An unchanged set is not
         * rewritten, which preserves a human-written `note` the crawler cannot observe.
         */
        private suspend fun applyPaymentMethods(state: CatalogState) {
            val observed = snapshot.paymentMethods ?: return
            val desired = observed.map { it.name }.sorted()
            val current = state.detail?.paymentMethods?.map { it.method }?.sorted() ?: emptyList()
            if (current == desired) return
            write("PUT /providers/{id}/payment-methods") {
                writer.putPaymentMethods(state.providerId, desired.map { PaymentMethodWrite(it) })
            }
        }

        /** Location id per match key: existing ones plus the ones created here. */
        private suspend fun applyLocations(state: CatalogState): Map<LocationMatchKey, Long> {
            val existing = state.detail?.locations.orEmpty().associateBy { it.matchKey() }
            val ids = existing.mapValues { (_, location) -> location.id }.toMutableMap()
            val observed = snapshot.locations ?: return ids
            observed.forEach { location ->
                val key = location.key.matchKey()
                val desired = locationWrite(location)
                val current = existing[key]
                if (current == null) {
                    createLocation(state, key, desired)?.let { ids[key] = it }
                } else if (locationDiffers(current, desired)) {
                    write("PUT /locations/{id}") { writer.updateLocation(current.id, desired) }
                }
            }
            return ids
        }

        private suspend fun createLocation(
            state: CatalogState,
            key: LocationMatchKey,
            desired: LocationWrite,
        ): Long? = try {
            writer.createLocation(state.providerId, desired).also { locationsCreated++; wroteSomething = true }
        } catch (conflict: CatalogApiException.Conflict) {
            // uq_location_provider_country_city_dc: the same row was created after our read.
            logger.info("Location {} of '{}' already exists; reusing the existing row", key, slug)
            val reread = client.getProvider(state.providerId).locations.firstOrNull { it.matchKey() == key }
            if (reread == null) {
                degrade("Location $key of '$slug' was refused with 409 but cannot be found either")
                null
            } else {
                reread.id
            }
        } catch (clientError: CatalogApiException.ClientError) {
            degrade("Location $key of '$slug' was refused: ${clientError.message}")
            null
        }

        private suspend fun applyTariffs(state: CatalogState, plan: TariffPlan): List<ResolvedTariff> {
            val resolved = mutableListOf<ResolvedTariff>()
            plan.matches.forEach { (observed, match) ->
                val id = if (match == null) {
                    createTariff(state, observed)
                } else {
                    updateTariff(observed, match)
                }
                if (id != null) resolved += ResolvedTariff(observed, id)
            }
            plan.collisions.forEach { observed ->
                degrade(
                    "Tariff '${observed.name}' of '$slug' matches a catalog row another tariff of the " +
                        "same snapshot already claimed; it was skipped",
                )
            }
            return resolved
        }

        private suspend fun createTariff(state: CatalogState, observed: TariffSnapshot): Long? {
            val desired = tariffWrite(observed, isActive = true, externalId = observed.externalId)
            return try {
                writer.createTariff(state.providerId, desired).also { tariffsCreated++; wroteSomething = true }
            } catch (conflict: CatalogApiException.Conflict) {
                logger.info("Tariff '{}' of '{}' already exists; reusing the existing row", observed.name, slug)
                val reread = match(client.listAllTariffs(state.providerId), observed)
                when {
                    reread == null -> {
                        degrade("Tariff '${observed.name}' of '$slug' was refused with 409 but cannot be found either")
                        null
                    }
                    !claimedTariffIds.add(reread.id) -> {
                        degrade(
                            "Tariff '${observed.name}' of '$slug' matches a catalog row another tariff of the " +
                                "same snapshot already claimed; it was skipped",
                        )
                        null
                    }
                    else -> updateTariff(observed, reread)
                }
            } catch (clientError: CatalogApiException.ClientError) {
                degrade("Tariff '${observed.name}' of '$slug' was refused: ${clientError.message}")
                null
            }
        }

        /** Preserve activation and any stored external ID when the observation omits it. A changed name is applied as a rename. */
        private suspend fun updateTariff(observed: TariffSnapshot, match: CatalogTariff): Long {
            val desired = tariffWrite(
                observed,
                isActive = match.isActive,
                externalId = observed.externalId ?: match.externalId,
            )
            if (echo(match) != desired && write("PUT /tariffs/{id}") { writer.updateTariff(match.id, desired) }) {
                // An observed in-stock -> out-of-stock flip shares the applyMissingTariffs counter,
                // so the summary agrees with flippingRows.
                if (match.inStock && !observed.inStock) tariffsMarkedOutOfStock++ else tariffsUpdated++
            }
            return match.id
        }

        /** Change only stock for missing rows, and only when the snapshot claims a complete list. */
        private suspend fun applyMissingTariffs(plan: TariffPlan) {
            if (!snapshot.tariffListComplete) return
            plan.missing.filter { it.inStock }.forEach { row ->
                val desired = echo(row).copy(inStock = false)
                if (write("PUT /tariffs/{id}") { writer.updateTariff(row.id, desired) }) {
                    tariffsMarkedOutOfStock++
                }
            }
        }

        /** Replace links only for observed locations. Skip the whole set if any location is unavailable to avoid unlinking still-valid locations. */
        private suspend fun applyLinks(resolved: List<ResolvedTariff>, locationIds: Map<LocationMatchKey, Long>) {
            if (snapshot.locations == null) return
            resolved.forEach { (observed, tariffId) ->
                val keys = observed.locationKeys ?: return@forEach
                val ids = keys.map { locationIds[it.matchKey()] }
                if (ids.any { it == null }) {
                    degrade(
                        "Locations of tariff '${observed.name}' of '$slug' were left unchanged: " +
                            "a location it links to could not be written",
                    )
                    return@forEach
                }
                write("PUT /tariffs/{id}/locations") {
                    writer.replaceTariffLocations(tariffId, ids.filterNotNull().distinct())
                }
            }
        }

        private suspend fun applyPrices(resolved: List<ResolvedTariff>) {
            resolved.forEach { (observed, tariffId) ->
                observed.prices.forEach { price ->
                    val desired = PriceWrite(
                        billingPeriod = price.billingPeriod.name,
                        amount = price.amount,
                        currency = price.currency,
                        dataSource = PriceDataSource.CRAWLER.name,
                    )
                    val outcome = try {
                        writer.putPrice(tariffId, desired)
                    } catch (conflict: CatalogApiException.Conflict) {
                        degrade("Price ${price.billingPeriod} of '${observed.name}' was refused: ${conflict.message}")
                        null
                    } catch (clientError: CatalogApiException.ClientError) {
                        degrade("Price ${price.billingPeriod} of '${observed.name}' was refused: ${clientError.message}")
                        null
                    } ?: return@forEach
                    if (outcome.appended) {
                        pricesAppended++
                        wroteSomething = true
                    } else {
                        pricesUnchanged++
                    }
                    if (outcome.noFxRate) noFxRateCount++
                }
            }
        }

        /** Use the same one-to-one matches for the guard and writes so the guard counts every planned stock flip. */
        private fun planTariffs(catalog: List<CatalogTariff>): TariffPlan {
            val matches = mutableListOf<Pair<TariffSnapshot, CatalogTariff?>>()
            val collisions = mutableListOf<TariffSnapshot>()
            snapshot.tariffs.orEmpty().forEach { observed ->
                val match = match(catalog, observed)
                when {
                    match == null -> matches += observed to null
                    claimedTariffIds.add(match.id) -> matches += observed to match
                    else -> collisions += observed
                }
            }
            return TariffPlan(
                matches = matches,
                missing = catalog.filter { it.id !in claimedTariffIds },
                collisions = collisions,
            )
        }

        /**
         * `externalId` first, then `lower(name)`, so a snapshot that newly supplies an `externalId`
         * still finds its row (and backfills it) instead of creating a duplicate name. Both keys are
         * unique (`uq_tariff_provider_external_id`, `uq_tariff_provider_name`).
         */
        private fun match(catalog: List<CatalogTariff>, observed: TariffSnapshot): CatalogTariff? =
            observed.externalId?.let { external -> catalog.firstOrNull { it.externalId == external } }
                ?: catalog.firstOrNull { it.name.lowercase() == observed.name.lowercase() }

        /** Count both missing rows and explicit out-of-stock observations so the guard covers both ways to flip stock. */
        private fun flippingRows(plan: TariffPlan): List<CatalogTariff> =
            plan.missing.filter { it.inStock } +
                plan.matches.mapNotNull { (observed, match) -> match?.takeIf { it.inStock && !observed.inStock } }

        private fun checkMassChange(state: CatalogState, plan: TariffPlan) {
            if (!snapshot.tariffListComplete) return
            val inStock = state.tariffs.count { it.inStock }
            if (inStock < MASS_CHANGE_GUARD_MIN_IN_STOCK) return
            val flipping = flippingRows(plan).size
            // Cross-multiplied for an exact comparison without rounding. Strict, so ratio 1.0 always passes.
            if (BigDecimal(flipping) <= maxOutOfStockRatio.multiply(BigDecimal(inStock))) return
            logger.warn(
                "Mass-change guard tripped for provider '{}': {} of {} in-stock tariffs would be flipped " +
                    "out of stock, above the allowed ratio {}; no writes were performed",
                slug,
                flipping,
                inStock,
                maxOutOfStockRatio.toPlainString(),
            )
            throw Halt(
                AdapterRunStatus.FAILED,
                FailureReason.MASS_CHANGE_GUARD,
                "$flipping of $inStock in-stock tariffs would have been flipped out of stock " +
                    "(limit ${maxOutOfStockRatio.toPlainString()})",
            )
        }

        /** Promote only a row read as CANDIDATE after a material write. Keep promotion last so each reconciliation attempts it at most once. */
        private suspend fun promoteIfCandidate(state: CatalogState) {
            if (state.detail?.provider?.systemStatus != SystemStatus.CANDIDATE.name) return
            if (!wroteSomething) return
            logger.info(
                "Provider '{}' was a candidate and this run wrote to it; promoting systemStatus {} -> {}",
                slug,
                SystemStatus.CANDIDATE,
                SystemStatus.CONFIGURED,
            )
            write("PUT /providers/{id}/system-status") { writer.promoteProviderToConfigured(state.providerId) }
        }

        /**
         * A write whose `4xx` (including an unexpected `409`) degrades the outcome to `PARTIAL`
         * instead of stopping the adapter. Returns whether the write happened.
         */
        private suspend fun write(kind: String, block: suspend () -> Unit): Boolean = try {
            block()
            wroteSomething = true
            true
        } catch (failure: CatalogApiException.Conflict) {
            degrade("$kind for '$slug' was refused: ${failure.message}")
            false
        } catch (failure: CatalogApiException.ClientError) {
            degrade("$kind for '$slug' was refused: ${failure.message}")
            false
        }

        /** [problem] must never contain a request body. */
        private fun degrade(problem: String) {
            degraded = true
            if (firstProblem == null) firstProblem = problem
            logger.warn("{}", problem)
        }

        private fun providerWrite(provider: ProviderInfo) = ProviderWrite(
            slug = provider.slug,
            name = provider.name,
            websiteUrl = provider.websiteUrl,
            status = provider.status.name,
            description = provider.description,
        )

        private fun providerWrite(provider: CatalogProvider) = ProviderWrite(
            slug = provider.slug,
            name = provider.name,
            websiteUrl = provider.websiteUrl,
            status = provider.status,
            description = provider.description,
        )

        private fun locationWrite(location: LocationSnapshot) = LocationWrite(
            countryCode = location.key.countryCode,
            region = location.region,
            city = location.key.city,
            datacenterName = location.key.datacenterName,
            latitude = location.latitude,
            longitude = location.longitude,
        )

        /** Compare coordinates numerically because NUMERIC(9, 6) can change scale on readback. */
        private fun locationDiffers(current: CatalogLocation, desired: LocationWrite): Boolean =
            current.countryCode != desired.countryCode ||
                current.region != desired.region ||
                current.city != desired.city ||
                current.datacenterName != desired.datacenterName ||
                !sameDecimal(current.latitude, desired.latitude) ||
                !sameDecimal(current.longitude, desired.longitude)

        private fun sameDecimal(left: BigDecimal?, right: BigDecimal?): Boolean = when {
            left == null || right == null -> left == null && right == null
            else -> left.compareTo(right) == 0
        }

        private fun tariffWrite(observed: TariffSnapshot, isActive: Boolean, externalId: String?) = TariffWrite(
            name = observed.name,
            vcpuCount = observed.vcpuCount,
            ramMb = observed.ramMb,
            diskGb = observed.diskGb,
            diskType = observed.diskType.name,
            ipv4Count = observed.ipv4Count,
            virtualization = observed.virtualization.name,
            isActive = isActive,
            inStock = observed.inStock,
            externalId = externalId,
            url = observed.url,
            cpuModel = observed.cpuModel,
            bandwidthMbps = observed.bandwidthMbps,
            trafficLimitGb = observed.trafficLimitGb,
            ipv6Supported = observed.ipv6Supported,
        )

        private fun echo(row: CatalogTariff) = TariffWrite(
            name = row.name,
            vcpuCount = row.vcpuCount,
            ramMb = row.ramMb,
            diskGb = row.diskGb,
            diskType = row.diskType,
            ipv4Count = row.ipv4Count,
            virtualization = row.virtualization,
            isActive = row.isActive,
            inStock = row.inStock,
            externalId = row.externalId,
            url = row.url,
            cpuModel = row.cpuModel,
            bandwidthMbps = row.bandwidthMbps,
            trafficLimitGb = row.trafficLimitGb,
            ipv6Supported = row.ipv6Supported,
        )
    }

    /** What the up-front read found. [detail] is `null` for a provider this run just created. */
    private class CatalogState(
        val providerId: Long,
        val detail: CatalogProviderDetail?,
        val tariffs: List<CatalogTariff>,
    )

    private class TariffPlan(
        val matches: List<Pair<TariffSnapshot, CatalogTariff?>>,
        /** Catalog rows the snapshot does not mention -- candidates for `inStock = false`. */
        val missing: List<CatalogTariff>,
        /** Observed tariffs whose match was already claimed by an earlier one; skipped. */
        val collisions: List<TariffSnapshot>,
    )

    private data class ResolvedTariff(val observed: TariffSnapshot, val tariffId: Long)

    private class Halt(
        val status: AdapterRunStatus,
        val reason: FailureReason,
        val text: String?,
    ) : Exception(text)

    private companion object {
        val logger = LoggerFactory.getLogger(Reconciler::class.java)
    }
}

/** The key in the form `uq_location_provider_country_city_dc` compares. */
private fun CatalogLocation.matchKey(): LocationMatchKey =
    LocationKey(countryCode = countryCode, city = city, datacenterName = datacenterName).matchKey()
