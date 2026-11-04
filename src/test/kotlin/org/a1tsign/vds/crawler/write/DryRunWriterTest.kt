package org.a1tsign.vds.crawler.write

import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.Virtualization
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer itself, without a catalog: what matters here is that the ids it fabricates stay
 * consistent for the same entity (otherwise the follow-up operations of a preview would be planned
 * against ids that drift) and that each line names the provider and the entity.
 *
 * "Writes nothing" is proved where it matters -- against the real application -- in `ReconcilerTest`.
 */
class DryRunWriterTest {

    @Test
    fun `the same entity keeps one fabricated id while a different one gets another`() = runBlocking {
        val writer = DryRunWriter()
        writer.beginProvider("acme")

        val provider = writer.createProvider(providerWrite("acme"))
        val providerAgain = writer.createProvider(providerWrite("acme"))
        val other = writer.createProvider(providerWrite("beta"))
        val berlin = writer.createLocation(provider, location(city = "Berlin"))
        // Same row by vds-001's unique index (case-insensitive city/datacenter), so the same id.
        val berlinAgain = writer.createLocation(provider, location(city = "berlin"))
        val hamburg = writer.createLocation(provider, location(city = "Hamburg"))
        val tariff = writer.createTariff(provider, tariffWrite("VPS 1", externalId = "p-1"))
        val tariffAgain = writer.createTariff(provider, tariffWrite("renamed", externalId = "p-1"))

        assertEquals(provider, providerAgain)
        assertEquals(berlin, berlinAgain)
        assertEquals(tariff, tariffAgain, "the externalId identifies the tariff, not its name")
        assertEquals(5, setOf(provider, other, berlin, hamburg, tariff).size, "distinct entities, distinct ids")
        assertTrue(
            listOf(provider, other, berlin, hamburg, tariff).all { it < 0 },
            "a fabricated id must be impossible to confuse with a real one",
        )
    }

    @Test
    fun `every intended mutation is recorded as one line naming the provider and the entity`() = runBlocking {
        val writer = DryRunWriter()
        writer.beginProvider("acme")
        val provider = writer.createProvider(providerWrite("acme"))
        val tariff = writer.createTariff(provider, tariffWrite("VPS 1"))
        writer.putPaymentMethods(provider, listOf(PaymentMethodWrite("VISA_MASTERCARD")))
        val price = writer.putPrice(
            tariff,
            PriceWrite(BillingPeriod.MONTHLY.name, BigDecimal("9.9900"), "EUR", "CRAWLER"),
        )

        assertEquals(
            listOf(
                "CREATE_PROVIDER provider=acme name=Acme",
                "CREATE_TARIFF provider=acme name=VPS 1",
                "PUT_PAYMENT_METHODS provider=acme methods=VISA_MASTERCARD",
                "PUT_PRICE provider=acme tariff=VPS 1 period=MONTHLY amount=9.9900 currency=EUR",
            ),
            writer.plannedOperations(),
        )
        assertEquals(
            PriceOutcome(appended = true, noFxRate = false),
            price,
            "a preview reports the append it would have made, and consults no FX state",
        )
    }

    @Test
    fun `the plan stops growing at 200 lines while the writer keeps working`() = runBlocking {
        val writer = DryRunWriter()
        writer.beginProvider("acme")
        val provider = writer.createProvider(providerWrite("acme"))

        repeat(500) { index -> writer.createTariff(provider, tariffWrite("VPS $index")) }

        assertEquals(PLANNED_OPERATIONS_LIMIT, writer.plannedOperations().size)
        assertEquals("CREATE_PROVIDER provider=acme name=Acme", writer.plannedOperations().first())
        assertEquals(
            "CREATE_TARIFF provider=acme name=VPS 198",
            writer.plannedOperations().last(),
            "the cap keeps the first lines, which is where the run started going wrong",
        )
    }

    private fun providerWrite(slug: String) = ProviderWrite(
        slug = slug,
        name = "Acme",
        websiteUrl = "https://$slug.example",
        status = "ACTIVE",
    )

    private fun location(city: String) = LocationWrite(countryCode = "DE", city = city, datacenterName = "DC1")

    private fun tariffWrite(name: String, externalId: String? = null) = TariffWrite(
        name = name,
        vcpuCount = 2,
        ramMb = 2048,
        diskGb = 40,
        diskType = DiskType.NVME.name,
        ipv4Count = 1,
        virtualization = Virtualization.KVM.name,
        isActive = true,
        inStock = true,
        externalId = externalId,
    )
}
