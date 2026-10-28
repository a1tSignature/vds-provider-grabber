package org.a1tsign.vds.db

import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves `V1__core_schema.sql` against the real migrated container -- never by parsing the .sql
 * file. Every assertion queries `information_schema`/`pg_constraint` or exercises a constraint
 * with real inserts, and is scoped to the ids this test itself created (the container, and the
 * schema in it, is shared across every test class in the suite).
 */
class CoreSchemaTest : AbstractDbTest() {
    // ---- small JDBC helpers -------------------------------------------------------------

    private fun Connection.insertId(sql: String, vararg params: Any?): Long =
        prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun Connection.exec(sql: String, vararg params: Any?) {
        prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun Connection.scalarLong(sql: String, vararg params: Any?): Long =
        prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

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
     * Asserts the insert fails, and that it fails for the *specific* named constraint expected --
     * not merely with *some* `SQLException`, which would also pass for a typo in the test's own
     * SQL or the wrong constraint firing. `PSQLException.serverErrorMessage.constraint` names the
     * constraint (or unique index, which Postgres reports the same way) that actually fired on the
     * server.
     */
    private fun Connection.assertInsertFails(expectedConstraint: String, sql: String, vararg params: Any?) {
        val exception = assertFailsWith<SQLException> { exec(sql, *params) }
        val psqlException = exception as? PSQLException ?: exception.cause as? PSQLException
        assertEquals(
            expectedConstraint,
            psqlException?.serverErrorMessage?.constraint,
            "constraint expected to fire for: $sql",
        )
    }

    private fun newProvider(connection: Connection, slug: String, status: String = "ACTIVE"): Long =
        connection.insertId(
            "INSERT INTO provider (slug, name, website_url, status) VALUES (?, ?, ?, ?) RETURNING id",
            slug,
            slug,
            "https://$slug.example.com",
            status,
        )

    private fun newTariff(
        connection: Connection,
        providerId: Long,
        name: String,
        diskType: String = "SSD",
        virtualization: String = "KVM",
    ): Long =
        connection.insertId(
            """
            INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
            VALUES (?, ?, 1, 512, 10, ?, ?) RETURNING id
            """.trimIndent(),
            providerId,
            name,
            diskType,
            virtualization,
        )

    private fun newLocation(connection: Connection, providerId: Long, countryCode: String = "US"): Long =
        connection.insertId(
            "INSERT INTO location (provider_id, country_code) VALUES (?, ?) RETURNING id",
            providerId,
            countryCode,
        )

    private fun newUser(connection: Connection, username: String, status: String = "ACTIVE"): Long =
        connection.insertId(
            "INSERT INTO app_user (username, password_hash, status) VALUES (?, 'hash', ?) RETURNING id",
            username,
            status,
        )

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    // ---- 1. tables and columns -----------------------------------------------------------

    private fun Connection.columnsOf(table: String): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        prepareStatement(
            "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    result[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
                }
            }
        }
        return result
    }

    @Test
    fun `every core table has exactly the expected columns and nullability`() {
        val expected: Map<String, Map<String, Boolean>> = mapOf(
            "provider" to mapOf(
                "id" to false,
                "slug" to false,
                "name" to false,
                "website_url" to false,
                "description" to true,
                "status" to false,
                "created_at" to false,
                "updated_at" to false,
            ),
            "provider_legal_info" to mapOf(
                "provider_id" to false,
                "legal_entity_name" to true,
                "registration_country" to true,
                "registration_number" to true,
                "legal_address" to true,
                "data_source" to false,
                "confidence" to false,
                "source_url" to true,
                "verified_at" to true,
                "updated_at" to false,
            ),
            "provider_payment_method" to mapOf(
                "provider_id" to false,
                "method" to false,
                "note" to true,
            ),
            "location" to mapOf(
                "id" to false,
                "provider_id" to false,
                "country_code" to false,
                "region" to true,
                "city" to true,
                "datacenter_name" to true,
                "latitude" to true,
                "longitude" to true,
                "created_at" to false,
                "updated_at" to false,
            ),
            "tariff" to mapOf(
                "id" to false,
                "provider_id" to false,
                "external_id" to true,
                "name" to false,
                "url" to true,
                "vcpu_count" to false,
                "cpu_model" to true,
                "ram_mb" to false,
                "disk_gb" to false,
                "disk_type" to false,
                "bandwidth_mbps" to true,
                "traffic_limit_gb" to true,
                "ipv4_count" to false,
                "ipv6_supported" to true,
                "virtualization" to false,
                "is_active" to false,
                "in_stock" to false,
                "created_at" to false,
                "updated_at" to false,
            ),
            "tariff_location" to mapOf(
                "tariff_id" to false,
                "location_id" to false,
                "provider_id" to false,
            ),
            "tariff_price" to mapOf(
                "id" to false,
                "tariff_id" to false,
                "billing_period" to false,
                "amount" to false,
                "currency" to false,
                "amount_usd" to true,
                "usd_rate" to true,
                "usd_rate_at" to true,
                "effective_from" to false,
                "effective_to" to true,
                "data_source" to false,
                "recorded_by" to true,
                "created_at" to false,
            ),
            "currency_rate" to mapOf(
                "id" to false,
                "currency" to false,
                "rate_to_usd" to false,
                "source_rate_at" to false,
                "fetched_at" to false,
                "source" to false,
            ),
            "app_user" to mapOf(
                "id" to false,
                "username" to false,
                "email" to true,
                "password_hash" to false,
                "status" to false,
                "token_version" to false,
                "created_at" to false,
                "updated_at" to false,
                "last_login_at" to true,
            ),
            "role" to mapOf(
                "id" to false,
                "code" to false,
                "description" to true,
            ),
            "user_role" to mapOf(
                "user_id" to false,
                "role_id" to false,
            ),
        )

        PostgresSupport.dataSource.connection.use { connection ->
            expected.forEach { (table, expectedColumns) ->
                assertEquals(expectedColumns, connection.columnsOf(table), "columns of table '$table'")
            }
        }
    }

    // ---- 2. numeric precision/scale, no float/double -------------------------------------

    @Test
    fun `money and rate columns are numeric with the exact precision and scale`() {
        data class NumericSpec(val table: String, val column: String, val precision: Int, val scale: Int)

        val expected = listOf(
            NumericSpec("tariff_price", "amount", 12, 4),
            NumericSpec("tariff_price", "amount_usd", 12, 4),
            NumericSpec("tariff_price", "usd_rate", 20, 12),
            NumericSpec("currency_rate", "rate_to_usd", 20, 12),
            NumericSpec("location", "latitude", 9, 6),
            NumericSpec("location", "longitude", 9, 6),
        )

        PostgresSupport.dataSource.connection.use { connection ->
            expected.forEach { spec ->
                prepareStatementAndAssertNumeric(connection, spec.table, spec.column, spec.precision, spec.scale)
            }

            val floatOrDoubleColumnCount = connection.scalarLong(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND data_type IN ('double precision', 'real')",
            )
            assertEquals(0L, floatOrDoubleColumnCount, "no float/double column may exist anywhere in schema public")
        }
    }

    private fun prepareStatementAndAssertNumeric(
        connection: Connection,
        table: String,
        column: String,
        expectedPrecision: Int,
        expectedScale: Int,
    ) {
        connection.prepareStatement(
            """
            SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, column)
            statement.executeQuery().use { rs ->
                assertTrue(rs.next(), "$table.$column must exist")
                assertEquals("numeric", rs.getString("data_type"), "$table.$column data type")
                assertEquals(expectedPrecision, rs.getInt("numeric_precision"), "$table.$column precision")
                assertEquals(expectedScale, rs.getInt("numeric_scale"), "$table.$column scale")
            }
        }
    }

    // ---- 3. timestamp columns --------------------------------------------------------------

    @Test
    fun `every timestamp column is timestamp with time zone`() {
        val expected = setOf(
            "provider.created_at",
            "provider.updated_at",
            "provider_legal_info.verified_at",
            "provider_legal_info.updated_at",
            "location.created_at",
            "location.updated_at",
            "tariff.created_at",
            "tariff.updated_at",
            "tariff_price.usd_rate_at",
            "tariff_price.effective_from",
            "tariff_price.effective_to",
            "tariff_price.created_at",
            "currency_rate.source_rate_at",
            "currency_rate.fetched_at",
            "app_user.created_at",
            "app_user.updated_at",
            "app_user.last_login_at",
        )

        PostgresSupport.dataSource.connection.use { connection ->
            val actual = connection.stringSet(
                """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                  AND data_type = 'timestamp with time zone'
                """.trimIndent(),
            )
            assertEquals(expected, actual)

            // flyway_schema_history is Flyway's own metadata table (its installed_on column is a
            // naive timestamp) -- not part of this migration's schema, excluded like above.
            val withoutTimeZoneCount = connection.scalarLong(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' AND data_type = 'timestamp without time zone'",
            )
            assertEquals(0L, withoutTimeZoneCount, "no naive timestamp column may exist outside flyway_schema_history")
        }
    }

    // ---- 4. enum-like CHECK constraints ----------------------------------------------------

    @Test
    fun `provider status accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            listOf("ACTIVE", "INACTIVE", "UNKNOWN").forEach { status ->
                newProvider(connection, "provider-status-$status".lowercase(), status)
            }
            connection.assertInsertFails(
                "ck_provider_status",
                "INSERT INTO provider (slug, name, website_url, status) VALUES ('provider-status-bogus', 'x', 'https://x.example.com', 'BOGUS')",
            )
        }
    }

    @Test
    fun `provider_legal_info data_source accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            listOf("MANUAL", "CRAWLER", "WHOIS", "PROVIDER_SITE", "UNKNOWN").forEachIndexed { index, dataSource ->
                val providerId = newProvider(connection, "legal-ds-$index")
                connection.exec(
                    "INSERT INTO provider_legal_info (provider_id, data_source, confidence) VALUES (?, ?, 'HIGH')",
                    providerId,
                    dataSource,
                )
            }
            val providerId = newProvider(connection, "legal-ds-invalid")
            connection.assertInsertFails(
                "ck_provider_legal_info_data_source",
                "INSERT INTO provider_legal_info (provider_id, data_source, confidence) VALUES (?, 'BOGUS', 'HIGH')",
                providerId,
            )
        }
    }

    @Test
    fun `provider_legal_info confidence accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            listOf("HIGH", "MEDIUM", "LOW", "UNVERIFIED").forEachIndexed { index, confidence ->
                val providerId = newProvider(connection, "legal-conf-$index")
                connection.exec(
                    "INSERT INTO provider_legal_info (provider_id, data_source, confidence) VALUES (?, 'MANUAL', ?)",
                    providerId,
                    confidence,
                )
            }
            val providerId = newProvider(connection, "legal-conf-invalid")
            connection.assertInsertFails(
                "ck_provider_legal_info_confidence",
                "INSERT INTO provider_legal_info (provider_id, data_source, confidence) VALUES (?, 'MANUAL', 'BOGUS')",
                providerId,
            )
        }
    }

    @Test
    fun `provider_payment_method accepts exactly the 20 defined methods and rejects any other`() {
        val allMethods = setOf(
            "VISA_MASTERCARD", "AMERICAN_EXPRESS", "UNIONPAY", "MIR", "SBP", "RU_BANK_TRANSFER",
            "BANK_WIRE", "PAYPAL", "ALIPAY", "WECHAT_PAY", "APPLE_PAY", "GOOGLE_PAY",
            "CRYPTO_BTC", "CRYPTO_ETH", "CRYPTO_USDT", "CRYPTO_OTHER", "WEBMONEY", "PERFECT_MONEY",
            "CASH", "OTHER",
        )
        assertEquals(20, allMethods.size, "sanity check on the literal list itself")

        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "payment-methods")
            allMethods.forEach { method ->
                connection.exec(
                    "INSERT INTO provider_payment_method (provider_id, method) VALUES (?, ?)",
                    providerId,
                    method,
                )
            }

            val readBack = connection.stringSet(
                "SELECT method FROM provider_payment_method WHERE provider_id = ?",
                providerId,
            )
            assertEquals(allMethods, readBack)

            connection.assertInsertFails(
                "ck_provider_payment_method_method",
                "INSERT INTO provider_payment_method (provider_id, method) VALUES (?, 'BITCOIN_CASH')",
                providerId,
            )
        }
    }

    @Test
    fun `tariff disk_type accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "disk-type")
            listOf("NVME", "SSD", "HDD", "UNKNOWN").forEachIndexed { index, diskType ->
                newTariff(connection, providerId, "disk-type-$index", diskType = diskType)
            }
            connection.assertInsertFails(
                "ck_tariff_disk_type",
                "INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization) VALUES (?, 'bad-disk', 1, 512, 10, 'FLOPPY', 'KVM')",
                providerId,
            )
        }
    }

    @Test
    fun `tariff virtualization accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "virt-type")
            listOf("KVM", "OPENVZ", "LXC", "XEN", "HYPERV", "UNKNOWN").forEachIndexed { index, virtualization ->
                newTariff(connection, providerId, "virt-type-$index", virtualization = virtualization)
            }
            connection.assertInsertFails(
                "ck_tariff_virtualization",
                "INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization) VALUES (?, 'bad-virt', 1, 512, 10, 'SSD', 'VMWARE')",
                providerId,
            )
        }
    }

    @Test
    fun `tariff_price billing_period accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "billing-period")
            val tariffId = newTariff(connection, providerId, "billing-period-tariff")
            val periods = listOf("HOURLY", "MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL", "BIENNIAL", "TRIENNIAL")
            periods.forEach { period ->
                connection.exec(
                    """
                    INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                    VALUES (?, ?, 10.0000, 'USD', ?, 'MANUAL')
                    """.trimIndent(),
                    tariffId,
                    period,
                    now,
                )
            }
            val readBack = connection.stringSet(
                "SELECT billing_period FROM tariff_price WHERE tariff_id = ?",
                tariffId,
            )
            assertEquals(periods.toSet(), readBack)

            connection.assertInsertFails(
                "ck_tariff_price_billing_period",
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'WEEKLY', 10.0000, 'USD', ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now,
            )
        }
    }

    @Test
    fun `tariff_price data_source accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "price-data-source")
            val tariffId = newTariff(connection, providerId, "price-data-source-tariff")
            listOf("MANUAL" to "HOURLY", "CRAWLER" to "MONTHLY").forEach { (dataSource, period) ->
                connection.exec(
                    """
                    INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                    VALUES (?, ?, 10.0000, 'USD', ?, ?)
                    """.trimIndent(),
                    tariffId,
                    period,
                    now,
                    dataSource,
                )
            }
            connection.assertInsertFails(
                "ck_tariff_price_data_source",
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'QUARTERLY', 10.0000, 'USD', ?, 'IMPORT')
                """.trimIndent(),
                tariffId,
                now,
            )
        }
    }

    @Test
    fun `app_user status accepts every intended value and rejects an invalid one`() {
        PostgresSupport.dataSource.connection.use { connection ->
            listOf("ACTIVE", "DISABLED").forEachIndexed { index, status ->
                newUser(connection, "user-status-$index", status)
            }
            connection.assertInsertFails(
                "ck_app_user_status",
                "INSERT INTO app_user (username, password_hash, status) VALUES ('user-status-bogus', 'hash', 'BOGUS')",
            )
        }
    }

    // ---- 5. partial unique index on the current price ------------------------------------

    @Test
    fun `at most one current price per tariff and billing period, but closed rows are unrestricted`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "current-price")
            val tariffId = newTariff(connection, providerId, "current-price-tariff")

            connection.exec(
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'MONTHLY', 10.0000, 'USD', ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now,
            )

            // A second *current* row for the same (tariff, billing_period) must be rejected.
            connection.assertInsertFails(
                "uq_tariff_price_current",
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'MONTHLY', 12.0000, 'USD', ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now.plusDays(1),
            )

            // Two *closed* rows for the same pair are both allowed.
            connection.exec(
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, effective_to, data_source)
                VALUES (?, 'MONTHLY', 8.0000, 'USD', ?, ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now.minusDays(60),
                now.minusDays(30),
            )
            connection.exec(
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, effective_to, data_source)
                VALUES (?, 'MONTHLY', 9.0000, 'USD', ?, ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now.minusDays(30),
                now.minusDays(1),
            )

            val rowCount = connection.scalarLong(
                "SELECT count(*) FROM tariff_price WHERE tariff_id = ?",
                tariffId,
            )
            assertEquals(3L, rowCount)
        }
    }

    // ---- 6. cross-provider composite FK ----------------------------------------------------

    @Test
    fun `tariff_location composite FK rejects a tariff and location belonging to different providers`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerA = newProvider(connection, "cross-provider-a")
            val providerB = newProvider(connection, "cross-provider-b")
            val tariffA = newTariff(connection, providerA, "cross-provider-tariff-a")
            val locationB = newLocation(connection, providerB, "DE")

            // provider_id = providerA matches tariffA's real provider (fk_tariff_location_tariff
            // is satisfied), but locationB's real provider is providerB, not providerA -- only
            // fk_tariff_location_location fires.
            connection.assertInsertFails(
                "fk_tariff_location_location",
                "INSERT INTO tariff_location (tariff_id, location_id, provider_id) VALUES (?, ?, ?)",
                tariffA,
                locationB,
                providerA,
            )
            // provider_id = providerB matches locationB's real provider (fk_tariff_location_location
            // is satisfied), but tariffA's real provider is providerA, not providerB -- only
            // fk_tariff_location_tariff fires.
            connection.assertInsertFails(
                "fk_tariff_location_tariff",
                "INSERT INTO tariff_location (tariff_id, location_id, provider_id) VALUES (?, ?, ?)",
                tariffA,
                locationB,
                providerB,
            )

            // A matching provider on both sides succeeds.
            val locationA = newLocation(connection, providerA, "FR")
            connection.exec(
                "INSERT INTO tariff_location (tariff_id, location_id, provider_id) VALUES (?, ?, ?)",
                tariffA,
                locationA,
                providerA,
            )
            val linked = connection.scalarLong(
                "SELECT count(*) FROM tariff_location WHERE tariff_id = ? AND location_id = ?",
                tariffA,
                locationA,
            )
            assertEquals(1L, linked)
        }
    }

    // ---- 7. half-filled coordinate pair ----------------------------------------------------

    @Test
    fun `location rejects a half-filled latitude-longitude pair`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "half-coords")

            connection.assertInsertFails(
                "ck_location_lat_lon_both_or_neither",
                "INSERT INTO location (provider_id, country_code, latitude, longitude) VALUES (?, 'US', 40.0, NULL)",
                providerId,
            )
            connection.assertInsertFails(
                "ck_location_lat_lon_both_or_neither",
                "INSERT INTO location (provider_id, country_code, latitude, longitude) VALUES (?, 'US', NULL, -73.0)",
                providerId,
            )

            // Both NULL, and both set within range, are fine.
            connection.exec(
                "INSERT INTO location (provider_id, country_code) VALUES (?, 'US')",
                providerId,
            )
            connection.exec(
                "INSERT INTO location (provider_id, country_code, latitude, longitude) VALUES (?, 'DE', 52.5, 13.4)",
                providerId,
            )

            val count = connection.scalarLong(
                "SELECT count(*) FROM location WHERE provider_id = ?",
                providerId,
            )
            assertEquals(2L, count)
        }
    }

    // ---- 8. ON DELETE CASCADE end to end ---------------------------------------------------

    @Test
    fun `deleting a provider cascades to legal info, payment methods, locations, tariffs, tariff_location and prices`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "cascade-provider")
            connection.exec(
                "INSERT INTO provider_legal_info (provider_id, data_source, confidence) VALUES (?, 'MANUAL', 'HIGH')",
                providerId,
            )
            connection.exec(
                "INSERT INTO provider_payment_method (provider_id, method) VALUES (?, 'PAYPAL')",
                providerId,
            )
            val locationId = newLocation(connection, providerId, "US")
            val tariffId = newTariff(connection, providerId, "cascade-tariff")
            connection.exec(
                "INSERT INTO tariff_location (tariff_id, location_id, provider_id) VALUES (?, ?, ?)",
                tariffId,
                locationId,
                providerId,
            )
            val priceId = connection.insertId(
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'MONTHLY', 10.0000, 'USD', ?, 'MANUAL') RETURNING id
                """.trimIndent(),
                tariffId,
                now,
            )

            connection.exec("DELETE FROM provider WHERE id = ?", providerId)

            assertEquals(
                0L,
                connection.scalarLong("SELECT count(*) FROM provider_legal_info WHERE provider_id = ?", providerId),
            )
            assertEquals(
                0L,
                connection.scalarLong("SELECT count(*) FROM provider_payment_method WHERE provider_id = ?", providerId),
            )
            assertEquals(0L, connection.scalarLong("SELECT count(*) FROM location WHERE id = ?", locationId))
            assertEquals(0L, connection.scalarLong("SELECT count(*) FROM tariff WHERE id = ?", tariffId))
            assertEquals(
                0L,
                connection.scalarLong("SELECT count(*) FROM tariff_location WHERE tariff_id = ?", tariffId),
            )
            assertEquals(0L, connection.scalarLong("SELECT count(*) FROM tariff_price WHERE id = ?", priceId))
        }
    }

    // ---- 9. deleting a user nulls out recorded_by, keeps the price row --------------------

    @Test
    fun `deleting the recording user sets tariff_price recorded_by to NULL and keeps the row`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val userId = newUser(connection, "price-recorder")
            val providerId = newProvider(connection, "recorded-by-provider")
            val tariffId = newTariff(connection, providerId, "recorded-by-tariff")
            val priceId = connection.insertId(
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source, recorded_by)
                VALUES (?, 'MONTHLY', 10.0000, 'USD', ?, 'MANUAL', ?) RETURNING id
                """.trimIndent(),
                tariffId,
                now,
                userId,
            )

            connection.exec("DELETE FROM app_user WHERE id = ?", userId)

            connection.prepareStatement("SELECT recorded_by FROM tariff_price WHERE id = ?").use { statement ->
                statement.setLong(1, priceId)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the price row must still exist")
                    assertNull(rs.getObject("recorded_by"), "recorded_by must be NULL after the user is gone")
                }
            }

            val stillOneRow = connection.scalarLong("SELECT count(*) FROM tariff_price WHERE id = ?", priceId)
            assertEquals(1L, stillOneRow)
        }
    }

    // ---- 10. seeded roles -------------------------------------------------------------------

    @Test
    fun `role table contains exactly ADMIN EDITOR and VIEWER`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val codes = connection.stringSet("SELECT code FROM role")
            assertEquals(setOf("ADMIN", "EDITOR", "VIEWER"), codes)
        }
    }

    // ---- 11. constraints that no test above actually pinned -------------------------------
    // Each of these can be deleted from V1__core_schema.sql individually with the rest of this
    // suite still green -- confirmed by temporarily removing each one and re-running the suite
    // before writing the test below for it. assertInsertFails checks the *specific* constraint
    // name that fired (see its KDoc), not just that some SQLException was thrown.

    @Test
    fun `provider slug must be unique`() {
        PostgresSupport.dataSource.connection.use { connection ->
            newProvider(connection, "dup-slug")
            connection.assertInsertFails(
                "uq_provider_slug",
                "INSERT INTO provider (slug, name, website_url, status) VALUES ('dup-slug', 'Other Name', 'https://dup-slug-2.example.com', 'ACTIVE')",
            )
        }
    }

    @Test
    fun `provider slug must match the lowercase alphanumeric-dash format`() {
        PostgresSupport.dataSource.connection.use { connection ->
            connection.assertInsertFails(
                "ck_provider_slug_format",
                "INSERT INTO provider (slug, name, website_url, status) VALUES ('Not A Valid Slug!', 'x', 'https://x.example.com', 'ACTIVE')",
            )
        }
    }

    @Test
    fun `location country_code must be two uppercase letters`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-country-code")
            connection.assertInsertFails(
                "ck_location_country_code",
                "INSERT INTO location (provider_id, country_code) VALUES (?, 'us')",
                providerId,
            )
        }
    }

    @Test
    fun `provider_legal_info registration_country must be two uppercase letters when present`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-registration-country")
            connection.assertInsertFails(
                "ck_provider_legal_info_registration_country",
                """
                INSERT INTO provider_legal_info (provider_id, registration_country, data_source, confidence)
                VALUES (?, 'us', 'MANUAL', 'HIGH')
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `location latitude must be within -90 and 90`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-latitude")
            connection.assertInsertFails(
                "ck_location_latitude_range",
                "INSERT INTO location (provider_id, country_code, latitude, longitude) VALUES (?, 'US', 100.0, 50.0)",
                providerId,
            )
        }
    }

    @Test
    fun `location longitude must be within -180 and 180`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-longitude")
            connection.assertInsertFails(
                "ck_location_longitude_range",
                "INSERT INTO location (provider_id, country_code, latitude, longitude) VALUES (?, 'US', 10.0, 200.0)",
                providerId,
            )
        }
    }

    @Test
    fun `location provider_country_city_dc must be unique case-insensitively`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "dup-location")
            connection.exec(
                "INSERT INTO location (provider_id, country_code, city, datacenter_name) VALUES (?, 'US', 'New York', 'DC1')",
                providerId,
            )
            connection.assertInsertFails(
                "uq_location_provider_country_city_dc",
                "INSERT INTO location (provider_id, country_code, city, datacenter_name) VALUES (?, 'US', 'new york', 'dc1')",
                providerId,
            )
        }
    }

    @Test
    fun `tariff vcpu_count must be positive`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-vcpu")
            connection.assertInsertFails(
                "ck_tariff_vcpu_count_positive",
                """
                INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                VALUES (?, 'bad-vcpu', 0, 512, 10, 'SSD', 'KVM')
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `tariff ram_mb must be positive`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-ram")
            connection.assertInsertFails(
                "ck_tariff_ram_mb_positive",
                """
                INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                VALUES (?, 'bad-ram', 1, 0, 10, 'SSD', 'KVM')
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `tariff disk_gb must be positive`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-disk-gb")
            connection.assertInsertFails(
                "ck_tariff_disk_gb_positive",
                """
                INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                VALUES (?, 'bad-disk-gb', 1, 512, 0, 'SSD', 'KVM')
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `tariff ipv4_count must be non-negative`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-ipv4")
            connection.assertInsertFails(
                "ck_tariff_ipv4_count_non_negative",
                """
                INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization, ipv4_count)
                VALUES (?, 'bad-ipv4', 1, 512, 10, 'SSD', 'KVM', -1)
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `tariff name must be unique per provider case-insensitively`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "dup-tariff-name")
            newTariff(connection, providerId, "Same Name")
            connection.assertInsertFails(
                "uq_tariff_provider_name",
                """
                INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                VALUES (?, 'same name', 1, 512, 10, 'SSD', 'KVM')
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `tariff external_id must be unique per provider when present`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "dup-tariff-external-id")
            connection.exec(
                """
                INSERT INTO tariff (provider_id, name, external_id, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                VALUES (?, 'tariff-a', 'EXT-1', 1, 512, 10, 'SSD', 'KVM')
                """.trimIndent(),
                providerId,
            )
            connection.assertInsertFails(
                "uq_tariff_provider_external_id",
                """
                INSERT INTO tariff (provider_id, name, external_id, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                VALUES (?, 'tariff-b', 'EXT-1', 1, 512, 10, 'SSD', 'KVM')
                """.trimIndent(),
                providerId,
            )
        }
    }

    @Test
    fun `tariff_price amount must be non-negative`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-price-amount")
            val tariffId = newTariff(connection, providerId, "bad-price-amount-tariff")
            connection.assertInsertFails(
                "ck_tariff_price_amount_non_negative",
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'MONTHLY', -1.0000, 'USD', ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now,
            )
        }
    }

    @Test
    fun `tariff_price currency must be three uppercase letters`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-price-currency")
            val tariffId = newTariff(connection, providerId, "bad-price-currency-tariff")
            connection.assertInsertFails(
                "ck_tariff_price_currency",
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source)
                VALUES (?, 'MONTHLY', 10.0000, 'usd', ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now,
            )
        }
    }

    @Test
    fun `tariff_price effective_to must be after effective_from`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val providerId = newProvider(connection, "bad-price-effective")
            val tariffId = newTariff(connection, providerId, "bad-price-effective-tariff")
            connection.assertInsertFails(
                "ck_tariff_price_effective_to_after_from",
                """
                INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, effective_to, data_source)
                VALUES (?, 'MONTHLY', 10.0000, 'USD', ?, ?, 'MANUAL')
                """.trimIndent(),
                tariffId,
                now,
                now.minusDays(1),
            )
        }
    }

    @Test
    fun `app_user username must be unique`() {
        PostgresSupport.dataSource.connection.use { connection ->
            newUser(connection, "dup-user")
            connection.assertInsertFails(
                "uq_app_user_username",
                "INSERT INTO app_user (username, password_hash, status) VALUES ('dup-user', 'hash2', 'ACTIVE')",
            )
        }
    }

    // ---- 12. currency_rate -- zero rows were ever inserted here before this fix -----------

    private fun newCurrencyRate(
        connection: Connection,
        currency: String = "USD",
        source: String = "test-source",
        sourceRateAt: OffsetDateTime = now,
    ): Long =
        connection.insertId(
            "INSERT INTO currency_rate (currency, rate_to_usd, source_rate_at, fetched_at, source) VALUES (?, 1.000000000000, ?, ?, ?) RETURNING id",
            currency,
            sourceRateAt,
            now,
            source,
        )

    @Test
    fun `currency_rate accepts a valid row and rejects an invalid currency, a non-positive rate, and a duplicate currency-source-timestamp`() {
        PostgresSupport.dataSource.connection.use { connection ->
            val id = newCurrencyRate(connection, currency = "EUR", source = "ecb")
            assertEquals(1L, connection.scalarLong("SELECT count(*) FROM currency_rate WHERE id = ?", id))

            connection.assertInsertFails(
                "ck_currency_rate_currency",
                "INSERT INTO currency_rate (currency, rate_to_usd, source_rate_at, fetched_at, source) VALUES ('eur', 1.0, ?, ?, 'ecb-2')",
                now,
                now,
            )
            connection.assertInsertFails(
                "ck_currency_rate_rate_to_usd_positive",
                "INSERT INTO currency_rate (currency, rate_to_usd, source_rate_at, fetched_at, source) VALUES ('GBP', 0, ?, ?, 'ecb-2')",
                now,
                now,
            )
            // Same (currency, source, source_rate_at) as the row inserted above -- a different
            // rate_to_usd does not exempt it from the unique constraint.
            connection.assertInsertFails(
                "uq_currency_rate_currency_source_rate_at",
                "INSERT INTO currency_rate (currency, rate_to_usd, source_rate_at, fetched_at, source) VALUES ('EUR', 1.1, ?, ?, 'ecb')",
                now,
                now,
            )
        }
    }
}
