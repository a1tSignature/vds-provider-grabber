package org.a1tsign.vds

import javax.sql.DataSource
import org.a1tsign.vds.config.AppConfig
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
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.service.TariffService
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.service.PriceService
import org.a1tsign.vds.db.CurrencyRateRepository
import org.a1tsign.vds.fx.CurrencyConverter
import org.a1tsign.vds.fx.CurrencyRateProvider
import org.a1tsign.vds.fx.ErApiCurrencyRateProvider
import org.a1tsign.vds.fx.FxRefreshService
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.CrawlerRunner
import org.a1tsign.vds.crawler.SnapshotValidator
import org.a1tsign.vds.crawler.fetch.KtorPageFetcher
import org.a1tsign.vds.crawler.fetch.crawlerHttpClient
import org.a1tsign.vds.crawler.write.ApiCrawlWriter
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.DryRunWriter
import org.a1tsign.vds.crawler.write.Reconciler
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

/**
 * The one holder every route group is wired from. Later tasks add their repository/service to it
 * and read it in [registerRoutes] -- so neither `Application.kt` nor `api/Routing.kt` grows a new
 * parameter per endpoint group.
 */
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

    // ---------- vds-002 crawler, all null together while VDS_CRAWLER_ENABLED is false ----------
    // (ADR-01: no client is created and no thread is started when the crawler is disabled).
    var crawlerRegistry: AdapterRegistry? = null
        private set
    var crawlerRunner: CrawlerRunner? = null
        private set

    /** The scope `api/routes/CrawlerRoutes.kt` launches an admin-triggered run on; cancelled on shutdown. */
    var crawlerScope: CoroutineScope? = null
        private set

    /** The crawler's loopback API client; closed on shutdown, same precedent as [fxHttpClient]. */
    var crawlerCatalogClient: CatalogApiClient? = null
        private set

    /** The outbound `HttpClient` behind [crawlerRegistry]'s `PageFetcher`; closed on shutdown. */
    var crawlerFetchHttpClient: HttpClient? = null
        private set

    /**
     * The `Job` of the currently in-flight admin-triggered run (`POST /api/v1/admin/crawl/run`),
     * set by `api/routes/CrawlerRoutes.kt` right after `launch`. `Application.kt`'s
     * `shutdownGracefully` joins it (bounded, like [org.a1tsign.vds.crawler.CrawlScheduler.close])
     * before cancelling [crawlerScope] -- unlike the scheduler's own ticks, nothing else awaited an
     * admin-triggered run before task vds-002.7. Always present, even while the crawler is disabled
     * (it simply never gets set then).
     */
    val crawlerAdminRunJob: AtomicReference<Job?> = AtomicReference(null)

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
    val providerService = ProviderService(database, providerRepository, clock)
    val catalogQueryService = CatalogQueryService(database, providerRepository, TariffRepository(), PriceRepository())
    val locationRepository = LocationRepository()
    val locationService = LocationService(database, locationRepository, ProviderRepository(), clock)
    val tariffService = TariffService(database, TariffRepository(), ProviderRepository(), locationRepository, clock)
    val currencyRateRepository = CurrencyRateRepository()
    val fxProvider = currencyRateProvider ?: ErApiCurrencyRateProvider(config)
    val currencyConverter = CurrencyConverter(currencyRateRepository)
    val priceService = PriceService(database, PriceRepository(), currencyConverter, clock)
    val fxRefreshService = FxRefreshService(fxProvider, database, currencyRateRepository, currencyConverter, clock)
    val fxClient = (fxProvider as? ErApiCurrencyRateProvider)?.client

    // ---------- vds-002 crawler: nothing is constructed, no client, no thread, while disabled ----------
    var crawlerRegistry: AdapterRegistry? = null
    var crawlerRunner: CrawlerRunner? = null
    var crawlerScope: CoroutineScope? = null
    var crawlerCatalogClient: CatalogApiClient? = null
    var crawlerFetchHttpClient: HttpClient? = null
    if (config.crawler.enabled) {
        if (!config.crawler.respectRobots) {
            logger.warn(
                "VDS_CRAWLER_RESPECT_ROBOTS is false: this crawler will not honour robots.txt on the sites it fetches",
            )
        }
        val registry = AdapterRegistry.production()
        // Fail-fast (spec req. 39): an unknown id in VDS_CRAWLER_ADAPTERS must abort startup, not
        // fail quietly on the scheduler's first tick minutes later -- see Application.kt's main().
        registry.select(config.crawler.adapters)
        val fetchClient = crawlerHttpClient()
        val fetcher = KtorPageFetcher(config.crawler, clock, fetchClient)
        val catalogClient = CatalogApiClient(config.crawler, clock)
        val reconciler = Reconciler(catalogClient)
        val writerFactory: (Boolean) -> CrawlWriter = { dryRun -> if (dryRun) DryRunWriter() else ApiCrawlWriter(catalogClient) }
        crawlerRunner = CrawlerRunner(
            config = config.crawler,
            registry = registry,
            fetcher = fetcher,
            validator = SnapshotValidator(clock),
            reconciler = reconciler,
            clock = clock,
            writerFactory = writerFactory,
            pagesFetched = { fetcher.pagesFetched },
        )
        crawlerRegistry = registry
        crawlerFetchHttpClient = fetchClient
        crawlerCatalogClient = catalogClient
        crawlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    return AppComponents(dataSource).apply {
        configure(
            config, database, clock, repository, jwt, passwordHasher, authService, userService, providerService, locationService,
            tariffService, priceService, catalogQueryService, currencyRateRepository, currencyConverter, fxRefreshService, fxClient,
            crawlerRegistry, crawlerRunner, crawlerScope, crawlerCatalogClient, crawlerFetchHttpClient,
        )
    }
}
