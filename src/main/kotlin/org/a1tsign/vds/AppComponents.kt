package org.a1tsign.vds

import javax.sql.DataSource
import org.a1tsign.vds.config.AppConfig
import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.config.crawlerAdapterConfig
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.auth.JwtService
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.auth.AuthService
import org.a1tsign.vds.auth.LoginThrottle
import org.a1tsign.vds.service.UserService
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.service.ProviderService
import org.a1tsign.vds.service.CatalogQueryService
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.service.LocationService
import org.a1tsign.vds.service.EnrichmentService
import org.a1tsign.vds.db.EnrichmentRepository
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.service.TariffService
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.service.PriceService
import org.a1tsign.vds.db.CurrencyRateRepository
import org.a1tsign.vds.fx.CurrencyConverter
import org.a1tsign.vds.fx.CurrencyRateProvider
import org.a1tsign.vds.fx.ErApiCurrencyRateProvider
import org.a1tsign.vds.fx.FxRefreshService
import org.a1tsign.vds.crawler.AdapterEntry
import org.a1tsign.vds.crawler.AdapterOrigin
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.CrawlerRunner
import org.a1tsign.vds.crawler.RunSlot
import org.a1tsign.vds.crawler.SnapshotValidator
import org.a1tsign.vds.crawler.adapters.DeclarativeAdapter
import org.a1tsign.vds.crawler.config.SourceConfigLoader
import org.a1tsign.vds.crawler.config.SourceLoadReport
import org.a1tsign.vds.crawler.fetch.KtorPageFetcher
import org.a1tsign.vds.crawler.fetch.crawlerHttpClient
import org.a1tsign.vds.crawler.write.ApiCrawlWriter
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.DryRunWriter
import org.a1tsign.vds.crawler.write.Reconciler
import org.a1tsign.vds.config.DiscoverySearchProvider
import org.a1tsign.vds.discovery.ApiCandidateCatalog
import org.a1tsign.vds.discovery.CreditLedger
import org.a1tsign.vds.discovery.DiscoveryRunner
import org.a1tsign.vds.discovery.SearchProvider
import org.a1tsign.vds.discovery.TavilySearchProvider
import org.a1tsign.vds.discovery.canonicalDomains
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.AppComponents")

class AppComponents(val dataSource: DataSource) {
    lateinit var config: AppConfig
        private set
    lateinit var database: Database
        private set
    lateinit var clock: Clock
        private set
    lateinit var userRepository: UserRepository
        private set
    lateinit var jwtService: JwtService
        private set
    lateinit var passwordHasher: PasswordHasher
        private set
    lateinit var authService: AuthService
        private set
    lateinit var userService: UserService
        private set
    lateinit var providerService: ProviderService
        private set
    lateinit var locationService: LocationService
        private set
    lateinit var enrichmentService: EnrichmentService
        private set
    lateinit var tariffService: TariffService
        private set
    lateinit var priceService: PriceService
        private set
    lateinit var catalogQueryService: CatalogQueryService
        private set
    lateinit var currencyRateRepository: CurrencyRateRepository
        private set
    lateinit var currencyConverter: CurrencyConverter
        private set
    lateinit var fxRefreshService: FxRefreshService
        private set
    var fxHttpClient: HttpClient? = null
        private set

    // All crawler components are absent while the crawler is disabled.
    var crawlerRegistry: AdapterRegistry? = null
        private set
    var crawlerRunner: CrawlerRunner? = null
        private set

    var crawlerScope: CoroutineScope? = null
        private set

    var crawlerCatalogClient: CatalogApiClient? = null
        private set

    var crawlerFetchHttpClient: HttpClient? = null
        private set

    /**
     * Startup source-load report, including file errors. It is fixed until restart.
     * [SourceLoadReport.NOT_LOADED] means the crawler is disabled or no source directory was configured.
     */
    var discoverySources: SourceLoadReport = SourceLoadReport.NOT_LOADED
        private set

    /** Shutdown waits a bounded time for this admin run before cancelling [crawlerScope]. */
    val crawlerAdminRunJob: AtomicReference<Job?> = AtomicReference(null)

    // Discovery starts no search client or thread when disabled.
    /** `null` also while enabled when `VDS_DISCOVERY_SEARCH_PROVIDER=none`. */
    var discoverySearchProvider: SearchProvider? = null
        private set

    var discoverySearchHttpClient: HttpClient? = null
        private set

    var discoveryLedger: CreditLedger? = null
        private set

    var discoveryRunner: DiscoveryRunner? = null
        private set

    var discoveryScope: CoroutineScope? = null
        private set

    /** Shutdown joins this admin pass with a bounded wait. */
    val discoveryAdminPassJob: AtomicReference<Job?> = AtomicReference(null)

    val isConfigured: Boolean get() = this::authService.isInitialized

    internal fun configure(
        config: AppConfig,
        database: Database,
        clock: Clock,
        userRepository: UserRepository,
        jwtService: JwtService,
        passwordHasher: PasswordHasher,
        authService: AuthService,
        userService: UserService,
        providerService: ProviderService,
        locationService: LocationService,
        enrichmentService: EnrichmentService,
        tariffService: TariffService,
        priceService: PriceService,
        catalogQueryService: CatalogQueryService,
        currencyRateRepository: CurrencyRateRepository,
        currencyConverter: CurrencyConverter,
        fxRefreshService: FxRefreshService,
        fxHttpClient: HttpClient?,
        crawlerRegistry: AdapterRegistry?,
        crawlerRunner: CrawlerRunner?,
        crawlerScope: CoroutineScope?,
        crawlerCatalogClient: CatalogApiClient?,
        crawlerFetchHttpClient: HttpClient?,
        discoverySources: SourceLoadReport,
        discoverySearchProvider: SearchProvider?,
        discoverySearchHttpClient: HttpClient?,
        discoveryLedger: CreditLedger?,
        discoveryRunner: DiscoveryRunner?,
        discoveryScope: CoroutineScope?,
    ) {
        this.config = config
        this.database = database
        this.clock = clock
        this.userRepository = userRepository
        this.jwtService = jwtService
        this.passwordHasher = passwordHasher
        this.authService = authService
        this.userService = userService
        this.providerService = providerService
        this.locationService = locationService
        this.enrichmentService = enrichmentService
        this.tariffService = tariffService
        this.priceService = priceService
        this.catalogQueryService = catalogQueryService
        this.currencyRateRepository = currencyRateRepository
        this.currencyConverter = currencyConverter
        this.fxRefreshService = fxRefreshService
        this.fxHttpClient = fxHttpClient
        this.crawlerRegistry = crawlerRegistry
        this.crawlerRunner = crawlerRunner
        this.crawlerScope = crawlerScope
        this.crawlerCatalogClient = crawlerCatalogClient
        this.crawlerFetchHttpClient = crawlerFetchHttpClient
        this.discoverySources = discoverySources
        this.discoverySearchProvider = discoverySearchProvider
        this.discoverySearchHttpClient = discoverySearchHttpClient
        this.discoveryLedger = discoveryLedger
        this.discoveryRunner = discoveryRunner
        this.discoveryScope = discoveryScope
    }
}

fun buildComponents(
    config: AppConfig,
    dataSource: DataSource,
    clock: Clock,
    database: Database,
    passwordHasher: PasswordHasher = PasswordHasher(),
    currencyRateProvider: CurrencyRateProvider? = null,
): AppComponents {
    val repository = UserRepository()
    val jwt = JwtService(config.jwt, clock)
    val authService = AuthService(database, repository, jwt, passwordHasher, clock, LoginThrottle(clock))
    val userService = UserService(database, repository, passwordHasher, clock)
    val providerRepository = ProviderRepository()
    val enrichmentRepository = EnrichmentRepository()
    val providerService = ProviderService(database, providerRepository, clock, enrichmentRepository)
    val catalogQueryService = CatalogQueryService(database, providerRepository, TariffRepository(), PriceRepository(), enrichmentRepository)
    val locationRepository = LocationRepository()
    val locationService = LocationService(database, locationRepository, ProviderRepository(), clock, enrichmentRepository)
    val enrichmentService = EnrichmentService(database, providerRepository, locationRepository, enrichmentRepository, clock)
    val tariffService = TariffService(database, TariffRepository(), ProviderRepository(), locationRepository, clock)
    val currencyRateRepository = CurrencyRateRepository()
    val fxProvider = currencyRateProvider ?: ErApiCurrencyRateProvider(config)
    val currencyConverter = CurrencyConverter(currencyRateRepository)
    val priceService = PriceService(database, PriceRepository(), currencyConverter, clock)
    val fxRefreshService = FxRefreshService(fxProvider, database, currencyRateRepository, currencyConverter, clock)
    val fxClient = (fxProvider as? ErApiCurrencyRateProvider)?.client

    var crawlerRegistry: AdapterRegistry? = null
    var crawlerRunner: CrawlerRunner? = null
    var crawlerScope: CoroutineScope? = null
    var crawlerCatalogClient: CatalogApiClient? = null
    var crawlerFetchHttpClient: HttpClient? = null
    var discoverySources = SourceLoadReport.NOT_LOADED
    var discoverySearchProvider: SearchProvider? = null
    var discoverySearchHttpClient: HttpClient? = null
    var discoveryLedger: CreditLedger? = null
    var discoveryRunner: DiscoveryRunner? = null
    var discoveryScope: CoroutineScope? = null
    if (config.discovery.enabled && !config.crawler.enabled) {
        // Discovery shares the crawler's service account and run slot. Without the crawler, discovery endpoints return 503.
        logger.warn(
            "VDS_DISCOVERY_ENABLED is true but VDS_CRAWLER_ENABLED is false: discovery needs the crawler's " +
                "service account and run slot, so it is not started",
        )
    }
    if (config.crawler.enabled) {
        if (!config.crawler.respectRobots) {
            logger.warn(
                "VDS_CRAWLER_RESPECT_ROBOTS is false: this crawler will not honour robots.txt on the sites it fetches",
            )
        }
        val compiledEntries = AdapterRegistry.compiledEntries()
        // Load declarative sources whenever the crawler and a source directory are configured, independently of search.
        val sourcesDir = config.discovery.sourcesDir
        if (sourcesDir != null) {
            discoverySources = SourceConfigLoader(clock).load(
                directory = sourcesDir,
                maxSources = config.discovery.maxSources,
                // Reserve compiled adapter IDs so a JSON source cannot shadow one.
                reservedIds = compiledEntries.map { it.adapter.id }.toSet(),
                reservedSlugs = compiledEntries.map { it.adapter.providerSlug }.toSet(),
            )
            if (config.discovery.sourcesStrict) {
                if (config.discovery.enabled) {
                    if (discoverySources.hasFailures) {
                        throw ConfigurationException(
                            "VDS_DISCOVERY_SOURCES_STRICT is true and a declarative source was refused: " +
                                discoverySources.failureSummary(),
                        )
                    }
                } else {
                    // Warn about ignored strict mode without failing startup while discovery is disabled.
                    logger.warn(
                        "VDS_DISCOVERY_SOURCES_STRICT is true but VDS_DISCOVERY_ENABLED is false, " +
                            "so strict mode was not applied: an invalid source file is skipped, not a startup failure",
                    )
                }
            }
        }
        val registry = AdapterRegistry.production(
            // A source's enabled flag determines whether an empty adapter selection includes it.
            discoverySources.loaded.map { source ->
                AdapterEntry(
                    adapter = DeclarativeAdapter(source.config),
                    includedByDefault = source.config.enabled,
                    origin = AdapterOrigin.CONFIG,
                    file = source.file,
                )
            },
        )
        // Validate adapter IDs before startup so an invalid selection cannot first fail on a scheduler tick.
        registry.select(config.crawler.adapters)
        val fetchClient = crawlerHttpClient()
        val fetcher = KtorPageFetcher(config.crawler, clock, fetchClient)
        val catalogClient = CatalogApiClient(config.crawler, clock)
        val reconciler = Reconciler(catalogClient)
        val writerFactory: (Boolean) -> CrawlWriter = { dryRun -> if (dryRun) DryRunWriter() else ApiCrawlWriter(catalogClient) }
        // One slot for a crawl run and a discovery pass: they are never in flight together.
        val runSlot = RunSlot()
        crawlerRunner = CrawlerRunner(
            config = config.crawler,
            registry = registry,
            fetcher = fetcher,
            validator = SnapshotValidator(clock),
            reconciler = reconciler,
            clock = clock,
            writerFactory = writerFactory,
            // Give each adapter its own normalized environment slice as CrawlContext.config.
            adapterConfig = { adapterId -> crawlerAdapterConfig(adapterId, config.crawler.adapterEnv.reveal()) },
            pagesFetched = { fetcher.pagesFetched },
            slot = runSlot,
        )
        crawlerRegistry = registry
        crawlerFetchHttpClient = fetchClient
        crawlerCatalogClient = catalogClient
        crawlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        if (config.discovery.enabled) {
            val discovery = config.discovery
            if (discovery.searchProvider == DiscoverySearchProvider.TAVILY) {
                // loadConfig refuses TAVILY without a key while discovery is enabled.
                val tavily = TavilySearchProvider(
                    endpoint = discovery.searchEndpoint,
                    apiKey = checkNotNull(discovery.searchApiKey) { "VDS_DISCOVERY_SEARCH_API_KEY is required" },
                    requestTimeoutMs = TimeUnit.SECONDS.toMillis(discovery.searchTimeoutSeconds),
                    maxResponseBytes = discovery.maxResponseBytes,
                    excludeDomains = canonicalDomains(discovery.domainDenylist),
                    retryRateLimit = discovery.retryRateLimit,
                )
                discoverySearchProvider = tavily
                discoverySearchHttpClient = tavily.client
            }
            val ledger = CreditLedger(
                path = Path.of(checkNotNull(discovery.stateFile) { "VDS_DISCOVERY_STATE_FILE is required" }),
                clock = clock,
                maxCreditsPerMonth = discovery.maxCreditsPerMonth,
                maxQueriesPerPass = discovery.maxQueriesPerPass,
                minPassIntervalHours = discovery.minPassIntervalHours,
            )
            discoveryLedger = ledger
            discoveryRunner = DiscoveryRunner(
                config = discovery,
                searchProvider = discoverySearchProvider,
                ledger = ledger,
                catalog = ApiCandidateCatalog(catalogClient),
                clock = clock,
                slot = runSlot,
            )
            discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }

    return AppComponents(dataSource).apply {
        configure(
            config, database, clock, repository, jwt, passwordHasher, authService, userService, providerService, locationService, enrichmentService,
            tariffService, priceService, catalogQueryService, currencyRateRepository, currencyConverter, fxRefreshService, fxClient,
            crawlerRegistry, crawlerRunner, crawlerScope, crawlerCatalogClient, crawlerFetchHttpClient,
            discoverySources, discoverySearchProvider, discoverySearchHttpClient, discoveryLedger, discoveryRunner, discoveryScope,
        )
    }
}
