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

/**
 * What one `reconcile` call did to one provider: the counters of the run summary, the planned
 * operations a dry run recorded while it ran, and this adapter's contribution to the run status.
 *
 * [status] is [AdapterRunStatus.SUCCESS], [AdapterRunStatus.PARTIAL] (some write was refused with a
 * `4xx`, everything else was applied) or [AdapterRunStatus.FAILED]; the counters are valid in every
 * case, because writes already applied are never rolled back.
 */
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
    /** Empty unless the writer was a [DryRunWriter]: the mutations this adapter would have made. */
    val plannedOperations: List<String> = emptyList(),
)

/** Below this many in-stock tariffs the mass-change guard never trips. */
const val MASS_CHANGE_GUARD_MIN_IN_STOCK: Int = 3

/**
 * Brings the catalog to the state one [ProviderSnapshot] describes, through the admin API only.
 * The price history is append-only and cannot be corrected afterwards, so these rules hold:
 *
 * 1. `null` means "not observed": a `null` section is skipped. The replace-the-whole-set endpoints
 *    (`…/payment-methods`, `…/tariffs/{id}/locations`) are called only for a non-null set; an empty
 *    non-null set does clear the catalog's set.
 * 2. Never destroy: [CrawlWriter] has no delete, and every tariff update echoes the catalog's
 *    `isActive`, because the API defaults an omitted `isActive` to `true` and would resurrect a
 *    deactivated tariff.
 * 3. A tariff missing from the snapshot is set to `inStock = false`, and nothing else, only when
 *    `tariffListComplete` is `true`.
 * 4. Mass-change guard: if the share of in-stock tariffs that would flip exceeds
 *    `maxOutOfStockRatio` (with at least [MASS_CHANGE_GUARD_MIN_IN_STOCK] in stock), the adapter
 *    fails with [FailureReason.MASS_CHANGE_GUARD] before any write.
 *
 * The catalog is read once up front (`?q=<slug>`, `GET /providers/{id}`,
 * `GET /providers/{id}/tariffs?activeOnly=false`); later reads only absorb a `409`.
 * Write order: provider, legal info, payment methods, locations, tariffs, tariff-location links,
 * prices. The run is not atomic; re-running the same snapshot converges without duplicates.
 *
 * | Failure | Reaction |
 * |---|---|
 * | `409` on create (provider / location / tariff) | re-read, reuse the existing row, stay `SUCCESS` |
 * | other `4xx` on the provider create | `PARTIAL`, stop: nothing can follow without an id |
 * | other `4xx` on provider/legal/payment-methods | `PARTIAL`, continue |
 * | other `4xx` on one location | `PARTIAL`, continue; link sets that needed it are skipped whole, never truncated |
 * | other `4xx` on a tariff create | `PARTIAL`, skip that tariff with its links and prices, continue |
 * | other `4xx` on a tariff update | `PARTIAL`; the row exists, so its links and prices are still applied |
 * | other `4xx` on a link set or one price | `PARTIAL`, continue |
 * | `5xx` / transport failure (already retried by the client) | `FAILED`, stop |
 * | a second consecutive `401` | propagated; the runner fails the whole run |
 *
 * No `catch (Throwable)`: the runner turns arbitrary failures into a status, and
 * `CancellationException` must stay uncaught for the per-adapter timeout.
 * No `Clock` is needed: `effectiveFrom` is always the server's "now".
 */
class Reconciler(private val client: CatalogApiClient) {

    suspend fun reconcile(
        adapter: ProviderAdapter,
        snapshot: ProviderSnapshot,
        writer: CrawlWriter,
        maxOutOfStockRatio: BigDecimal,
    ): ReconcileOutcome = Pass(adapter, snapshot, writer, maxOutOfStockRatio).run()

    /** One `reconcile` call's state. A fresh instance per call, so no counter can leak between adapters. */
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

        /**
         * Whether this pass mutated anything; gates the promotion of a `CANDIDATE` row. An unchanged
         * price (a `PUT` answered `200` without storing a row) does not count.
         */
        private var wroteSomething = false

        /**
         * Catalog tariff ids already bound to an observed tariff, shared by [planTariffs] and the
         * `409` re-read in [createTariff]: a row created concurrently mid-run was not planned
         * against, and without this set two snapshot tariffs could be written into it.
         */
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
                // The whole run has to stop, not just this adapter.
                throw unauthorized
            } catch (failure: CatalogApiException) {
                // 5xx or transport failure after the client's retries, or a failed read. Continuing
                // would write against an unknown state; writes already applied stay and are reported.
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
            // For an existing provider this runs before the first write at all.
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
                // Nothing at all can follow without a provider id, so this is where the adapter ends.
                logger.warn("Creating provider '{}' was refused; the adapter is PARTIAL: {}", slug, clientError.message)
                throw Halt(AdapterRunStatus.PARTIAL, FailureReason.WRITE, clientError.message)
            }
            providersCreated++
            wroteSomething = true
            // A just-created provider has nothing to read, and in a dry run the id is fabricated,
            // so it must never be read back.
            return CatalogState(created, detail = null, tariffs = emptyList())
        }

        private suspend fun readState(): CatalogState? {
            val summary = client.findProviderBySlug(slug) ?: return null
            return CatalogState(summary.id, client.getProvider(summary.id), client.listAllTariffs(summary.id))
        }

        /**
         * The catalog applies "manual wins" itself, so a `MANUAL` field that differs from the
         * observation is re-sent on every run and ignored there.
         */
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
            // The catalog leaves an unobserved entity or country alone, so it is no reason to write.
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
            // A new row has nothing to echo, and the crawler never creates a deactivated tariff.
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
                        // The planTariffs collision for a row created concurrently after planning.
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

        /**
         * Brings the matched row to the snapshot's values. `isActive` is echoed from the catalog,
         * `externalId` is only added, never cleared, and a differing name is applied as a rename.
         */
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

        /**
         * Writes the catalog's own values with only `inStock` set to `false`. Runs only for a
         * `tariffListComplete = true` snapshot.
         */
        private suspend fun applyMissingTariffs(plan: TariffPlan) {
            if (!snapshot.tariffListComplete) return
            plan.missing.filter { it.inStock }.forEach { row ->
                val desired = echo(row).copy(inStock = false)
                if (write("PUT /tariffs/{id}") { writer.updateTariff(row.id, desired) }) {
                    tariffsMarkedOutOfStock++
                }
            }
        }

        /**
         * Replaces the whole link set, so it runs only for observed `locationKeys`. A set with a
         * refused location is skipped whole: sending the remainder would unlink the rest.
         */
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

        /**
         * Matches each observed tariff to a catalog row once, for both the guard and the write
         * phase, so the guard counts exactly what the write phase would flip. A row already claimed
         * by an earlier observed tariff is not claimed twice.
         */
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

        /**
         * In-stock rows the write phase would flip: those missing from the snapshot and those it
         * reports as `inStock = false`. Counting only the first would let a broken "in stock"
         * selector wipe the provider with the guard silent.
         */
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

        /**
         * Promotes a row read as `CANDIDATE` to `CONFIGURED` once this pass has written something.
         * `REJECTED` and `CONFIGURED` rows are never touched, and a provider created by this pass is
         * already `CONFIGURED` (the crawler's `POST` never sends `systemStatus`). Must stay the last
         * step of [apply], so a run promotes at most once.
         */
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

        /**
         * Coordinates use `compareTo`: the catalog returns `NUMERIC(9,6)`, so `52.52` comes back as
         * `52.520000` and `BigDecimal.equals` would report a difference on every run.
         */
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

        /** The catalog row as the write that would leave it exactly as it is. */
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
        /** Every observed tariff with the catalog row it is, or `null` when it has to be created. */
        val matches: List<Pair<TariffSnapshot, CatalogTariff?>>,
        /** Catalog rows the snapshot does not mention -- candidates for `inStock = false`. */
        val missing: List<CatalogTariff>,
        /** Observed tariffs whose match was already claimed by an earlier one; skipped. */
        val collisions: List<TariffSnapshot>,
    )

    private data class ResolvedTariff(val observed: TariffSnapshot, val tariffId: Long)

    /** Ends the adapter early with a status; never escapes [Reconciler]. */
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
