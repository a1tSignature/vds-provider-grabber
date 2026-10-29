package org.a1tsign.vds.db

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.domain.Virtualization
import org.a1tsign.vds.domain.includes
import org.a1tsign.vds.domain.parseDbEnum
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the Kotlin mirror (`domain/Enums.kt`, `db/Tables.kt`, `db/RowMappers.kt`) and
 * `V1__core_schema.sql` cannot silently drift apart. Every enum/column expectation is read from
 * the database itself (`pg_constraint`/`information_schema`) rather than hardcoded a second time
 * here -- a hardcoded list would only prove the test author can copy-paste, not that the two sides
 * agree. Adding a value to one side without the other must fail this test.
 */
class SchemaEnumParityTest : AbstractDbTest() {
    // ---- small JDBC helpers (mirrors the style of CoreSchemaTest) -------------------------

    private fun Connection.stringSet(sql: String, vararg params: Any?): Set<String> {
        val result = mutableSetOf<String>()
        prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                while (rs.next()) result += rs.getString(1)
            }
        }
        return result
    }

    /**
     * The literal values of the single CHECK constraint covering [table].[column], read from
     * `information_schema`. Postgres rewrites `col IN ('A', 'B')` into
     * `(col)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying])::text[])` --
     * verified against a real container -- so the literals are pulled out with a quote-delimited
     * regex rather than assumed to still say "IN (...)".
     */
    private fun Connection.checkConstraintLiterals(table: String, column: String): Set<String> {
        val clauses = mutableListOf<String>()
        prepareStatement(
            """
            SELECT cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_schema = ccu.constraint_schema AND tc.constraint_name = ccu.constraint_name
            WHERE tc.table_schema = 'public' AND tc.table_name = ? AND tc.constraint_type = 'CHECK'
              AND ccu.column_name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, column)
            statement.executeQuery().use { rs ->
                while (rs.next()) clauses += rs.getString("check_clause")
            }
        }
        check(clauses.size == 1) {
            "expected exactly one CHECK constraint on $table.$column, found ${clauses.size}"
        }
        return Regex("'([^']*)'").findAll(clauses.single()).map { it.groupValues[1] }.toSet()
    }

    private fun Connection.columnNamesOf(table: String): Set<String> =
        stringSet(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
            table,
        )

    private inline fun <reified E : Enum<E>> Connection.assertEnumMatchesCheckConstraint(
        table: String,
        column: String,
    ) {
        assertEquals(
            checkConstraintLiterals(table, column),
            enumValues<E>().map { it.name }.toSet(),
            "$table.$column CHECK constraint vs ${E::class.simpleName}.entries",
        )
    }

    // ---- 1. enum <-> CHECK parity, driven by the database ---------------------------------

    @Test
    fun `every enum-like column CHECK constraint matches its Kotlin enum exactly`() {
        PostgresSupport.dataSource.connection.use { connection ->
            connection.assertEnumMatchesCheckConstraint<ProviderStatus>("provider", "status")
            connection.assertEnumMatchesCheckConstraint<LegalDataSource>("provider_legal_info", "data_source")
            connection.assertEnumMatchesCheckConstraint<Confidence>("provider_legal_info", "confidence")
            connection.assertEnumMatchesCheckConstraint<PaymentMethod>("provider_payment_method", "method")
            connection.assertEnumMatchesCheckConstraint<DiskType>("tariff", "disk_type")
            connection.assertEnumMatchesCheckConstraint<Virtualization>("tariff", "virtualization")
            connection.assertEnumMatchesCheckConstraint<BillingPeriod>("tariff_price", "billing_period")
            connection.assertEnumMatchesCheckConstraint<PriceDataSource>("tariff_price", "data_source")
            connection.assertEnumMatchesCheckConstraint<UserStatus>("app_user", "status")
        }
    }

    // ---- 2. seeded role.code vs Role enum --------------------------------------------------

    @Test
    fun `role table codes equal Role enum entries`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val codes = connection.stringSet("SELECT code FROM role")
            assertEquals(Role.entries.map { it.name }.toSet(), codes)
        }
    }

    // ---- 3. table-object <-> information_schema column parity -----------------------------

    @Test
    fun `every Exposed table object has exactly the columns of its SQL table`() {
        val tables: List<Pair<Table, String>> = listOf(
            ProviderTable to "provider",
            ProviderLegalInfoTable to "provider_legal_info",
            ProviderPaymentMethodTable to "provider_payment_method",
            LocationTable to "location",
            TariffTable to "tariff",
            TariffLocationTable to "tariff_location",
            TariffPriceTable to "tariff_price",
            CurrencyRateTable to "currency_rate",
            AppUserTable to "app_user",
            RoleTable to "role",
            UserRoleTable to "user_role",
        )

        PostgresSupport.dataSource.connection.use { connection ->
            tables.forEach { (table, sqlName) ->
                assertEquals(
                    connection.columnNamesOf(sqlName),
                    table.columns.map { it.name }.toSet(),
                    "columns of '$sqlName'",
                )
            }
        }
    }

    // ---- 4. round trip through Exposed: insert via the table object, read via the mapper --

    @Test
    fun `provider round trips through Exposed insert and the row mapper`() {
        transaction(PostgresSupport.database) {
            val createdAt = Instant.parse("2024-03-15T10:30:00.123456Z")
            val updatedAt = Instant.parse("2024-03-16T08:00:00Z")

            val id = ProviderTable.insert {
                it[slug] = "roundtrip-provider"
                it[name] = "Roundtrip Provider"
                it[websiteUrl] = "https://roundtrip.example.com"
                it[description] = "a description"
                it[status] = ProviderStatus.ACTIVE.name
                it[ProviderTable.createdAt] = createdAt.toUtcOffset()
                it[ProviderTable.updatedAt] = updatedAt.toUtcOffset()
            }[ProviderTable.id]

            val provider = ProviderTable.selectAll().where { ProviderTable.id eq id }.single().toProvider()

            assertEquals(id, provider.id)
            assertEquals("roundtrip-provider", provider.slug)
            assertEquals("Roundtrip Provider", provider.name)
            assertEquals("https://roundtrip.example.com", provider.websiteUrl)
            assertEquals("a description", provider.description)
            assertEquals(ProviderStatus.ACTIVE, provider.status)
            assertEquals(createdAt, provider.createdAt)
            assertEquals(updatedAt, provider.updatedAt)
        }
    }

    @Test
    fun `tariff round trips through Exposed insert and the row mapper`() {
        transaction(PostgresSupport.database) {
            val now = Instant.parse("2024-05-01T00:00:00Z")
            val providerId = ProviderTable.insert {
                it[slug] = "roundtrip-tariff-provider"
                it[name] = "Provider"
                it[websiteUrl] = "https://example.com"
                it[status] = ProviderStatus.ACTIVE.name
                it[createdAt] = now.toUtcOffset()
                it[updatedAt] = now.toUtcOffset()
            }[ProviderTable.id]

            val tariffId = TariffTable.insert {
                it[TariffTable.providerId] = providerId
                it[externalId] = "ext-1"
                it[name] = "Roundtrip Tariff"
                it[url] = "https://example.com/tariff"
                it[vcpuCount] = 4
                it[cpuModel] = "EPYC"
                it[ramMb] = 8192
                it[diskGb] = 100
                it[diskType] = DiskType.NVME.name
                it[bandwidthMbps] = 1000
                it[trafficLimitGb] = 5000
                it[ipv4Count] = 2
                it[ipv6Supported] = true
                it[virtualization] = Virtualization.KVM.name
                it[isActive] = true
                it[inStock] = false
                it[createdAt] = now.toUtcOffset()
                it[updatedAt] = now.toUtcOffset()
            }[TariffTable.id]

            val tariff = TariffTable.selectAll().where { TariffTable.id eq tariffId }.single().toTariff()

            assertEquals(tariffId, tariff.id)
            assertEquals(providerId, tariff.providerId)
            assertEquals("ext-1", tariff.externalId)
            assertEquals("Roundtrip Tariff", tariff.name)
            assertEquals("https://example.com/tariff", tariff.url)
            assertEquals(4.toShort(), tariff.vcpuCount)
            assertEquals("EPYC", tariff.cpuModel)
            assertEquals(8192, tariff.ramMb)
            assertEquals(100, tariff.diskGb)
            assertEquals(DiskType.NVME, tariff.diskType)
            assertEquals(1000, tariff.bandwidthMbps)
            assertEquals(5000, tariff.trafficLimitGb)
            assertEquals(2.toShort(), tariff.ipv4Count)
            assertEquals(true, tariff.ipv6Supported)
            assertEquals(Virtualization.KVM, tariff.virtualization)
            assertTrue(tariff.isActive)
            assertFalse(tariff.inStock)
            assertEquals(now, tariff.createdAt)
            assertEquals(now, tariff.updatedAt)
        }
    }

    @Test
    fun `tariff_price round trips an exact BigDecimal and a microsecond-precision timestamp`() {
        transaction(PostgresSupport.database) {
            val now = Instant.parse("2024-05-01T00:00:00Z")
            val effectiveFrom = Instant.parse("2024-06-01T09:15:30.654321Z")
            val usdRateAt = Instant.parse("2024-06-01T09:00:00Z")

            val providerId = ProviderTable.insert {
                it[slug] = "roundtrip-price-provider"
                it[name] = "Provider"
                it[websiteUrl] = "https://example.com"
                it[status] = ProviderStatus.ACTIVE.name
                it[createdAt] = now.toUtcOffset()
                it[updatedAt] = now.toUtcOffset()
            }[ProviderTable.id]

            val tariffId = TariffTable.insert {
                it[TariffTable.providerId] = providerId
                it[name] = "Priced Tariff"
                it[vcpuCount] = 1
                it[ramMb] = 512
                it[diskGb] = 10
                it[diskType] = DiskType.SSD.name
                it[virtualization] = Virtualization.KVM.name
                it[createdAt] = now.toUtcOffset()
                it[updatedAt] = now.toUtcOffset()
            }[TariffTable.id]

            val userId = AppUserTable.insert {
                it[username] = "price-recorder"
                it[passwordHash] = "hash"
                it[status] = UserStatus.ACTIVE.name
                it[createdAt] = now.toUtcOffset()
                it[updatedAt] = now.toUtcOffset()
            }[AppUserTable.id]

            // Written with no fractional digits; NUMERIC(12,4) stores it zero-padded to scale 4.
            // Comparing with compareTo (not equals) is the point of this assertion -- 10 and
            // 10.0000 are the same number but not `BigDecimal`-`equals`.
            val writtenAmount = BigDecimal("10")

            val priceId = TariffPriceTable.insert {
                it[TariffPriceTable.tariffId] = tariffId
                it[billingPeriod] = BillingPeriod.MONTHLY.name
                it[amount] = writtenAmount
                it[currency] = "USD"
                it[amountUsd] = BigDecimal("10.0000")
                it[usdRate] = BigDecimal("1.000000000000")
                it[TariffPriceTable.usdRateAt] = usdRateAt.toUtcOffset()
                it[TariffPriceTable.effectiveFrom] = effectiveFrom.toUtcOffset()
                it[dataSource] = PriceDataSource.MANUAL.name
                it[recordedBy] = userId
                it[TariffPriceTable.createdAt] = now.toUtcOffset()
            }[TariffPriceTable.id]

            val price = TariffPriceTable.selectAll().where { TariffPriceTable.id eq priceId }.single().toTariffPrice()

            assertEquals(priceId, price.id)
            assertEquals(tariffId, price.tariffId)
            assertEquals(BillingPeriod.MONTHLY, price.billingPeriod)
            assertEquals(0, writtenAmount.compareTo(price.amount), "amount: 10 must equal 10.0000 by value")
            assertEquals("USD", price.currency)
            assertEquals(0, BigDecimal("10.0000").compareTo(price.amountUsd))
            assertEquals(0, BigDecimal("1.000000000000").compareTo(price.usdRate))
            assertEquals(usdRateAt, price.usdRateAt)
            assertEquals(effectiveFrom, price.effectiveFrom)
            assertEquals(null, price.effectiveTo)
            assertEquals(PriceDataSource.MANUAL, price.dataSource)
            assertEquals(userId, price.recordedBy)
            assertEquals(now, price.createdAt)
        }
    }

    // ---- 5. parseDbEnum fails loudly, naming table/column/value ---------------------------

    @Test
    fun `parseDbEnum throws naming the table column and offending value for an unknown value`() {
        val exception = assertFailsWith<IllegalStateException> {
            parseDbEnum<ProviderStatus>("provider", "status", "BOGUS")
        }
        assertTrue(exception.message!!.contains("provider"), "message must name the table")
        assertTrue(exception.message!!.contains("status"), "message must name the column")
        assertTrue(exception.message!!.contains("BOGUS"), "message must name the offending value")
    }

    // ---- 6. Role.includes privilege ordering -----------------------------------------------

    @Test
    fun `Role includes expresses ADMIN over EDITOR over VIEWER`() {
        assertTrue(Role.ADMIN.includes(Role.EDITOR))
        assertTrue(Role.ADMIN.includes(Role.VIEWER))
        assertFalse(Role.EDITOR.includes(Role.ADMIN))
        assertFalse(Role.VIEWER.includes(Role.EDITOR))
        Role.entries.forEach { role -> assertTrue(role.includes(role), "$role must include itself") }
    }

    @Test
    fun `Role declaration order is pinned to ADMIN EDITOR VIEWER`() {
        // Role.includes() is implemented as a direct ordinal comparison (see its KDoc), so a
        // future reordering of this enum would silently change what includes() means. Pinning
        // the declaration order here makes that failure loud instead of silent.
        assertEquals(listOf(Role.ADMIN, Role.EDITOR, Role.VIEWER), Role.entries)
    }
}
