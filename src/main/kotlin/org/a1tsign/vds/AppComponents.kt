package org.a1tsign.vds

import javax.sql.DataSource
import org.a1tsign.vds.config.AppConfig
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.auth.JwtService
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.auth.AuthService
import org.a1tsign.vds.auth.LoginThrottle
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

    val isConfigured: Boolean get() = this::authService.isInitialized

    internal fun configure(
        config: AppConfig,
        database: Database,
        clock: Clock,
        userRepository: UserRepository,
        jwtService: JwtService,
        passwordHasher: PasswordHasher,
        authService: AuthService,
    ) {
        this.config = config
        this.database = database
        this.clock = clock
        this.userRepository = userRepository
        this.jwtService = jwtService
        this.passwordHasher = passwordHasher
        this.authService = authService
    }
}

fun buildComponents(
    config: AppConfig,
    dataSource: DataSource,
    clock: Clock,
    database: Database,
    passwordHasher: PasswordHasher = PasswordHasher(),
): AppComponents {
    val repository = UserRepository()
    val jwt = JwtService(config.jwt, clock)
    val authService = AuthService(database, repository, jwt, passwordHasher, clock, LoginThrottle(clock))
    return AppComponents(dataSource).apply {
        configure(config, database, clock, repository, jwt, passwordHasher, authService)
    }
}
