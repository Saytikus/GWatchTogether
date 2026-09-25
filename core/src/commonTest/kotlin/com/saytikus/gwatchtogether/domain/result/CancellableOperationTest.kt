package com.saytikus.gwatchtogether.domain.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CancellableOperationTest {
    @Test
    fun cancellationIsNotConvertedToFailure() =
        runTest {
            assertFailsWith<CancellationException> {
                executeCancellable<Int> {
                    throw CancellationException("test cancellation")
                }
            }
        }
}
