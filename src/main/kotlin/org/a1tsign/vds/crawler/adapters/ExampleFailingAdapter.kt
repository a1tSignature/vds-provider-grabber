package org.a1tsign.vds.crawler.adapters

import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.model.ProviderSnapshot

/** Opt-in reference adapter that throws a plain exception to exercise failure isolation beyond crawler-specific exceptions. */
class ExampleFailingAdapter : ProviderAdapter {

    override val id: String = ID

    override val providerSlug: String = PROVIDER_SLUG

    override val sourceKind: SourceKind = SourceKind.API

    override suspend fun fetch(ctx: CrawlContext): ProviderSnapshot =
        throw IllegalStateException(FAILURE_MESSAGE)

    companion object {
        const val ID: String = "example-failing"
        const val PROVIDER_SLUG: String = "example-failing"

        const val FAILURE_MESSAGE: String =
            "example-failing always fails: this adapter exists to demonstrate failure isolation"
    }
}
