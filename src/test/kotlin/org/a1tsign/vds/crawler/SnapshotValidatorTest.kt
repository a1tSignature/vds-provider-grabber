package org.a1tsign.vds.crawler

import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.support.AMSTERDAM
import org.a1tsign.vds.crawler.support.FIXTURE_NOW
import org.a1tsign.vds.crawler.support.FRANKFURT
import org.a1tsign.vds.crawler.support.fixtureAdapter
import org.a1tsign.vds.crawler.support.fixtureClock
import org.a1tsign.vds.crawler.support.validSnapshot
import org.a1tsign.vds.crawler.support.withLocation
import org.a1tsign.vds.crawler.support.withPrice
import org.a1tsign.vds.crawler.support.withTariff
import org.a1tsign.vds.crawler.support.withoutLocationLinks
import org.a1tsign.vds.domain.BillingPeriod
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every rule is exercised as a single mutation of the valid fixture asserted with
 * [assertOnlyProblem], which pins three things at once: a problem is reported, its `path` names the
 * offending element, and the valid neighbours in the same collection report nothing -- so a rule
 * cannot pass by rejecting everything. Deleting any rule from the validator turns one of these
 * cases red.
 */
class SnapshotValidatorTest {

    @Test
    fun `the valid fixture is writable`() {
        assertNoProblems(validSnapshot())
    }

    @Test
    fun `a not observed section is not a problem`() {
        assertNoProblems(validSnapshot().copy(legalInfo = null))
        assertNoProblems(validSnapshot().copy(paymentMethods = null))
        // tariffListComplete has to come along: "the full catalog" + "no catalog observed" is the
        // contradiction asserted below, not a "not observed" section.
        assertNoProblems(validSnapshot().copy(tariffs = null, tariffListComplete = false))
        // locationKeys go with the locations: an adapter that did not observe locations cannot
        // claim tariff-to-location links either (rule 11 covers the inconsistent combination).
        assertNoProblems(validSnapshot().withoutLocationLinks().copy(locations = null))
    }

    @Test
    fun `an observed empty section is not a problem either`() {
        assertNoProblems(validSnapshot().copy(paymentMethods = emptySet()))
        assertNoProblems(validSnapshot().copy(tariffs = emptyList()))
        assertNoProblems(validSnapshot().withoutLocationLinks().copy(locations = emptyList()))
        assertNoProblems(validSnapshot().withTariff(0) { it.copy(prices = emptyList()) })
    }

    @Test
    fun `the provider slug must be exactly the slug the adapter owns`() {
        val renamed = validSnapshot().copy(provider = validSnapshot().provider.copy(slug = "acme-2"))

        val found = SnapshotValidator(fixtureClock()).validate(fixtureAdapter("acme"), renamed)

        assertEquals(listOf("provider.slug"), found.map { it.path }, "problems: $found")
    }

    @Test
    fun `a malformed provider slug is a problem even when the adapter agrees with it`() {
        val malformed = validSnapshot().copy(provider = validSnapshot().provider.copy(slug = "Acme"))

        val found = SnapshotValidator(fixtureClock()).validate(fixtureAdapter("Acme"), malformed)

        assertEquals(listOf("provider.slug"), found.map { it.path }, "problems: $found")
    }

    /**
     * A value exactly at a vds-001 limit must be **accepted** -- an off-by-one here would reject
     * rows the catalog happily stores. Asserted as one snapshot that sits on every boundary at
     * once: if any single comparison is wrong, the reported path names it.
     */
    @Test
    fun `values exactly at the vds-001 limits are accepted`() {
        val slug = "a".repeat(64)
        val onLimits = validSnapshot()
            .copy(
                provider = validSnapshot().provider.copy(
                    slug = slug,
                    name = "n".repeat(255),
                    websiteUrl = "https://acme.example/" + "a".repeat(2048 - "https://acme.example/".length),
                ),
            )
            .withLocation(0) { it.copy(latitude = BigDecimal("-90"), longitude = BigDecimal("180")) }
            .withTariff(0) { tariff ->
                tariff.copy(
                    name = "t".repeat(255),
                    externalId = "e".repeat(128),
                    vcpuCount = Short.MAX_VALUE.toInt(),
                    ipv4Count = 0,
                    ramMb = 1,
                    diskGb = 1,
                    bandwidthMbps = 1,
                    trafficLimitGb = 1,
                ).withPrice(0) { it.copy(amount = BigDecimal("99999999.9999")) }
                    .withPrice(1) { it.copy(amount = BigDecimal("0.0000")) }
            }

        val found = SnapshotValidator(fixtureClock()).validate(fixtureAdapter(slug), onLimits)

        assertEquals(emptyList(), found.map { it.path })
    }

    @Test
    fun `each single broken field is reported at its own path`() {
        val cases = listOf(
            // rule 2: strings and URLs
            "provider.name" to mutate { copy(provider = provider.copy(name = "  ")) },
            "provider.name" to mutate { copy(provider = provider.copy(name = "x".repeat(256))) },
            "provider.websiteUrl" to mutate { copy(provider = provider.copy(websiteUrl = "ftp://acme.example")) },
            "provider.websiteUrl" to mutate { copy(provider = provider.copy(websiteUrl = "http://")) },
            "provider.websiteUrl" to mutate { copy(provider = provider.copy(websiteUrl = "/vps")) },
            "provider.websiteUrl" to mutate {
                copy(provider = provider.copy(websiteUrl = "https://acme.example/" + "a".repeat(2048)))
            },
            "tariffs[1].url" to mutate { withTariff(1) { it.copy(url = "javascript:alert(1)") } },
            "legalInfo.sourceUrl" to mutate { copy(legalInfo = legalInfo!!.copy(sourceUrl = "/imprint")) },
            "sourceUrls[0]" to mutate { copy(sourceUrls = listOf("acme.example/vps", "https://acme.example/ok")) },
            // rule 3: an adapter must say where its data came from
            "sourceUrls" to mutate { copy(sourceUrls = emptyList()) },
            // rule 4: country codes
            "locations[0].key.countryCode" to mutate {
                withLocation(0) { it.copy(key = it.key.copy(countryCode = "de")) }
            },
            "legalInfo.registrationCountry" to mutate {
                copy(legalInfo = legalInfo!!.copy(registrationCountry = "DEU"))
            },
            // rule 5: sizes, including the SMALLINT ceilings
            "tariffs[0].vcpuCount" to mutate { withTariff(0) { it.copy(vcpuCount = 0) } },
            "tariffs[0].vcpuCount" to mutate { withTariff(0) { it.copy(vcpuCount = 40_000) } },
            "tariffs[1].ramMb" to mutate { withTariff(1) { it.copy(ramMb = 0) } },
            "tariffs[1].diskGb" to mutate { withTariff(1) { it.copy(diskGb = 0) } },
            "tariffs[0].ipv4Count" to mutate { withTariff(0) { it.copy(ipv4Count = -1) } },
            "tariffs[0].ipv4Count" to mutate { withTariff(0) { it.copy(ipv4Count = 40_000) } },
            "tariffs[1].bandwidthMbps" to mutate { withTariff(1) { it.copy(bandwidthMbps = 0) } },
            "tariffs[1].trafficLimitGb" to mutate { withTariff(1) { it.copy(trafficLimitGb = 0) } },
            // rule 6: tariff identity strings
            "tariffs[0].name" to mutate { withTariff(0) { it.copy(name = " ") } },
            "tariffs[0].name" to mutate { withTariff(0) { it.copy(name = "x".repeat(256)) } },
            "tariffs[1].externalId" to mutate { withTariff(1) { it.copy(externalId = "") } },
            "tariffs[1].externalId" to mutate { withTariff(1) { it.copy(externalId = "x".repeat(129)) } },
            // rule 7: money
            "tariffs[0].prices[0].currency" to mutate {
                withTariff(0) { t -> t.withPrice(0) { it.copy(currency = "eur") } }
            },
            "tariffs[1].prices[1].amount" to mutate {
                withTariff(1) { t -> t.withPrice(1) { it.copy(amount = BigDecimal("-1.0000")) } }
            },
            "tariffs[1].prices[1].amount" to mutate {
                withTariff(1) { t -> t.withPrice(1) { it.copy(amount = BigDecimal("1.00005")) } }
            },
            "tariffs[1].prices[1].amount" to mutate {
                withTariff(1) { t -> t.withPrice(1) { it.copy(amount = BigDecimal("123456789.0000")) } }
            },
            // rule 8: coordinates
            "locations[0].latitude" to mutate { withLocation(0) { it.copy(latitude = BigDecimal("90.000001")) } },
            "locations[0].longitude" to mutate {
                withLocation(0) { it.copy(longitude = BigDecimal("-180.000001")) }
            },
            "locations[0].latitude" to mutate { withLocation(0) { it.copy(latitude = BigDecimal("50.1234567")) } },
            "locations[0].latitude" to mutate { withLocation(0) { it.copy(latitude = null) } },
            "locations[0].longitude" to mutate { withLocation(0) { it.copy(longitude = null) } },
        )

        cases.forEach { (path, snapshot) -> assertOnlyProblem(path, snapshot) }
    }

    @Test
    fun `two tariffs may not share a name ignoring case`() {
        assertOnlyProblem("tariffs[1].name", validSnapshot().withTariff(1) { it.copy(name = "vps 1") })
    }

    @Test
    fun `two tariffs may not share an external id, but may both have none`() {
        assertOnlyProblem("tariffs[1].externalId", validSnapshot().withTariff(1) { it.copy(externalId = "vps-1") })

        assertNoProblems(
            validSnapshot()
                .withTariff(0) { it.copy(externalId = null) }
                .withTariff(1) { it.copy(externalId = null) },
        )
    }

    @Test
    fun `two locations may not share a normalised key`() {
        val duplicated = validSnapshot().withLocation(1) {
            it.copy(key = LocationKey("DE", city = "frankfurt", datacenterName = "fra-1"))
        }

        // The tariffs pointed at locations[1], which no longer exists under its old key.
        assertOnlyProblem("locations[1].key", duplicated.withoutLocationLinks())
    }

    @Test
    fun `two prices of one tariff may not share a billing period`() {
        val duplicated = validSnapshot().withTariff(0) { tariff ->
            tariff.withPrice(1) { it.copy(billingPeriod = BillingPeriod.MONTHLY) }
        }

        assertOnlyProblem("tariffs[0].prices[1].billingPeriod", duplicated)
    }

    @Test
    fun `a tariff location key must match an observed location`() {
        val unknown = validSnapshot().withTariff(1) {
            it.copy(locationKeys = listOf(AMSTERDAM, LocationKey("FR", city = "Paris")))
        }
        assertOnlyProblem("tariffs[1].locationKeys[1]", unknown)

        val otherCase = validSnapshot().withTariff(1) {
            it.copy(locationKeys = listOf(FRANKFURT.copy(city = "FRANKFURT", datacenterName = "fra-1")))
        }
        assertNoProblems(otherCase)
    }

    @Test
    fun `location keys while locations are not observed are a problem`() {
        assertOnlyProblem("tariffs[0].locationKeys", validSnapshot().copy(locations = null).withTariff(1) {
            it.copy(locationKeys = null)
        })
    }

    @Test
    fun `a complete tariff list while tariffs are not observed is a problem`() {
        assertOnlyProblem("tariffListComplete", validSnapshot().copy(tariffs = null))
        // The claim is about the list, not its contents: an observed empty list may be complete.
        assertNoProblems(validSnapshot().copy(tariffs = emptyList()))
    }

    @Test
    fun `observedAt may be slightly ahead of the clock but not minutes ahead`() {
        assertNoProblems(validSnapshot().copy(observedAt = FIXTURE_NOW.plusSeconds(30)))

        assertOnlyProblem("observedAt", validSnapshot().copy(observedAt = FIXTURE_NOW.plus(Duration.ofMinutes(10))))
    }

    @Test
    fun `every independent problem is reported, not just the first`() {
        val broken = validSnapshot()
            .copy(sourceUrls = emptyList())
            .withTariff(0) { it.copy(ramMb = 0) }
            .withTariff(1) { t -> t.withPrice(0) { it.copy(currency = "eur") } }
            .withLocation(0) { it.copy(latitude = BigDecimal("91.000000")) }

        val found = SnapshotValidator(fixtureClock()).validate(fixtureAdapter(), broken)

        assertEquals(
            setOf("sourceUrls", "locations[0].latitude", "tariffs[0].ramMb", "tariffs[1].prices[0].currency"),
            found.map { it.path }.toSet(),
            "problems: $found",
        )
        assertEquals(4, found.size, "problems: $found")
    }

    @Test
    fun `the log line stays on one line and truncates a long problem list`() {
        val problems = (1..12).map { SnapshotProblem("tariffs[$it].ramMb", "must be between 1 and 2147483647") }

        val line = problems.toLogLine()

        assertEquals(PROBLEM_LOG_LIMIT, line.split("; ").size - 1, line)
        assertTrue(line.startsWith("12 problems: tariffs[1].ramMb: "), line)
        assertTrue(line.endsWith("... and 2 more"), line)
        assertTrue(problems.take(PROBLEM_LOG_LIMIT).toLogLine().endsWith("ramMb: must be between 1 and 2147483647"))
        assertTrue(line.none { it == '\n' || it == '\r' }, line)
        assertEquals("no problems", emptyList<SnapshotProblem>().toLogLine())
    }
}

private fun mutate(block: ProviderSnapshot.() -> ProviderSnapshot): ProviderSnapshot = validSnapshot().block()

private fun problemsOf(snapshot: ProviderSnapshot, clock: Clock = fixtureClock()): List<SnapshotProblem> =
    SnapshotValidator(clock).validate(fixtureAdapter(), snapshot)

/** Exactly one problem, at [expectedPath] -- every other element of the fixture stays acceptable. */
private fun assertOnlyProblem(expectedPath: String, snapshot: ProviderSnapshot) {
    val found = problemsOf(snapshot)
    assertEquals(listOf(expectedPath), found.map { it.path }, "problems: $found")
    assertTrue(found.single().message.isNotBlank())
}

private fun assertNoProblems(snapshot: ProviderSnapshot) {
    assertEquals(emptyList(), problemsOf(snapshot).map { it.path })
}
