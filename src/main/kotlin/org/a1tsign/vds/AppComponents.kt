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
import io.ktor.client.HttpClient
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock

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
    return AppComponents(dataSource).apply {
        configure(config, database, clock, repository, jwt, passwordHasher, authService, userService, providerService, locationService,
            tariffService, priceService, catalogQueryService, currencyRateRepository, currencyConverter, fxRefreshService, fxClient)
    }
}
