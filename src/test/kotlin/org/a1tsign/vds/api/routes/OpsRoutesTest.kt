package org.a1tsign.vds.api.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises `GET /health/ready` end to end through the real Ktor routing (not by calling the
 * handler's internals directly), against both an UP and a DOWN database -- previously untested,
 * see the batch-review findings this fixes.
 */
class OpsRoutesTest : AbstractDbTest() {
    @Test
    fun `health ready returns 200 UP when the database answers`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { opsRoutes(PostgresSupport.dataSource) }
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"UP\""))
    }

    @Test
    fun `health ready returns 503 DOWN when the database is unreachable`() = testApplication {
        // Use an unused port and a timeout just above Hikari's 250 ms floor.
        val unreachableDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://localhost:1/nonexistent"
                username = "nonexistent"
                password = "nonexistent"
                maximumPoolSize = 1
                minimumIdle = 0
                connectionTimeout = 300
                initializationFailTimeout = -1
            },
        )
        try {
            application {
                install(ContentNegotiation) { json() }
                routing { opsRoutes(unreachableDataSource) }
            }

            val response = client.get("/health/ready")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"DOWN\""))
            assertTrue(body.contains("database unavailable"))
        } finally {
            unreachableDataSource.close()
        }
    }
}
