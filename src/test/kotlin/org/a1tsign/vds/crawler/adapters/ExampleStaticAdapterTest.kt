package org.a1tsign.vds.crawler.adapters

import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.SnapshotValidator
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.model.LegalInfoSnapshot
import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.PriceSnapshot
import org.a1tsign.vds.crawler.model.ProviderInfo
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.a1tsign.vds.crawler.support.StubPageFetcher
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Virtualization
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Use the same fixture markup as the end-to-end test to exercise the reference selectors. */
class ExampleStaticAdapterTest {

    private val adapter = ExampleStaticAdapter()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-05T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `the fixture page parses into the complete expected snapshot`() {
        val snapshot = fetch(fixture("example-static.html"))

        val locationKeys = listOf(
            LocationKey("DE", "Falkenstein", "FSN1"),
            LocationKey("FI", "Helsinki", "HEL1"),
        )
        assertEquals(
            ProviderSnapshot(
                provider = ProviderInfo(
                    slug = "example-static",
                    name = "Example Static Hosting",
                    websiteUrl = "https://example-static.test/",
                    status = ProviderStatus.ACTIVE,
                ),
                legalInfo = LegalInfoSnapshot(
                    legalEntityName = "Example Static Hosting GmbH",
                    registrationCountry = "DE",
                    registrationNumber = "HRB 123456 B",
                    // Written across two padded lines in the fixture: jsoup collapses it.
                    legalAddress = "Beispielstrasse 1, 10115 Berlin",
                    confidence = Confidence.LOW,
                    sourceUrl = PAGE_URL,
                ),
                paymentMethods = setOf(PaymentMethod.VISA_MASTERCARD, PaymentMethod.BANK_WIRE),
                locations = listOf(
                    LocationSnapshot(key = locationKeys[0], region = "Saxony"),
                    LocationSnapshot(key = locationKeys[1], region = "Uusimaa"),
                ),
                tariffs = listOf(
                    // "$ 4.51" / "$48.70": the symbol with and without a space.
                    plan("esh-cx11", "Static CX11", 1, 2048, 20, DiskType.NVME, 1, "4.51", "48.70", locationKeys),
                    // "$&nbsp;96.98": a non-breaking space, which jsoup's text() normalises to a plain space.
                    plan("esh-cx21", "Static CX21", 2, 4096, 40, DiskType.NVME, 1, "8.98", "96.98", locationKeys),
                    // "$ 1,299.00": grouped thousands.
                    plan("esh-cx51", "Static CX51", 8, 32768, 360, DiskType.SSD, 2, "119.00", "1299.00", locationKeys),
                ),
                tariffListComplete = true,
                observedAt = clock.instant(),
                sourceUrls = listOf(PAGE_URL),
            ),
            snapshot,
        )
    }

    @Test
    fun `the parsed snapshot is writable as it stands, so no reconcile can fail on validation`() {
        val problems = SnapshotValidator(clock).validate(adapter, fetch(fixture("example-static.html")))

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the price-change variant differs from the base page in exactly one MONTHLY amount`() {
        val base = fetch(fixture("example-static.html"))
        val changed = fetch(fixture("example-static-price-change.html"))

        assertEquals(BigDecimal("4.51"), monthly(base, "Static CX11"))
        assertEquals(BigDecimal("5.49"), monthly(changed, "Static CX11"))
        assertEquals(
            base.tariffs!!.map { it.copy(prices = emptyList()) },
            changed.tariffs!!.map { it.copy(prices = emptyList()) },
            "only a price may differ between the two fixtures",
        )
        assertEquals(
            base.tariffs!!.drop(1).map { it.prices },
            changed.tariffs!!.drop(1).map { it.prices },
            "the other two plans keep both of their prices",
        )
        assertEquals(base.copy(tariffs = null), changed.copy(tariffs = null))
    }


    @Test
    fun `a missing base URL is this adapter's own failure, naming the variable`() {
        val missing = assertFailsWith<AdapterFailureException> { fetch(fixture("example-static.html"), config = emptyMap()) }
        val blank = assertFailsWith<AdapterFailureException> {
            fetch(fixture("example-static.html"), config = mapOf(ExampleStaticAdapter.BASE_URL_CONFIG_KEY to "   "))
        }

        listOf(missing, blank).forEach { failure ->
            assertTrue(
                failure.message!!.contains(ExampleStaticAdapter.BASE_URL_VARIABLE),
                failure.message,
            )
        }
    }

    @Test
    fun `a base URL that is not an absolute http URL is this adapter's own failure`() {
        listOf("example-static.test/vps", "ftp://example-static.test", "http://", "ht tp://x") .forEach { configured ->
            val failure = assertFailsWith<AdapterFailureException>(configured) {
                fetch(fixture("example-static.html"), config = mapOf(ExampleStaticAdapter.BASE_URL_CONFIG_KEY to configured))
            }
            assertTrue(failure.message!!.contains(ExampleStaticAdapter.BASE_URL_VARIABLE), failure.message)
        }
    }

    @Test
    fun `a trailing slash on the base URL does not double the separator`() {
        val snapshot = fetch(
            fixture("example-static.html"),
            config = mapOf(ExampleStaticAdapter.BASE_URL_CONFIG_KEY to "$BASE_URL/"),
        )

        assertEquals(listOf(PAGE_URL), snapshot.sourceUrls)
    }


    @Test
    fun `a price the adapter cannot read fails the adapter instead of becoming zero`() {
        val page = fixture("example-static.html").replace("""<td class="price-monthly">$ 4.51</td>""", """<td class="price-monthly">ask us</td>""")

        val failure = assertFailsWith<AdapterFailureException> { fetch(page) }

        assertTrue(failure.message!!.contains("td.price-monthly"), failure.message)
        assertTrue(failure.message!!.contains("ask us"), failure.message)
    }

    @Test
    fun `a price with a currency symbol the adapter does not know fails the adapter`() {
        val page = fixture("example-static.html").replace("""<td class="price-annual">$48.70</td>""", """<td class="price-annual">¤48.70</td>""")

        assertTrue(assertFailsWith<AdapterFailureException> { fetch(page) }.message!!.contains("td.price-annual"))
    }

    @Test
    fun `a price with more decimals than the catalog stores fails the adapter`() {
        val page = fixture("example-static.html").replace("""<td class="price-monthly">$8.98</td>""", """<td class="price-monthly">$8.98765</td>""")

        assertTrue(assertFailsWith<AdapterFailureException> { fetch(page) }.message!!.contains("td.price-monthly"))
    }

    @Test
    fun `a missing section, a missing value and a non-numeric cell each name what broke`() {
        val cases = mapOf(
            // Remove the whole section. Treating a broken selector as an empty observation could clear the catalog set.
            """<section class="payments">""" to ("""<section class="payments-renamed">""" to "section.payments"),
            """<h1 class="provider-name">Example Static Hosting</h1>""" to
                ("""<h1 class="provider-name"></h1>""" to "h1.provider-name"),
            """<td class="ram-mb">2048</td>""" to ("""<td class="ram-mb">2 GB</td>""" to "td.ram-mb"),
            """data-disk-type="NVME" data-virtualization="KVM">
      <td class="plan-name">Static CX11</td>""" to
                (
                    """data-disk-type="OPTANE" data-virtualization="KVM">
      <td class="plan-name">Static CX11</td>""" to "data-disk-type"
                    ),
            """<a class="provider-site" href="https://example-static.test/">""" to
                ("""<a class="provider-site">""" to "href"),
        )

        cases.forEach { (original, replacement) ->
            val page = fixture("example-static.html")
            assertTrue(page.contains(original), "fixture no longer contains: $original")
            val failure = assertFailsWith<AdapterFailureException>(original) {
                fetch(page.replace(original, replacement.first))
            }
            assertTrue(failure.message!!.contains(replacement.second), "${failure.message} (expected ${replacement.second})")
            if (replacement.second == "data-disk-type") {
                assertTrue(failure.message!!.contains("NVME"), failure.message)
            }
        }
    }

    @Test
    fun `an empty plans table is parsed as an honest empty complete list, left to the mass-change guard`() {
        // An observed empty complete plan list reaches the reconciler's mass-change guard.
        val page = fixture("example-static.html").replace(Regex("""<tr class="plan"[\s\S]*?</tr>"""), "")

        val snapshot = fetch(page)

        assertEquals(emptyList(), snapshot.tariffs)
        assertTrue(snapshot.tariffListComplete)
    }

    @Test
    fun `a locations table with no row is this adapter's own failure, not an honest empty list`() {
        // A missing location section must fail because an observed empty set can clear all tariff links.
        val page = fixture("example-static.html").replace(Regex("""<tr class="location"[\s\S]*?</tr>"""), "")

        val failure = assertFailsWith<AdapterFailureException> { fetch(page) }

        assertTrue(failure.message!!.contains("tr.location"), failure.message)
    }


    @Test
    fun `the production registry ships exactly the two reference adapters and selects neither by default`() {
        val registry = AdapterRegistry.production()

        assertEquals(
            listOf("example-static" to SourceKind.HTML, "example-failing" to SourceKind.API),
            registry.entries.map { it.adapter.id to it.adapter.sourceKind },
        )
        assertEquals(
            listOf("example-static", "example-failing"),
            registry.entries.map { it.adapter.providerSlug },
        )
        assertEquals(
            emptyList(),
            registry.select(emptyList()).map { it.id },
            "an empty VDS_CRAWLER_ADAPTERS must run nothing in production (spec req. 42)",
        )
        assertEquals(listOf("example-failing"), registry.select(listOf("example-failing")).map { it.id })
    }

    @Test
    fun `the failing reference adapter always throws with a message that says it is deliberate`() {
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking {
                ExampleFailingAdapter().fetch(
                    CrawlContext(StubPageFetcher(), clock, UUID.randomUUID(), emptyMap()),
                )
            }
        }

        assertEquals(ExampleFailingAdapter.FAILURE_MESSAGE, failure.message)
    }


    private fun fetch(
        page: String,
        config: Map<String, String> = mapOf(ExampleStaticAdapter.BASE_URL_CONFIG_KEY to BASE_URL),
    ): ProviderSnapshot = runBlocking {
        adapter.fetch(CrawlContext(StubPageFetcher(page), clock, UUID.randomUUID(), config))
    }

    private fun monthly(snapshot: ProviderSnapshot, tariffName: String): BigDecimal =
        snapshot.tariffs!!.single { it.name == tariffName }
            .prices.single { it.billingPeriod == BillingPeriod.MONTHLY }.amount

    private fun plan(
        externalId: String,
        name: String,
        vcpu: Int,
        ramMb: Int,
        diskGb: Int,
        diskType: DiskType,
        ipv4: Int,
        monthly: String,
        annual: String,
        locationKeys: List<LocationKey>,
    ) = TariffSnapshot(
        externalId = externalId,
        name = name,
        vcpuCount = vcpu,
        ramMb = ramMb,
        diskGb = diskGb,
        diskType = diskType,
        ipv4Count = ipv4,
        virtualization = Virtualization.KVM,
        locationKeys = locationKeys,
        prices = listOf(
            PriceSnapshot(BillingPeriod.MONTHLY, BigDecimal(monthly), "USD"),
            PriceSnapshot(BillingPeriod.ANNUAL, BigDecimal(annual), "USD"),
        ),
    )

    private companion object {
        /** Any host: the page never leaves the process, [StubPageFetcher] answers for it. */
        const val BASE_URL = "http://fixture.invalid:8099"
        const val PAGE_URL = "$BASE_URL/vps"

        fun fixture(name: String): String =
            ExampleStaticAdapterTest::class.java.getResource("/crawler/$name")!!.readText()
    }
}
