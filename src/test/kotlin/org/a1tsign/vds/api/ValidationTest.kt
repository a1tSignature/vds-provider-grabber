package org.a1tsign.vds.api

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ValidationTest {
    @Test
    fun coordinateWithTrailingZerosWithinSixSignificantFractionalDigitsIsAccepted() {
        val v = Validation()
        v.validateLocationCoordinate("latitude", "50.1109000", BigDecimal("-90"), BigDecimal("90"))
        v.throwIfInvalid() // must not throw: 50.1109000 has only 4 significant fractional digits
    }

    @Test
    fun coordinateWithMoreThanSixSignificantFractionalDigitsIsRejected() {
        val v = Validation()
        val parsed = v.validateLocationCoordinate("latitude", "1.1234567", BigDecimal("-90"), BigDecimal("90"))
        assertNull(parsed)
        assertFailsWith<ValidationException> { v.throwIfInvalid() }
    }

    @Test
    fun coordinatePairAcceptsBothRawFieldsEvenWhenOneFailedItsOwnValidation() {
        // longitude is individually out of range (validates to null), but both fields were
        // supplied -- the pair check must look at the raw request fields, not the validated
        // outcome, or it would wrongly ALSO report "must be supplied together".
        val v = Validation()
        val longitude = v.validateLocationCoordinate("longitude", "200", BigDecimal("-180"), BigDecimal("180"))
        assertNull(longitude)
        v.validateCoordinatePair(BigDecimal("1"), BigDecimal("200"))
        val failure = assertFailsWith<ValidationException> { v.throwIfInvalid() }
        assertNull(failure.details?.firstOrNull { it.message.contains("supplied together") })
    }

    @Test
    fun coordinatePairRejectsOneRawFieldSuppliedWithoutTheOther() {
        val v = Validation()
        v.validateCoordinatePair(BigDecimal("1"), null)
        val failure = assertFailsWith<ValidationException> { v.throwIfInvalid() }
        assertEquals(setOf("latitude", "longitude"), failure.details?.map { it.field }?.toSet())
    }
}
