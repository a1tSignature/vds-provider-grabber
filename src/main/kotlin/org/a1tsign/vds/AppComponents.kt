package org.a1tsign.vds

import javax.sql.DataSource

/**
 * The one holder every route group is wired from. Later tasks add their repository/service to it
 * and read it in [registerRoutes] -- so neither `Application.kt` nor `api/Routing.kt` grows a new
 * parameter per endpoint group.
 */
class AppComponents(
    val dataSource: DataSource,
)
