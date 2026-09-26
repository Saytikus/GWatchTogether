package com.saytikus.gwatchtogether.protocol

import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Applies a deadline to cooperative suspend work; it cannot preempt blocking or cancellation-insensitive operations.
 * Callers must choose a deadline and ensure transport work promptly honors scope cancellation. A timeout is not
 * automatically retryable: the remote side may already have committed a mutation.
 */
suspend fun <T> withProtocolDeadline(
    deadlineMillis: Long,
    operation: suspend () -> MbResult<T>,
): MbResult<T> {
    if (deadlineMillis !in 1..30_000) return protocolFailure("invalid_deadline")
    return withTimeoutOrNull(deadlineMillis) { operation() } ?: protocolFailure("operation_deadline_exceeded")
}

private fun protocolFailure(code: String) =
    MbResult.Failure(
        MbError(
            error = DomainError.Protocol(code),
            retryability = Retryability.Never,
            diagnosticId = null,
        ),
    )
