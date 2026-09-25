package com.saytikus.gwatchtogether.domain.result

import com.saytikus.gwatchtogether.platform.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MbResultTest {
    @Test
    fun successAndFailureComposeWithoutUntypedErrors() {
        val success: MbResult<Int> = MbResult.Success(2)
        assertEquals(6, success.map { it * 3 }.fold({ it }, { error("unexpected $it") }))

        val failure =
            MbResult.Failure(
                MbError(
                    error = DomainError.Connectivity("offline"),
                    retryability = Retryability.AfterBackoff,
                    diagnosticId = DiagnosticId("diagnostic.connection"),
                ),
            )
        val mapped = failure.map { "not reached" }
        assertIs<MbResult.Failure>(mapped)
        assertEquals("offline", (mapped.error.error as DomainError.Connectivity).code)
        assertEquals(Retryability.AfterBackoff, mapped.error.retryability)
    }

    @Test
    fun fixedClockKeepsEpochAndMonotonicDomainsIndependent() {
        val clock = FixedClock(epochMillisValue = 1_700_000_000_000, monotonicNanosValue = 42_000)
        assertEquals(1_700_000_000_000, clock.epochMillis())
        assertEquals(42_000, clock.monotonicNanos())
        assertTrue(clock.epochMillis() > clock.monotonicNanos())
    }
}
