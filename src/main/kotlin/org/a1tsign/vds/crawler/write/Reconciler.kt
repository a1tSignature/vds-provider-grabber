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
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.PriceDataSource
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

/** Below this many in-stock tariffs the mass-change guard never trips (ADR-03 point 4). */
const val MASS_CHANGE_GUARD_MIN_IN_STOCK: Int = 3

/**
 * Brings the catalog to the state one [ProviderSnapshot] describes, through the vds-001 admin API
 * and nothing else (ADR-02). This is where a bug destroys real data -- the price history is
 * append-only and not back-correctable -- so the four rules of ADR-03 live here:
 *
 * 1. **`null` is "not observed".** A `null` snapshot section is skipped entirely; in particular the
 *    two replace-the-whole-set endpoints (`…/payment-methods`, `…/tariffs/{id}/locations`) are
 *    called only for a non-null set, and an **empty** non-null set is an observation that really
 *    does clear the catalog's set.
 * 2. **Never destroy.** No `DELETE` is reachable from here ([CrawlWriter] has none) and no write
 *    ever carries a computed `isActive`: every tariff update echoes the value just read from the
 *    catalog, because `TariffWriteRequest.isActive` defaults to `true` on the vds-001 side and an
 *    omitted value would resurrect a deactivated tariff.
 * 3. **Only a complete tariff list may flip stock.** A catalog tariff the snapshot does not mention
 *    is updated to `inStock = false` -- and to nothing else -- only when `tariffListComplete` is
 *    `true`; otherwise it is left completely alone.
 * 4. **Mass-change guard.** Before the first write of a `tariffListComplete = true` snapshot, the
 *    share of currently in-stock tariffs that would flip is compared against `maxOutOfStockRatio`;
 *    over it (and with at least [MASS_CHANGE_GUARD_MIN_IN_STOCK] in-stock tariffs) the adapter
 *    fails with [FailureReason.MASS_CHANGE_GUARD] having performed **zero** writes.
 *
 * ### Read before write (spec req. 24), and the only reads that follow one
 *
 * Per provider the catalog is read once up front: `?q=<slug>` → `GET /providers/{id}` →
 * `GET /providers/{id}/tariffs?activeOnly=false`. Only the differences are then sent. The sole
 * reads *after* a write are the re-reads that absorb a `409` (another writer won the race, spec
 * req. 25); they cannot happen in a dry run, which writes nothing and therefore conflicts with
 * nothing.
 *
 * ### Write order and convergence (spec req. 29)
 *
 * provider → legal info → payment methods → locations → tariffs → tariff↔location links → prices.
 * The run is **not** atomic; the contract is convergence -- re-running the same snapshot after any
 * interruption reaches the same end state and creates no duplicates.
 *
 * ### Failure policy (spec req. 33)
 *
 * | Failure | Reaction |
 * |---|---|
 * | `409` on create (provider / location / tariff) | re-read, reuse the existing row, stay `SUCCESS` |
 * | `4xx` ≠ `409` on the provider create | `PARTIAL`, stop: nothing can follow without an id |
 * | `4xx` ≠ `409` on provider/legal/payment-methods | `PARTIAL`, continue with the rest |
 * | `4xx` ≠ `409` on one location | `PARTIAL`, continue; link sets that needed it are **skipped whole**, never truncated |
 * | `4xx` ≠ `409` on a tariff **create** | `PARTIAL`, skip that tariff whole -- there is no id to hang its links and prices on -- and continue with the next tariff |
 * | `4xx` ≠ `409` on a tariff **update** | `PARTIAL`, but the row exists, so its links and prices are still applied: a rejected field must not cost the tariff its price history |
 * | `4xx` ≠ `409` on a link set or one price | `PARTIAL`, continue with the next |
 * | `5xx` / transport failure (already retried by the client) | `FAILED`, stop |
 * | a second consecutive `401` | propagated -- the runner turns it into a whole-run failure |
 *
 * There is no `catch (Throwable)` here: producing a run status out of an arbitrary failure is the
 * runner's job (task vds-002.6), and `CancellationException` must stay uncaught so the per-adapter
 * timeout works.
 *
 * Reconciling writes no timestamp of its own (`effectiveFrom` is always the server's "now"), so
 * this class needs no `Clock`.
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
                // Never swallowed: the whole run has to stop, not just this adapter (spec req. 23).
                throw unauthorized
            } catch (failure: CatalogApiException) {
                // A 5xx or a transport failure, i.e. the client's retry budget is already spent, or
                // a read of the current state failed. Continuing would write against an unknown
                // state; the writes already applied stay and are reported.
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
            // Before the first write that could flip anything -- and, for an existing provider,
            // before the first write at all.
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
        }

        // ---------- provider ----------

        /**
         * The provider row every later write hangs off: the existing one, or the one created here.
         * A `409` on the create means a concurrent writer won the race -- re-read and continue,
         * never fail (spec req. 25).
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
            // detail = null: we just created the provider, so legal info, payment methods,
            // locations and tariffs are all empty -- and in a dry run the id is fabricated, so it
            // must never be read back.
            return CatalogState(created, detail = null, tariffs = emptyList())
        }

        private suspend fun readState(): CatalogState? {
            val summary = client.findProviderBySlug(slug) ?: return null
            return CatalogState(summary.id, client.getProvider(summary.id), client.listAllTariffs(summary.id))
        }

        // ---------- legal info, payment methods ----------

        private suspend fun applyLegalInfo(state: CatalogState) {
            val observed = snapshot.legalInfo ?: return
            val desired = LegalInfoWrite(
                dataSource = LegalDataSource.CRAWLER.name,
                confidence = observed.confidence.name,
                legalEntityName = observed.legalEntityName,
                registrationCountry = observed.registrationCountry,
                registrationNumber = observed.registrationNumber,
                legalAddress = observed.legalAddress,
                sourceUrl = observed.sourceUrl,
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
                )
            }
            if (current != desired) {
                write("PUT /providers/{id}/legal-info") { writer.putLegalInfo(state.providerId, desired) }
            }
        }

        /**
         * Replaces the whole set, so it runs **only** for an observed (non-null) one. An unchanged
         * method set is not rewritten -- which also preserves a `note` a human wrote next to a
         * method the crawler cannot observe.
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

        // ---------- locations ----------

        /** Location id per match key: the ones that were already there plus the ones created here. */
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
            writer.createLocation(state.providerId, desired).also { locationsCreated++ }
        } catch (conflict: CatalogApiException.Conflict) {
            // uq_location_provider_country_city_dc refused it: somebody created the same row
            // between our read and this call. Re-read and reuse it (spec req. 25).
            logger.info("Location {} of '{}' already exists; reusing the existing row", key, slug)
            val reread = client.getProvider(state.providerId).locations.firstOrNull { it.matchKey() == key }
            if (reread == null) {
                degrade("Location $key of '$slug' was refused with 409 but cannot be found either")
                null
            } else {
                // Reused, not created: the counter stays where it is.
                reread.id
            }
        } catch (clientError: CatalogApiException.ClientError) {
            degrade("Location $key of '$slug' was refused: ${clientError.message}")
            null
        }

        // ---------- tariffs ----------

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
            // isActive = true is the only value a *create* may carry: a new row cannot be echoing
            // anything, and the crawler never creates a deactivated tariff.
            val desired = tariffWrite(observed, isActive = true, externalId = observed.externalId)
            return try {
                writer.createTariff(state.providerId, desired).also { tariffsCreated++ }
            } catch (conflict: CatalogApiException.Conflict) {
                logger.info("Tariff '{}' of '{}' already exists; reusing the existing row", observed.name, slug)
                val reread = match(client.listAllTariffs(state.providerId), observed)
                if (reread == null) {
                    degrade("Tariff '${observed.name}' of '$slug' was refused with 409 but cannot be found either")
                    null
                } else {
                    updateTariff(observed, reread)
                }
            } catch (clientError: CatalogApiException.ClientError) {
                degrade("Tariff '${observed.name}' of '$slug' was refused: ${clientError.message}")
                null
            }
        }

        /**
         * The matched row brought to the snapshot's values -- with `isActive` **echoed from the
         * catalog** and an `externalId` that is only ever added, never cleared. A differing name is
         * a normal rename and is applied.
         */
        private suspend fun updateTariff(observed: TariffSnapshot, match: CatalogTariff): Long {
            val desired = tariffWrite(
                observed,
                isActive = match.isActive,
                externalId = observed.externalId ?: match.externalId,
            )
            if (echo(match) != desired && write("PUT /tariffs/{id}") { writer.updateTariff(match.id, desired) }) {
                tariffsUpdated++
            }
            return match.id
        }

        /**
         * The disappearance signal, and the only one: `inStock = false` and **nothing else** about
         * the row changes, which is why the write is the catalog's own values with that one flag
         * flipped. Runs only for a `tariffListComplete = true` snapshot.
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

        // ---------- links, prices ----------

        /**
         * Replaces the whole link set, so it runs only for a tariff whose `locationKeys` were
         * observed. A set that cannot be rendered completely (one of its locations was refused) is
         * skipped **whole**: sending the remainder would unlink the rest.
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
                    if (outcome.appended) pricesAppended++ else pricesUnchanged++
                    if (outcome.noFxRate) noFxRateCount++
                }
            }
        }

        // ---------- matching and the guard ----------

        /**
         * Which catalog row each observed tariff is, decided once and used by both the guard and
         * the write phase -- so the number of tariffs the guard counts as disappearing is exactly
         * the number the write phase would flip.
         *
         * `externalId` first, then `lower(name)` (spec req. 25). A row already claimed by an
         * earlier observed tariff is not claimed twice: that snapshot would otherwise write two
         * different tariffs into one row.
         */
        private fun planTariffs(catalog: List<CatalogTariff>): TariffPlan {
            val matches = mutableListOf<Pair<TariffSnapshot, CatalogTariff?>>()
            val collisions = mutableListOf<TariffSnapshot>()
            val claimed = mutableSetOf<Long>()
            snapshot.tariffs.orEmpty().forEach { observed ->
                val match = match(catalog, observed)
                when {
                    match == null -> matches += observed to null
                    claimed.add(match.id) -> matches += observed to match
                    else -> collisions += observed
                }
            }
            return TariffPlan(
                matches = matches,
                missing = catalog.filter { it.id !in claimed },
                collisions = collisions,
            )
        }

        /**
         * The two keys **in order**: the snapshot's `externalId` first, then `lower(name)` -- so a
         * snapshot that newly supplies an `externalId` still finds the row it has always meant
         * (whose `externalId` is then backfilled) instead of trying to create a duplicate name.
         * Both indexes are unique in vds-001 (`uq_tariff_provider_external_id`,
         * `uq_tariff_provider_name`), so neither lookup can be ambiguous.
         */
        private fun match(catalog: List<CatalogTariff>, observed: TariffSnapshot): CatalogTariff? =
            observed.externalId?.let { external -> catalog.firstOrNull { it.externalId == external } }
                ?: catalog.firstOrNull { it.name.lowercase() == observed.name.lowercase() }

        private fun checkMassChange(state: CatalogState, plan: TariffPlan) {
            if (!snapshot.tariffListComplete) return
            val inStock = state.tariffs.count { it.inStock }
            if (inStock < MASS_CHANGE_GUARD_MIN_IN_STOCK) return
            val flipping = plan.missing.count { it.inStock }
            // Cross-multiplied instead of divided: an exact BigDecimal comparison with no rounding
            // and no Double anywhere. "Exceeds" is strict, so ratio 1.0 always passes.
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

        // ---------- write helpers ----------

        /**
         * One write whose failure must not stop the adapter: a `4xx` (including an unexpected `409`
         * on an endpoint where it is not a race) degrades the outcome to `PARTIAL` and the caller
         * continues. Returns whether the write actually happened, so a counter is only raised for a
         * write that did.
         */
        private suspend fun write(kind: String, block: suspend () -> Unit): Boolean = try {
            block()
            true
        } catch (failure: CatalogApiException.Conflict) {
            degrade("$kind for '$slug' was refused: ${failure.message}")
            false
        } catch (failure: CatalogApiException.ClientError) {
            degrade("$kind for '$slug' was refused: ${failure.message}")
            false
        }

        /**
         * Records a `4xx` as `PARTIAL`. The WARN carries method, path, status and the vds-001 error
         * envelope's `message`/`details` (all of them inside [CatalogApiException.message]) and
         * never the request body.
         */
        private fun degrade(problem: String) {
            degraded = true
            if (firstProblem == null) firstProblem = problem
            logger.warn("{}", problem)
        }

        // ---------- request builders ----------

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
         * Coordinates are compared with `compareTo`: the catalog returns `NUMERIC(9,6)`, so
         * `52.52` comes back as `52.520000` and `BigDecimal.equals` -- which data-class equality
         * would use -- would report a difference on every single run.
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

/** The catalog row's key in the form vds-001's `uq_location_provider_country_city_dc` compares. */
private fun CatalogLocation.matchKey(): LocationMatchKey =
    LocationKey(countryCode = countryCode, city = city, datacenterName = datacenterName).matchKey()
