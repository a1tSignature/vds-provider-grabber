package org.a1tsign.vds.domain

import java.math.BigDecimal
import java.math.RoundingMode

internal const val MONTHS_PER_YEAR = 12
internal const val USD_AMOUNT_SCALE = 4

internal fun BigDecimal.annualUsdPerMonth(): BigDecimal =
    divide(BigDecimal.valueOf(MONTHS_PER_YEAR.toLong()), USD_AMOUNT_SCALE, RoundingMode.HALF_UP)
