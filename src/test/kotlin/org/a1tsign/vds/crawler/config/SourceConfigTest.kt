package org.a1tsign.vds.crawler.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The format's own half of the defence: a config file is **semi-trusted input**, so everything this
 * class refuses is something the interpreter would otherwise have to survive at crawl time -- or,
 * worse, would silently treat as "that section was not observed".
 */
class SourceConfigTest {

    @Test
    fun `a minimal file parses, and nothing is enabled or claimed complete by default`() {
        val parsed = assertIs<SourceConfigParse.Valid>(SourceConfigFormat.parse(FILE, baseline()))

        val config = parsed.config
        assertEquals(emptyList(), parsed.warnings)
        assertFalse(config.enabled, "a new file is inert until somebody turns it on")
        assertFalse(config.tariffListComplete, "claiming a complete catalog is always deliberate")
        assertFalse(config.acknowledgeSlugMismatch)
        assertEquals(SourceConfigFormat.SOURCE_KIND_HTML, config.sourceKind)
        assertEquals("https://acme.example/vps", config.pageUrl())
        assertEquals(1, config.tariffs!!.minRows, "a configured list must match at least one row")
        assertEquals(SourceConfigFormat.LOCATIONS_NOT_OBSERVED, config.tariffs.locationKeys)

        val turnedOn = baseline().replace(""""pagePath": "/vps",""", """"pagePath": "/vps", "enabled": true,""")
        assertTrue(
            assertIs<SourceConfigParse.Valid>(SourceConfigFormat.parse(FILE, turnedOn)).config.enabled,
            "a file that turns itself on is read as on",
        )
    }

    @Test
    fun `every malformed file is refused with a problem naming the offending key`() {
        val cases = listOf(
            // A typo in a key must never degrade into "that section was not observed".
            Case("an unknown key", """"pagePath": "/vps",""", """"pagePath": "/vps", "pagePth": "/x",""", "pagePth"),
            Case("a foreign schema version", """"schemaVersion": 1""", """"schemaVersion": 2""", "schemaVersion must be 1"),
            Case("an API source", """"pagePath": "/vps",""", """"pagePath": "/vps", "sourceKind": "API",""", "sourceKind must be HTML"),
            Case("an id outside the charset", """"id": "acme"""", """"id": "Acme Hosting"""", "id must match"),
            Case("a slug outside the charset", """"providerSlug": "acme-example"""", """"providerSlug": "ACME"""", "providerSlug must match"),
            Case("a slug of another site", """"providerSlug": "acme-example"""", """"providerSlug": "other-example"""", "is not the slug of"),
            Case("a relative baseUrl", """"baseUrl": "https://acme.example"""", """"baseUrl": "acme.example"""", "must be an absolute http"),
            Case("a baseUrl with a query", """"baseUrl": "https://acme.example"""", """"baseUrl": "https://acme.example?a=1"""", "no query"),
            Case("a page path that is not a path", """"pagePath": "/vps"""", """"pagePath": "vps"""", "pagePath must start with"),
            Case("more fraction digits than money has", """"maxFractionDigits": 4""", """"maxFractionDigits": 5""", "maxFractionDigits must be between 0 and 4"),
            Case("a separator that is not one character", """"maxFractionDigits": 4,""", """"maxFractionDigits": 4, "grouping": ",,",""", "grouping must be a single"),
            Case("a grouping separator equal to the decimal one", """"maxFractionDigits": 4,""", """"maxFractionDigits": 4, "grouping": ".",""", "must differ from the decimal"),
            Case("an enum constant that does not exist", """"values": {"NVMe SSD": "NVME"}""", """"values": {"NVMe SSD": "OPTANE"}""", "tariffs.diskType"),
            Case("a billing period that does not exist", """"billingPeriod": "MONTHLY"""", """"billingPeriod": "WEEKLY"""", "billingPeriod must be one of"),
            Case("a provider status that does not exist", """"status": "ACTIVE"""", """"status": "RETIRED"""", "provider.status must be one of"),
            Case("a location mode that does not exist", """"prices": [""", """"locationKeys": "SOME", "prices": [""", "tariffs.locationKeys must be one of"),
            // jsoup's :matches()/[attr~=] would compile a java.util.regex pattern out of the file.
            Case("a regex selector", """"selector": "td.month"""", """"selector": "td:matches(^[0-9]+${'$'})"""", ":matches"),
            Case("an attribute-regex selector", """"selector": "td.month"""", """"selector": "td[class~=month]"""", "~="),
            Case("two ways to read one value", """{"selector": "h1.name", "text": true}""", """{"selector": "h1.name", "text": true, "attr": "id"}""", "provider.name"),
            Case("an optional field the snapshot cannot do without", """{"selector": "td.name", "text": true}""", """{"selector": "td.name", "text": true, "required": false}""", "tariffs.name"),
            Case("two currency sources", """"currency": {"fixed": "EUR"}""", """"currency": {"fixed": "EUR", "bySymbol": {"${'$'}": "USD"}}""", "currency"),
            Case("a currency that is not an ISO code", """"currency": {"fixed": "EUR"}""", """"currency": {"fixed": "Euro"}""", "ISO-4217"),
        )

        cases.forEach { case ->
            val parsed = SourceConfigFormat.parse(FILE, case.content())
            val problems = assertIs<SourceConfigParse.Invalid>(parsed, case.description).problems
            assertTrue(
                problems.any { it.contains(case.expected) },
                "${case.description}: no problem says '${case.expected}': $problems",
            )
            problems.forEach { assertContains(it, FILE, false, "every problem names its file: $it") }
        }
    }

    /**
     * One deliberately broken field per iteration, across every key the format has: a validation
     * call forgotten for one section or one field would otherwise be invisible -- the file would
     * load and the mistake would surface as a failed crawl, or as a section quietly "not observed".
     */
    @Test
    fun `every section and every field of the format is really validated`() {
        assertIs<SourceConfigParse.Valid>(SourceConfigFormat.parse(FILE, fullConfig()), "the unbroken template is valid")

        val keys = listOf(
            "provider.rootSelector", "provider.name", "provider.websiteUrl", "provider.description",
            "legalInfo.rootSelector", "legalInfo.legalEntityName", "legalInfo.registrationCountry",
            "legalInfo.registrationNumber", "legalInfo.legalAddress",
            "paymentMethods.rootSelector", "paymentMethods.itemSelector", "paymentMethods.minRows",
            "paymentMethods.method",
            "locations.rootSelector", "locations.rowSelector", "locations.minRows", "locations.countryCode",
            "locations.city", "locations.datacenterName", "locations.region", "locations.latitude",
            "locations.longitude",
            "tariffs.rootSelector", "tariffs.rowSelector", "tariffs.minRows", "tariffs.name",
            "tariffs.vcpuCount", "tariffs.ramMb", "tariffs.diskGb", "tariffs.ipv4Count", "tariffs.diskType",
            "tariffs.virtualization", "tariffs.externalId", "tariffs.url", "tariffs.cpuModel",
            "tariffs.bandwidthMbps", "tariffs.trafficLimitGb", "tariffs.ipv6Supported", "tariffs.inStock",
            "tariffs.prices[0]",
        )

        keys.forEach { key ->
            val problems = assertIs<SourceConfigParse.Invalid>(SourceConfigFormat.parse(FILE, fullConfig(key)), key).problems
            assertTrue(problems.any { it.contains(key) }, "no problem names '$key': $problems")
        }
    }

    @Test
    fun `a slug that is not the baseUrl's own slug is accepted only when it is acknowledged`() {
        val mismatched = baseline().replace(""""providerSlug": "acme-example"""", """"providerSlug": "beta-example"""")

        assertIs<SourceConfigParse.Invalid>(SourceConfigFormat.parse(FILE, mismatched))

        val parsed = assertIs<SourceConfigParse.Valid>(SourceConfigFormat.parse(FILE, acknowledged(mismatched)))
        val warning = parsed.warnings.single()
        assertContains(warning, "beta-example")
        assertContains(warning, "acme-example", false, "the warning names the slug the baseUrl implies: $warning")
    }

    @Test
    fun `the slug length limit is exactly provider slug's 64 characters`() {
        fun problemsFor(length: Int): List<String> {
            val content = acknowledged(
                baseline().replace(
                    """"providerSlug": "acme-example"""",
                    """"providerSlug": "${"a".repeat(length)}"""",
                ),
            )
            return when (val parsed = SourceConfigFormat.parse(FILE, content)) {
                is SourceConfigParse.Valid -> emptyList()
                is SourceConfigParse.Invalid -> parsed.problems
            }
        }

        assertEquals(emptyList(), problemsFor(64), "64 characters is the column's full width")
        assertTrue(problemsFor(65).any { it.contains("at most 64 characters") }, "${problemsFor(65)}")
    }

    /**
     * The money bound (`NUMERIC(12, 4)`) is exercised through a file above; a coordinate's own
     * options are only reachable through a section the sample files do not carry, so they are
     * checked against [SourceConfigFormat.validate] -- the entry point the loader of task 4 calls.
     */
    @Test
    fun `a coordinate's own options are bounded in both directions`() {
        fun problems(latitude: DecimalSpec): List<String> =
            when (val parsed = SourceConfigFormat.validate(FILE, withLatitude(latitude))) {
                is SourceConfigParse.Valid -> emptyList()
                is SourceConfigParse.Invalid -> parsed.problems
            }

        val key = "locations.latitude"
        assertEquals(emptyList(), problems(DecimalSpec("td.lat", text = true)), "NUMERIC(9, 6) is six digits")
        assertEquals(
            emptyList(),
            problems(DecimalSpec("td.lat", text = true, maxFractionDigits = 0)),
            "a page that writes whole numbers only is legal",
        )
        listOf(7, -1).forEach { digits ->
            assertTrue(
                problems(DecimalSpec("td.lat", text = true, maxFractionDigits = digits)).any {
                    it.contains("$key.maxFractionDigits")
                },
                "$digits fraction digits must be refused",
            )
        }
        assertTrue(problems(DecimalSpec("td.lat", text = true, grouping = "..")).any { it.contains("$key.grouping") })
    }

    @Test
    fun `a file over the 64 KB limit is refused without being parsed, and the limit itself is not`() {
        val oversized = "{" + "a".repeat(SourceConfigFormat.MAX_FILE_BYTES)

        val problem = assertIs<SourceConfigParse.Invalid>(SourceConfigFormat.parse(FILE, oversized)).problems.single()
        assertContains(problem, "${SourceConfigFormat.MAX_FILE_BYTES} byte limit")
        assertFalse(problem.contains("aaaa"), "the refused content is never repeated: $problem")

        val atTheLimit = "{" + "a".repeat(SourceConfigFormat.MAX_FILE_BYTES - 1)
        val atTheLimitProblem = assertIs<SourceConfigParse.Invalid>(SourceConfigFormat.parse(FILE, atTheLimit)).problems.single()
        assertFalse(atTheLimitProblem.contains("byte limit"), "a file of exactly the limit is parsed: $atTheLimitProblem")
    }

    @Test
    fun `a problem repeats at most 40 characters of the offending value`() {
        val long = "x".repeat(200)
        val content = baseline().replace(""""id": "acme"""", """"id": "$long!"""")

        val problems = assertIs<SourceConfigParse.Invalid>(SourceConfigFormat.parse(FILE, content)).problems

        assertEquals(SourceConfigFormat.VALUE_IN_MESSAGE_LIMIT, 40)
        problems.forEach { problem ->
            assertFalse(
                problem.contains("x".repeat(SourceConfigFormat.VALUE_IN_MESSAGE_LIMIT + 1)),
                "a problem must not become a dumping ground for the file: $problem",
            )
        }
        assertTrue(problems.any { it.contains("x".repeat(SourceConfigFormat.VALUE_IN_MESSAGE_LIMIT)) }, "$problems")
    }

    private class Case(
        val description: String,
        private val original: String,
        private val replacement: String,
        val expected: String,
    ) {
        fun content(): String {
            val baseline = baseline()
            assertContains(baseline, original, false, "$description: the baseline no longer contains $original")
            return baseline.replace(original, replacement)
        }
    }

    private companion object {
        const val FILE = "acme.json"

        /** The one config shape a coordinate's options are reachable through. */
        fun withLatitude(latitude: DecimalSpec): SourceConfig = SourceConfig(
            schemaVersion = SourceConfigFormat.SCHEMA_VERSION,
            id = "acme",
            providerSlug = "acme-example",
            baseUrl = "https://acme.example",
            provider = ProviderSection(
                name = TextSpec("h1.name", text = true),
                websiteUrl = TextSpec("a.site", text = true),
            ),
            locations = LocationsSection(
                rootSelector = "table.locations",
                rowSelector = "tr.location",
                countryCode = TextSpec("td.country", text = true),
                latitude = latitude,
            ),
        )

        fun acknowledged(content: String): String =
            content.replace(""""pagePath": "/vps",""", """"pagePath": "/vps", "acknowledgeSlugMismatch": true,""")

        /**
         * A valid file every case above breaks in exactly one way. `providerSlug` is the slug of
         * `baseUrl`'s own domain, which is what makes a config unable to point at an unrelated row.
         */
        fun baseline(): String = """
            {
              "schemaVersion": 1,
              "id": "acme",
              "providerSlug": "acme-example",
              "baseUrl": "https://acme.example",
              "pagePath": "/vps",
              "provider": {
                "name": {"selector": "h1.name", "text": true},
                "websiteUrl": {"selector": "a.site", "attr": "href"},
                "status": "ACTIVE"
              },
              "tariffs": {
                "rootSelector": "table.plans",
                "rowSelector": "tr.plan",
                "name": {"selector": "td.name", "text": true},
                "vcpuCount": {"selector": "td.vcpu", "text": true},
                "ramMb": {"selector": "td.ram", "text": true},
                "diskGb": {"selector": "td.disk", "text": true},
                "ipv4Count": {"selector": "td.ipv4", "text": true},
                "diskType": {"selector": "tr.plan", "attr": "data-disk", "values": {"NVMe SSD": "NVME"}},
                "virtualization": {"selector": "tr.plan", "attr": "data-virt", "values": {"KVM": "KVM"}},
                "prices": [
                  {
                    "billingPeriod": "MONTHLY",
                    "selector": "td.month",
                    "text": true,
                    "maxFractionDigits": 4,
                    "currency": {"fixed": "EUR"}
                  }
                ]
              }
            }
        """.trimIndent()

        /**
         * Every key the format has, valid -- except [broken], which is given a second way to read
         * its value (a spec), a regex selector (a selector) or a negative row count (`minRows`).
         */
        fun fullConfig(broken: String? = null): String {
            fun spec(key: String, selector: String, extra: String = ""): String {
                val conflict = if (key == broken) """, "attr": "and-this"""" else ""
                return """{"selector": "$selector", "text": true$conflict$extra}"""
            }
            fun sel(key: String, selector: String): String = if (key == broken) "td:matches(^x)" else selector
            fun minRows(key: String): Int = if (key == broken) -1 else 1
            val disk = """, "values": {"NVMe SSD": "NVME"}"""
            val virt = """, "values": {"KVM": "KVM"}"""
            val method = """, "values": {"wire": "BANK_WIRE"}"""
            val flag = """, "values": {"yes": true}"""
            val price = """, "billingPeriod": "MONTHLY", "currency": {"fixed": "EUR"}"""
            return """
                {
                  "schemaVersion": 1,
                  "id": "acme",
                  "providerSlug": "acme-example",
                  "baseUrl": "https://acme.example",
                  "pagePath": "/vps",
                  "provider": {
                    "rootSelector": "${sel("provider.rootSelector", "body")}",
                    "name": ${spec("provider.name", "h1.name")},
                    "websiteUrl": ${spec("provider.websiteUrl", "a.site")},
                    "description": ${spec("provider.description", "p.about")},
                    "status": "ACTIVE"
                  },
                  "legalInfo": {
                    "rootSelector": "${sel("legalInfo.rootSelector", "section.company")}",
                    "legalEntityName": ${spec("legalInfo.legalEntityName", "dd.entity")},
                    "registrationCountry": ${spec("legalInfo.registrationCountry", "dd.country")},
                    "registrationNumber": ${spec("legalInfo.registrationNumber", "dd.registration")},
                    "legalAddress": ${spec("legalInfo.legalAddress", "dd.address")}
                  },
                  "paymentMethods": {
                    "rootSelector": "${sel("paymentMethods.rootSelector", "section.payments")}",
                    "itemSelector": "${sel("paymentMethods.itemSelector", "li.method")}",
                    "minRows": ${minRows("paymentMethods.minRows")},
                    "method": ${spec("paymentMethods.method", "li.method", method)}
                  },
                  "locations": {
                    "rootSelector": "${sel("locations.rootSelector", "table.locations")}",
                    "rowSelector": "${sel("locations.rowSelector", "tr.location")}",
                    "minRows": ${minRows("locations.minRows")},
                    "countryCode": ${spec("locations.countryCode", "td.country")},
                    "city": ${spec("locations.city", "td.city")},
                    "datacenterName": ${spec("locations.datacenterName", "td.dc")},
                    "region": ${spec("locations.region", "td.region")},
                    "latitude": ${spec("locations.latitude", "td.lat")},
                    "longitude": ${spec("locations.longitude", "td.lon")}
                  },
                  "tariffs": {
                    "rootSelector": "${sel("tariffs.rootSelector", "table.plans")}",
                    "rowSelector": "${sel("tariffs.rowSelector", "tr.plan")}",
                    "minRows": ${minRows("tariffs.minRows")},
                    "name": ${spec("tariffs.name", "td.name")},
                    "vcpuCount": ${spec("tariffs.vcpuCount", "td.vcpu")},
                    "ramMb": ${spec("tariffs.ramMb", "td.ram")},
                    "diskGb": ${spec("tariffs.diskGb", "td.disk")},
                    "ipv4Count": ${spec("tariffs.ipv4Count", "td.ipv4")},
                    "diskType": ${spec("tariffs.diskType", "td.disk-type", disk)},
                    "virtualization": ${spec("tariffs.virtualization", "td.virt", virt)},
                    "externalId": ${spec("tariffs.externalId", "td.id")},
                    "url": ${spec("tariffs.url", "a.plan")},
                    "cpuModel": ${spec("tariffs.cpuModel", "td.cpu")},
                    "bandwidthMbps": ${spec("tariffs.bandwidthMbps", "td.bandwidth")},
                    "trafficLimitGb": ${spec("tariffs.trafficLimitGb", "td.traffic")},
                    "ipv6Supported": ${spec("tariffs.ipv6Supported", "td.ipv6", flag)},
                    "inStock": ${spec("tariffs.inStock", "td.stock", flag)},
                    "locationKeys": "ALL_LOCATIONS",
                    "prices": [${spec("tariffs.prices[0]", "td.month", price)}]
                  }
                }
            """.trimIndent()
        }
    }
}
