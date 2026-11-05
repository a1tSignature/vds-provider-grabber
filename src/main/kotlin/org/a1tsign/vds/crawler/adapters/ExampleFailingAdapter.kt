package org.a1tsign.vds.crawler.adapters

import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.model.ProviderSnapshot

/**
 * The failure-isolation reference adapter (spec req. 42): [fetch] always throws, so a run that
 * includes it demonstrates -- in the run summary and in the log, on a live box as well as in the
 * end-to-end test -- that one broken adapter is `FAILED` alone while every other adapter of the
 * same run still reaches the catalog (spec req. 18).
 *
 * Registered with `includedByDefault = false`, like [ExampleStaticAdapter], so an empty
 * `VDS_CRAWLER_ADAPTERS` never runs it; an operator opts in by naming `example-failing`.
 *
 * It throws a plain exception on purpose: the runner's isolation must hold for *anything* an
 * adapter throws, not only for the crawler's own exception types.
 */
class ExampleFailingAdapter : ProviderAdapter {

    override val id: String = ID

    override val providerSlug: String = PROVIDER_SLUG

    override val sourceKind: SourceKind = SourceKind.API

    override suspend fun fetch(ctx: CrawlContext): ProviderSnapshot =
        throw IllegalStateException(FAILURE_MESSAGE)

    companion object {
        const val ID: String = "example-failing"
        const val PROVIDER_SLUG: String = "example-failing"

        /** Says it is deliberate, so nobody hunts for a bug behind the WARN line. */
        const val FAILURE_MESSAGE: String =
            "example-failing always fails: this adapter exists to demonstrate failure isolation"
    }
}
