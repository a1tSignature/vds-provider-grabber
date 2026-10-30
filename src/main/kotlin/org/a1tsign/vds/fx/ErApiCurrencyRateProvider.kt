package org.a1tsign.vds.fx

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.api.parsePlainDecimal
import org.a1tsign.vds.config.AppConfig
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

/** Long-lived CIO client owned by AppComponents and closed with the application. */
class ErApiCurrencyRateProvider(
    config: AppConfig,
    val client: HttpClient = HttpClient(CIO) {
        expectSuccess = true
        install(HttpRequestRetry) {
            retryOnException(maxRetries = 1)
            retryOnServerErrors(maxRetries = 1)
            delayMillis { 0L }
        }
        engine {
            requestTimeout = 5_000
            endpoint.connectTimeout = 3_000
            endpoint.socketTimeout = 5_000
            endpoint.connectAttempts = 1
        }
    },
) : CurrencyRateProvider {
    private val apiUrl = config.fx.apiUrl
    private val apiKey = config.fx.apiKey

    override suspend fun fetchRates(): FetchedRates {
        val responseText = client.get(apiUrl) {
            configureRequest(this)
            timeout {
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 5_000
                requestTimeoutMillis = 5_000
            }
        }.bodyAsText()
        return parseResponse(responseText)
    }

    internal fun configureRequest(request: HttpRequestBuilder) {
        val configuredKey = apiKey ?: return
        val host = URLBuilder(apiUrl).host.lowercase()
        if (host == KEYED_API_HOST) {
            request.headers.append(HttpHeaders.Authorization, "Bearer ${configuredKey.reveal()}")
        }
    }

    internal fun parseResponse(responseText: String): FetchedRates {
        val root = Json.parseToJsonElement(responseText).jsonObject
        val result = root["result"]?.jsonPrimitive?.content
        if (result != "success") throw IllegalStateException("Open ER API returned result=$result")
        val updatedAt = root["time_last_update_unix"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalStateException("Open ER API response omitted its update time")
        val rawRates = root["rates"]?.jsonObject
            ?: throw IllegalStateException("Open ER API response omitted rates")
        val parsedRates = mutableMapOf<String, BigDecimal>()
        rawRates.forEach { (currency, raw) ->
            val decimal = (raw as? JsonPrimitive)?.content?.let(::parsePlainDecimal)
            if (decimal == null || decimal.signum() <= 0) {
                logger.warn("Skipping invalid FX rate for {} from {}", currency, SOURCE)
            } else if (currency != "USD") {
                parsedRates[currency] = decimal
            }
        }
        return FetchedRates(SOURCE, Instant.ofEpochSecond(updatedAt), parsedRates)
    }

    companion object {
        const val SOURCE = "open.er-api.com"
        private const val KEYED_API_HOST = "v6.exchangerate-api.com"
        private val logger = LoggerFactory.getLogger(ErApiCurrencyRateProvider::class.java)
    }
}
