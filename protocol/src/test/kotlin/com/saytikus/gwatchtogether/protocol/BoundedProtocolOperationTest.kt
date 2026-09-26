package com.saytikus.gwatchtogether.protocol

import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BoundedProtocolOperationTest {
    @Test
    fun deadlineYieldsTerminalFailureAndCancelsWork() =
        runTest {
            var finished = false
            val result =
                withProtocolDeadline(100) {
                    try {
                        delay(1_000)
                        MbResult.Success("late")
                    } finally {
                        finished = true
                    }
                }
            assertEquals(
                DomainError.Protocol("operation_deadline_exceeded"),
                assertIs<MbResult.Failure>(result).error.error,
            )
            assertEquals(true, finished)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun parentCancellationPropagates() =
        runTest {
            val pending = async { withProtocolDeadline<Unit>(30_000) { awaitCancellation() } }
            runCurrent()
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
        }

    @Test
    fun invalidDeadlineDoesNotInvokeOperation() =
        runTest {
            var invoked = false
            val result =
                withProtocolDeadline(0) {
                    invoked = true
                    MbResult.Success(Unit)
                }
            assertEquals(DomainError.Protocol("invalid_deadline"), assertIs<MbResult.Failure>(result).error.error)
            assertEquals(false, invoked)
        }
}
