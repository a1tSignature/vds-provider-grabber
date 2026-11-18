package org.a1tsign.vds.crawler.adapters

import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.crawler.AdapterEntry
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.AdapterSelection
import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.CrawlerRunner
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SnapshotValidator
import org.a1tsign.vds.crawler.config.SourceConfig
import org.a1tsign.vds.crawler.config.SourceConfigFormat
import org.a1tsign.vds.crawler.config.SourceConfigParse
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.PriceSnapshot
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.RecordingCrawlWriter
import org.a1tsign.vds.crawler.support.StubPageFetcher
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CatalogClientLimits
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.Reconciler
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.Virtualization
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compare declarative and handwritten snapshots, then check how missing and empty sections affect real catalog data. */
class DeclarativeAdapterTest : AbstractDbTest() {


    @Test
    fun `a declarative config reproduces the reference adapter's snapshot of the same page exactly`() {
        // The fixture retains the footprint page's selector markup with the filler removed.
        val page = resource("example-static.html")
        val parsed = assertIs<SourceConfigParse.Valid>(
            SourceConfigFormat.parse(SOURCE_FILE, resource(SOURCE_FILE)),
        )
        assertEquals(
            1,
            parsed.warnings.size,
            "the fixture's slug predates the format, so the file acknowledges the mismatch: ${parsed.warnings}",
        )
        val declarative = DeclarativeAdapter(parsed.config)

        val snapshot = fetch(declarative, page)

        assertEquals(
            runBlocking {
                ExampleStaticAdapter().fetch(context(page, mapOf(ExampleStaticAdapter.BASE_URL_CONFIG_KEY to BASE_URL)))
            },
            snapshot,
        )
        assertEquals(
            emptyList(),
            SnapshotValidator(clock).validate(declarative, snapshot),
            "the declarative snapshot is writable as it stands",
        )
    }


    @Test
    fun `an omitted section is not observed, while a configured one that matches nothing fails`() {
        val onlyTariffs = snapshot(acmeConfig(tariffsSection()), acmePage())

        assertNull(onlyTariffs.legalInfo, "an omitted section leaves that part of the catalog alone")
        assertNull(onlyTariffs.paymentMethods)
        assertNull(onlyTariffs.locations)
        assertNull(onlyTariffs.tariffs!!.single().locationKeys, "locationKeys defaults to not observed")

        val failure = assertFailsWith<AdapterFailureException> {
            snapshot(acmeConfig(PAYMENTS_SECTION), acmePage())
        }
        assertTrue(failure.message!!.contains("section.payments"), failure.message)
    }

    @Test
    fun `minRows 0 is the only way a configured list may be observed empty`() {
        val emptyPage = acmePage(planRows = "")

        val honest = snapshot(acmeConfig(tariffsSection(minRows = 0), tariffListComplete = true), emptyPage)
        assertEquals(emptyList(), honest.tariffs, "the explicit opt-in to 'this site lists none'")
        assertTrue(honest.tariffListComplete)

        val failure = assertFailsWith<AdapterFailureException> { snapshot(acmeConfig(tariffsSection()), emptyPage) }
        assertTrue(failure.message!!.contains("tariffs.minRows"), failure.message)

        // An observed empty location or payment set can clear catalog data without a tariff mass-change guard.
        val emptyLocations = assertFailsWith<AdapterFailureException> {
            snapshot(acmeConfig(tariffsSection() + LOCATIONS_SECTION), acmePage(extra = "<table class=\"locations\"></table>"))
        }
        assertTrue(emptyLocations.message!!.contains("locations.minRows"), emptyLocations.message)

        val emptyPayments = assertFailsWith<AdapterFailureException> {
            snapshot(acmeConfig(tariffsSection() + PAYMENTS_SECTION), acmePage(extra = "<section class=\"payments\"></section>"))
        }
        assertTrue(emptyPayments.message!!.contains("paymentMethods.minRows"), emptyPayments.message)
    }

    @Test
    fun `a required value that is missing or unreadable fails the source, an optional absent one is null`() {
        val cases = mapOf(
            "tariffs.name" to acmePage(planRows = planRow().replace("""<td class="name">VPS 1</td>""", "")),
            "tariffs.ramMb" to acmePage(planRows = planRow(ram = "plenty")),
            "tariffs.diskType" to acmePage(planRows = planRow().replace("""data-disk="NVMe SSD"""", """data-disk="Optane"""")),
            "tariffs.prices[0]" to acmePage(planRows = planRow(month = "ask us")),
        )

        cases.forEach { (key, page) ->
            val failure = assertFailsWith<AdapterFailureException>(key) { snapshot(acmeConfig(tariffsSection()), page) }
            assertTrue(failure.message!!.contains(key), "${failure.message} (expected $key)")
        }
        assertTrue(
            assertFailsWith<AdapterFailureException> {
                snapshot(acmeConfig(tariffsSection()), acmePage(planRows = planRow(ram = "plenty")))
            }.message!!.contains("plenty"),
            "the message has to name the value that broke, so an operator can fix the config",
        )

        val optional = snapshot(
            acmeConfig(tariffsSection(extra = ""","cpuModel": {"selector": "td.cpu", "text": true, "required": false}""")),
            acmePage(),
        )
        assertNull(optional.tariffs!!.single().cpuModel, "an optional field the page does not carry is 'not observed'")
    }

    @Test
    fun `every optional snapshot field the format offers is read when the page carries it`() {
        val snapshot = snapshot(
            acmeConfig(tariffsSection(extra = OPTIONAL_TARIFF_FIELDS) + LOCATIONS_SECTION),
            acmePage(planRows = RICH_PLAN_ROW, extra = LOCATIONS_MARKUP),
        )

        assertEquals(
            listOf(
                LocationSnapshot(
                    key = LocationKey("DE", "Berlin", "BER1"),
                    region = "Berlin",
                    latitude = BigDecimal("52.520008"),
                    longitude = BigDecimal("-13.404954"),
                ),
            ),
            snapshot.locations,
        )
        assertEquals(
            TariffSnapshot(
                externalId = "vps-1",
                name = "VPS 1",
                url = "/vps/1",
                vcpuCount = 1,
                cpuModel = "EPYC 7003",
                ramMb = 1024,
                diskGb = 20,
                diskType = DiskType.NVME,
                bandwidthMbps = 1000,
                trafficLimitGb = 20_000,
                ipv4Count = 1,
                ipv6Supported = true,
                virtualization = Virtualization.KVM,
                inStock = false,
                locationKeys = listOf(LocationKey("DE", "Berlin", "BER1")),
                prices = listOf(PriceSnapshot(BillingPeriod.MONTHLY, BigDecimal("9.99"), "USD")),
            ),
            snapshot.tariffs!!.single(),
        )
    }

    @Test
    fun `a negative amount is refused while a negative coordinate is not`() {
        assertFailsWith<AdapterFailureException>("a price below zero is never an observation") {
            price("-5.00", """{"fixed": "USD"}""")
        }
        // The southern/western hemisphere is not an error, which is why the two use different rules.
        assertEquals(
            BigDecimal("-13.404954"),
            snapshot(
                acmeConfig(tariffsSection() + LOCATIONS_SECTION),
                acmePage(extra = LOCATIONS_MARKUP),
            ).locations!!.single().longitude,
        )
    }

    @Test
    fun `a real-world plans page is read through units, suffixes and constants`() {
        val page = """
            <html><body><table><tbody>
              <tr><td>512 MiB</td><td>1 vCPU</td><td>500 GiB</td><td>10 GiB</td><td>$0.00595</td><td>$ 4.00 /mo</td></tr>
              <tr><td>2 GB</td><td>2 vCPUs</td><td>3 TB</td><td>1 TB NVMe</td><td>$0.02679</td><td>From $1,018.50/mo</td></tr>
            </tbody></table></body></html>
        """.trimIndent()
        val json = """
            {
              "schemaVersion": 1, "id": "real-example", "providerSlug": "real-example-test",
              "baseUrl": "https://real-example.test", "enabled": true,
              "provider": {"name": {"value": "Real Example"}, "websiteUrl": {"value": "https://real-example.test/"}},
              "tariffs": {
                "rootSelector": "table", "rowSelector": "tbody tr",
                "name": {"selector": "td:nth-child(1)", "text": true},
                "vcpuCount": {"selector": "td:nth-child(2)", "text": true},
                "ramMb": {"selector": "td:nth-child(1)", "text": true},
                "diskGb": {"selector": "td:nth-child(4)", "text": true},
                "trafficLimitGb": {"selector": "td:nth-child(3)", "text": true},
                "diskType": {"value": "NVME"},
                "ipv4Count": {"value": "1"},
                "virtualization": {"value": "KVM"},
                "ipv6Supported": {"value": "true"},
                "prices": [{
                  "billingPeriod": "MONTHLY", "selector": "td:nth-child(6)", "text": true,
                  "grouping": ",", "currency": {"bySymbol": {"${'$'}": "USD"}}
                }]
              }
            }
        """.trimIndent()

        val tariffs = snapshot(json, page).tariffs!!

        assertEquals(listOf(512, 2048), tariffs.map { it.ramMb }, "MiB is kept, GB becomes MB")
        assertEquals(listOf(1, 2), tariffs.map { it.vcpuCount })
        assertEquals(listOf(10, 1000), tariffs.map { it.diskGb }, "a decimal TB of disk is 1000 GB")
        assertEquals(listOf(500, 3000), tariffs.map { it.trafficLimitGb })
        assertEquals(listOf(DiskType.NVME, DiskType.NVME), tariffs.map { it.diskType })
        assertEquals(listOf(1, 1), tariffs.map { it.ipv4Count })
        assertEquals(listOf(true, true), tariffs.map { it.ipv6Supported })
        assertEquals(listOf(BigDecimal("4.00"), BigDecimal("1018.50")), tariffs.map { it.prices.single().amount })
        assertEquals("Real Example", snapshot(json, page).provider.name)
    }

    @Test
    fun `traffic size skips port speeds before the transfer allowance`() {
        val json = acmeConfig(tariffsSection(extra = """, "trafficLimitGb": {"selector": "td.traffic", "text": true}"""))
        mapOf("1Gbps - 2TB Bandwidth" to 2000, "10 Gbit/s · 3 TB Traffic" to 3000, "1 GB/s, 500 GB transfer" to 500).forEach { (cell, expected) ->
            val row = RICH_PLAN_ROW.replace("<td class=\"traffic\">20000</td>", "<td class=\"traffic\">$cell</td>")
            assertEquals(expected, snapshot(json, acmePage(planRows = row)).tariffs!!.single().trafficLimitGb, cell)
        }
    }

    @Test
    fun `a size with no unit is read in the config's unit, and a fractional result is refused`() {
        fun ram(cell: String, unit: String?): Int {
            val unitKey = unit?.let { ""","unit": "$it"""" } ?: ""
            val page = "<html><body><table><tbody><tr><td>$cell</td><td>$5</td></tr></tbody></table></body></html>"
            val json = """
                {
                  "schemaVersion": 1, "id": "u", "providerSlug": "u-test", "baseUrl": "https://u.test",
                  "provider": {"name": {"value": "U"}, "websiteUrl": {"value": "https://u.test/"}},
                  "tariffs": {
                    "rootSelector": "table", "rowSelector": "tr",
                    "name": {"value": "plan"}, "vcpuCount": {"value": "1"}, "diskGb": {"value": "10"},
                    "ramMb": {"selector": "td:nth-child(1)", "text": true$unitKey},
                    "diskType": {"value": "SSD"}, "ipv4Count": {"value": "1"}, "virtualization": {"value": "KVM"}
                  }
                }
            """.trimIndent()
            return snapshot(json, page).tariffs!!.single().ramMb
        }

        assertEquals(2048, ram("2048", null), "no unit anywhere: the field's own unit")
        assertEquals(4096, ram("4", "GB"))
        assertEquals(512, ram("0.5 GB", null))
        assertEquals(8192, ram("8GB RAM", "MB"), "the unit on the page wins over the config's")
        assertFailsWith<AdapterFailureException> { ram("0.3 GB", null) }
    }

    @Test
    fun `a constant may not be combined with a selector, and must fit its field`() {
        fun problems(field: String): List<String> {
            val json = """
                {
                  "schemaVersion": 1, "id": "c", "providerSlug": "c-test", "baseUrl": "https://c.test",
                  "provider": {"name": {"value": "C"}, "websiteUrl": {"value": "https://c.test/"}},
                  "tariffs": {
                    "rootSelector": "table", "rowSelector": "tr",
                    "name": {"value": "plan"}, "vcpuCount": {"value": "1"}, "diskGb": {"value": "10"},
                    "ramMb": {"value": "1024"}, "diskType": {"value": "SSD"}, "ipv4Count": {"value": "1"},
                    "virtualization": {"value": "KVM"}, $field
                  }
                }
            """.trimIndent()
            return when (val parsed = SourceConfigFormat.parse("c.json", json)) {
                is SourceConfigParse.Valid -> emptyList()
                is SourceConfigParse.Invalid -> parsed.problems
            }
        }

        assertEquals(emptyList(), problems(""""cpuModel": {"value": "EPYC"}"""))
        assertTrue(problems(""""cpuModel": {"value": "EPYC", "selector": "td", "text": true}""").single().contains("not both"))
        assertTrue(problems(""""bandwidthMbps": {"value": "fast"}""").single().contains("whole number"))
        assertTrue(problems(""""bandwidthMbps": {"selector": "td", "text": true, "unit": "GB"}""").single().contains("only allowed"))
        assertTrue(problems(""""trafficLimitGb": {"selector": "td", "text": true, "unit": "KB"}""").single().contains("must be one of"))
        assertTrue(
            problems(""""prices": [{"billingPeriod": "MONTHLY", "value": "5", "currency": {"fixed": "USD"}}]""")
                .single().contains("'selector' is required"),
        )
    }


    @Test
    fun `an omitted section issues no replace-the-whole-set write, while a configured one does`() {
        withCatalog { ctx ->
            val provider = CatalogFixture(PostgresSupport.database).createProvider(ACME_SLUG, clock.instant())
            CatalogFixture(PostgresSupport.database)
                .createPaymentMethods(provider.id, listOf(PaymentMethod.VISA_MASTERCARD to null))

            val silent = RecordingCrawlWriter()
            ctx.reconcile(snapshot(acmeConfig(tariffsSection()), acmePage()), silent)
            assertTrue(
                silent.mutations().none { it is RecordingCrawlWriter.Mutation.PutPaymentMethods },
                "an omitted section must never replace the catalog's set: ${silent.kinds()}",
            )
            assertEquals(
                listOf(PaymentMethod.VISA_MASTERCARD.name),
                ctx.client.getProvider(provider.id).paymentMethods.map { it.method },
            )

            val observed = RecordingCrawlWriter()
            ctx.reconcile(
                snapshot(acmeConfig(tariffsSection() + PAYMENTS_SECTION), acmePage(extra = PAYMENTS_MARKUP)),
                observed,
            )
            assertTrue(
                observed.mutations().any { it is RecordingCrawlWriter.Mutation.PutPaymentMethods },
                "a configured section is an observation that does replace the set: ${observed.kinds()}",
            )
        }
    }

    @Test
    fun `a root selector that matches nothing fails the adapter with zero writes`() {
        withCatalog { ctx ->
            CatalogFixture(PostgresSupport.database).createProvider(ACME_SLUG, clock.instant())
            val recorder = RecordingCrawlWriter()
            val adapter = DeclarativeAdapter(config(acmeConfig(tariffsSection())))

            val summary = ctx.run(adapter, acmePage(planRows = "").replace("""<table class="plans">""", "<table>"), recorder)

            val result = summary.adapters.single()
            assertEquals(AdapterRunStatus.FAILED, result.status)
            assertEquals(FailureReason.ADAPTER_ERROR, result.failureReason)
            assertEquals(emptyList(), recorder.mutations(), "a broken selector writes nothing at all")
            assertTrue(result.message!!.contains("table.plans"), result.message)
        }
    }

    @Test
    fun `a complete snapshot with no tariffs is refused by the mass-change guard, byte-identically`() {
        withCatalog { ctx ->
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider(ACME_SLUG, clock.instant())
            (1..4).forEach { fixture.createTariff(provider.id, "VPS $it", clock.instant()) }
            val before = ctx.dump(provider.id)
            val recorder = RecordingCrawlWriter()

            // The regression that matters most: a config that claims a complete catalog and whose
            // row selector stopped matching must not flip every tariff out of stock.
            val wipeAttempt = snapshot(
                acmeConfig(tariffsSection(minRows = 0), tariffListComplete = true),
                acmePage(planRows = ""),
            )
            val outcome = ctx.reconcile(wipeAttempt, recorder)

            assertEquals(AdapterRunStatus.FAILED, outcome.status)
            assertEquals(FailureReason.MASS_CHANGE_GUARD, outcome.failureReason)
            assertEquals(emptyList(), recorder.mutations(), "the guard performs zero writes")
            assertEquals(before, ctx.dump(provider.id), "the catalog is byte-identical afterwards")
        }
    }


    @Test
    fun `money is read with the config's own separator and currency options, exactly`() {
        assertEquals(
            PriceSnapshot(BillingPeriod.MONTHLY, BigDecimal("1234.5678"), "USD"),
            price("${DOLLAR}1,234.5678", """{"bySymbol": {"$DOLLAR": "USD"}}""", grouping = ","),
            "the exact BigDecimal, never a Double",
        )
        assertEquals(
            PriceSnapshot(BillingPeriod.MONTHLY, BigDecimal("1234.56"), "EUR"),
            price("1.234,56 €", """{"bySymbol": {"€": "EUR"}}""", grouping = ".", decimal = ","),
            "a page that writes its numbers the European way",
        )
        assertEquals(
            PriceSnapshot(BillingPeriod.MONTHLY, BigDecimal("12.00"), "USD"),
            price("12.00", """{"fixed": "USD"}"""),
            "a fixed currency needs no symbol on the page",
        )

        val refused = mapOf(
            "a fifth decimal the catalog cannot store" to "${DOLLAR}1.23456",
            "a symbol the config does not map" to "¤9.99",
            "no number at all" to "ask us",
        )
        refused.forEach { (description, cell) ->
            assertFailsWith<AdapterFailureException>(description) {
                price(cell, """{"bySymbol": {"$DOLLAR": "USD"}}""")
            }
        }
    }

    @Test
    fun `undeclared money separators fail instead of truncating the price`() {
        val rupees = """{"bySymbol": {"₹": "INR"}}"""
        listOf("₹3,299.00", "3,299.00 ₹", "₹3 . 99").forEach { cell ->
            assertFailsWith<AdapterFailureException>(cell) { price(cell, rupees) }
        }
        assertFailsWith<AdapterFailureException> {
            price("3,299.00", """{"fixed": "INR"}""")
        }
        assertEquals(BigDecimal("3299.00"), price("₹3,299.00", rupees, grouping = ",").amount)
        assertEquals(BigDecimal("3.99"), price("₹3 . 99", rupees, grouping = " ").amount)
    }

    @Test
    fun `a struck-out former price is ignored unless the config asks for the regular price`() {
        val usd = """{"bySymbol": {"$DOLLAR": "USD"}}"""
        assertEquals(BigDecimal("8"), price("<del>${DOLLAR}12</del> ${DOLLAR}8 /mo", usd).amount)
        assertEquals(BigDecimal("12"), price("<del>${DOLLAR}12</del> ${DOLLAR}8 /mo", usd, regularPrice = true).amount)
        assertEquals(
            BigDecimal("12.99"),
            price("""<div class="old-price"><span class="x-strike">$DOLLAR</span><span class="x-strike">12.99</span></div> ${DOLLAR}1.99""", usd, regularPrice = true).amount,
            "struck parts are read together, so a symbol in its own span still counts",
        )
        assertEquals(BigDecimal("8"), price("${DOLLAR}8 /mo", usd).amount, "nothing struck: the shown price")
        assertEquals(
            BigDecimal("44"),
            price("""<div><p class="now">${DOLLAR}20 / year</p><p><span class="line-through">${DOLLAR}44</span> with a code</p></div>""", usd, regularPrice = true, selector = "p.now").amount,
            "a struck amount beside the selected price counts too",
        )
        assertEquals(BigDecimal("1.99"), price("""<span class="price-old">${DOLLAR}12.99</span> ${DOLLAR}1.99""", usd).amount)
    }

    @Test
    fun `the number next to the currency symbol is the amount, ahead of a discount percentage`() {
        val eur = """{"bySymbol": {"€": "EUR"}}"""
        assertEquals(BigDecimal("6.00"), price("6.00 € \u221237%", eur).amount)
        assertEquals(BigDecimal("5.00"), price("Save 20% - ${DOLLAR}5.00/mo", """{"bySymbol": {"$DOLLAR": "USD"}}""").amount)
        assertEquals(BigDecimal("2094"), price("2 094 ₽", """{"bySymbol": {"₽": "RUB"}}""", grouping = " ").amount)
    }

    @Test
    fun `multiplied sizes are summed and enum and flag values match by the longest contained key`() {
        val page = """
            <html><body><table><tbody>
              <tr><td>vds.ryzen-16</td><td>200 GB NVMe</td><td>Buy now</td></tr>
              <tr><td>vds.v1-max</td><td>2 x 960 GB SSD</td><td>Pre-Order</td></tr>
            </tbody></table></body></html>
        """.trimIndent()
        val json = """
            {
              "schemaVersion": 1, "id": "multi", "providerSlug": "multi-test", "baseUrl": "https://multi.test",
              "provider": {"name": {"value": "Multi"}, "websiteUrl": {"value": "https://multi.test/"}},
              "tariffs": {
                "rootSelector": "table", "rowSelector": "tbody tr",
                "name": {"selector": "td:nth-child(1)", "text": true},
                "vcpuCount": {"value": "4"}, "ramMb": {"value": "16384"}, "ipv4Count": {"value": "1"},
                "diskGb": {"selector": "td:nth-child(2)", "text": true},
                "diskType": {"selector": "td:nth-child(2)", "text": true, "values": {"NVMe": "NVME", "SSD": "SSD", "HDD": "HDD"}},
                "inStock": {"selector": "td:nth-child(3)", "text": true, "values": {"Pre-Order": false}, "default": true},
                "virtualization": {"value": "KVM"}
              }
            }
        """.trimIndent()

        val tariffs = snapshot(json, page).tariffs!!

        assertEquals(listOf(200, 1920), tariffs.map { it.diskGb })
        assertEquals(listOf(DiskType.NVME, DiskType.SSD), tariffs.map { it.diskType })
        assertEquals(listOf(true, false), tariffs.map { it.inStock })
    }

    @Test
    fun `colliding tariff names are prefixed with the spec that tells them apart, only when asked`() {
        val rows = planRow(ram = "16384") + planRow(ram = "16384").replace("<td class=\"disk\">", "<td class=\"disk\">1")
        val page = acmePage(planRows = rows)

        val plain = snapshot(acmeConfig(tariffsSection()), page).tariffs!!
        assertEquals(listOf("VPS 1", "VPS 1"), plain.map { it.name }, "off by default: the validator refuses the pair")

        val prefixed = snapshot(acmeConfig(tariffsSection(extra = ", \"disambiguateNames\": true")), page).tariffs!!
        assertEquals(
            listOf("${plain[0].diskGb} GB disk · VPS 1", "${plain[1].diskGb} GB disk · VPS 1"),
            prefixed.map { it.name },
        )
    }


    private fun price(
        cell: String,
        currency: String,
        grouping: String? = null,
        decimal: String = ".",
        regularPrice: Boolean = false,
        selector: String = "td.month",
    ): PriceSnapshot {
        val grouped = grouping?.let { """"grouping": "$it", """ } ?: ""
        val spec = """
            {
              "billingPeriod": "MONTHLY", "selector": "$selector", "text": true, "regularPrice": $regularPrice,
              $grouped"decimal": "$decimal", "currency": $currency
            }
        """.trimIndent()
        return snapshot(acmeConfig(tariffsSection(prices = spec)), acmePage(planRows = planRow(month = cell)))
            .tariffs!!.single().prices.single()
    }

    private fun config(json: String): SourceConfig =
        assertIs<SourceConfigParse.Valid>(SourceConfigFormat.parse("acme.json", json)).config

    private fun snapshot(json: String, page: String): ProviderSnapshot = fetch(DeclarativeAdapter(config(json)), page)

    private fun fetch(adapter: ProviderAdapter, page: String): ProviderSnapshot =
        runBlocking { adapter.fetch(context(page)) }

    private fun context(page: String, config: Map<String, String> = emptyMap()) =
        CrawlContext(StubPageFetcher(page), clock, UUID.randomUUID(), config)

    private class Ctx(
        private val harness: CrawlerAppHarness,
        val client: CatalogApiClient,
        private val clock: java.time.Clock,
    ) {
        private val reconciler = Reconciler(client)

        suspend fun reconcile(snapshot: ProviderSnapshot, writer: CrawlWriter) =
            reconciler.reconcile(DeclarativeAdapter(ACME_CONFIG), snapshot, writer, RATIO)

        /** The whole provider as the public API renders it -- the handle on "nothing changed". */
        suspend fun dump(providerId: Long): String {
            val tariffs = client.listAllTariffs(providerId)
            val history = transaction(PostgresSupport.database) {
                tariffs.map { PriceRepository().history(it.id, null, null, null).toString() }
            }
            return listOf(client.getProvider(providerId).toString(), tariffs.toString(), history.toString()).joinToString("\n")
        }

        suspend fun run(adapter: ProviderAdapter, page: String, writer: CrawlWriter) = CrawlerRunner(
            config = harness.crawlerConfig(),
            registry = AdapterRegistry(listOf(AdapterEntry(adapter, includedByDefault = true))),
            fetcher = StubPageFetcher(page),
            validator = SnapshotValidator(clock),
            reconciler = reconciler,
            clock = clock,
            writerFactory = { writer },
            adapterTimeoutMs = 20_000,
            runTimeoutMs = 60_000,
        ).runOnce(AdapterSelection(), dryRun = false)
    }

    private fun withCatalog(block: suspend (Ctx) -> Unit) = CrawlerAppHarness(clock).use { harness ->
        CatalogApiClient(
            config = harness.crawlerConfig(),
            clock = clock,
            limits = FAST_LIMITS,
            backoffJitter = { 0L },
            sleep = { },
        ).use { client ->
            runBlocking { block(Ctx(harness, client, clock)) }
        }
    }

    private companion object {
        const val SOURCE_FILE = "example-static-source.json"

        /** The base URL both adapters are pointed at, so their `sourceUrls` can be compared. */
        const val BASE_URL = "https://example-static.test"

        const val ACME_SLUG = "acme-example"

        const val DOLLAR = "$"

        val RATIO: BigDecimal = BigDecimal("0.5")

        val FAST_LIMITS = CatalogClientLimits(
            connectTimeoutMs = 1_000,
            requestTimeoutMs = 5_000,
            socketTimeoutMs = 5_000,
        )

        val PAYMENTS_SECTION = """
            , "paymentMethods": {
                "rootSelector": "section.payments",
                "itemSelector": "li.method",
                "method": {"selector": "li.method", "attr": "data-method", "values": {"wire": "BANK_WIRE"}}
              }
        """.trimIndent()

        val PAYMENTS_MARKUP = """<section class="payments"><li class="method" data-method="wire">Wire</li></section>"""

        val LOCATIONS_SECTION = """
            , "locations": {
                "rootSelector": "table.locations",
                "rowSelector": "tr.location",
                "countryCode": {"selector": "td.country", "text": true},
                "city": {"selector": "td.city", "text": true},
                "datacenterName": {"selector": "td.dc", "text": true},
                "region": {"selector": "td.region", "text": true},
                "latitude": {"selector": "td.lat", "text": true},
                "longitude": {"selector": "td.lon", "text": true}
              }
        """.trimIndent()

        val LOCATIONS_MARKUP = """
            <table class="locations"><tr class="location">
              <td class="country">DE</td><td class="city">Berlin</td><td class="dc">BER1</td>
              <td class="region">Berlin</td><td class="lat">52.520008</td><td class="lon">-13.404954</td>
            </tr></table>
        """.trimIndent()

        val OPTIONAL_TARIFF_FIELDS = """
            , "externalId": {"selector": "td.id", "text": true}
            , "url": {"selector": "a.plan", "attr": "href"}
            , "cpuModel": {"selector": "td.cpu", "text": true}
            , "bandwidthMbps": {"selector": "td.bandwidth", "text": true}
            , "trafficLimitGb": {"selector": "td.traffic", "text": true}
            , "ipv6Supported": {"selector": "td.ipv6", "text": true, "values": {"yes": true, "no": false}}
            , "inStock": {"selector": "td.stock", "text": true, "values": {"yes": true, "no": false}}
            , "locationKeys": "ALL_LOCATIONS"
        """.trimIndent()

        val RICH_PLAN_ROW = """
            <tr class="plan" data-disk="NVMe SSD" data-virt="KVM">
              <td class="name">VPS 1</td><td class="vcpu">1</td><td class="ram">1024</td>
              <td class="disk">20</td><td class="ipv4">1</td><td class="month">9.99</td>
              <td class="id">vps-1</td><td><a class="plan" href="/vps/1">details</a></td>
              <td class="cpu">EPYC 7003</td><td class="bandwidth">1000</td><td class="traffic">20000</td>
              <td class="ipv6">yes</td><td class="stock">no</td>
            </tr>
        """.trimIndent()

        fun resource(name: String): String =
            DeclarativeAdapterTest::class.java.getResource("/crawler/$name")!!.readText()

        fun acmeConfig(sections: String = "", tariffListComplete: Boolean = false): String = """
            {
              "schemaVersion": 1,
              "id": "acme",
              "providerSlug": "$ACME_SLUG",
              "baseUrl": "https://acme.example",
              "pagePath": "/vps",
              "tariffListComplete": $tariffListComplete,
              "provider": {
                "name": {"selector": "h1.name", "text": true},
                "websiteUrl": {"selector": "a.site", "attr": "href"}
              }$sections
            }
        """.trimIndent()

        /** USD through a fixed currency, so no FX rate is needed by the catalog tests. */
        fun tariffsSection(
            minRows: Int = 1,
            prices: String = """{"billingPeriod": "MONTHLY", "selector": "td.month", "text": true, "currency": {"fixed": "USD"}}""",
            extra: String = "",
        ): String = """
            , "tariffs": {
                "rootSelector": "table.plans",
                "rowSelector": "tr.plan",
                "minRows": $minRows,
                "name": {"selector": "td.name", "text": true},
                "vcpuCount": {"selector": "td.vcpu", "text": true},
                "ramMb": {"selector": "td.ram", "text": true},
                "diskGb": {"selector": "td.disk", "text": true},
                "ipv4Count": {"selector": "td.ipv4", "text": true},
                "diskType": {"selector": "tr.plan", "attr": "data-disk", "values": {"NVMe SSD": "NVME"}},
                "virtualization": {"selector": "tr.plan", "attr": "data-virt", "values": {"KVM": "KVM"}},
                "prices": [$prices]$extra
              }
        """.trimIndent()

        fun planRow(month: String = "9.99", ram: String = "1024"): String = """
            <tr class="plan" data-disk="NVMe SSD" data-virt="KVM">
              <td class="name">VPS 1</td><td class="vcpu">1</td><td class="ram">$ram</td>
              <td class="disk">20</td><td class="ipv4">1</td><td class="month">$month</td>
            </tr>
        """.trimIndent()

        fun acmePage(planRows: String = planRow(), extra: String = ""): String = """
            <html><body>
            <h1 class="name">Acme Hosting</h1>
            <a class="site" href="https://acme.example/">acme.example</a>
            <table class="plans">$planRows</table>
            $extra
            </body></html>
        """.trimIndent()

        val ACME_CONFIG = SourceConfig(
            schemaVersion = SourceConfigFormat.SCHEMA_VERSION,
            id = "acme",
            providerSlug = ACME_SLUG,
            baseUrl = "https://acme.example",
        )
    }
}
