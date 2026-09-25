package com.saytikus.gwatchtogether.domain.result

/** A typed success/failure boundary. Cancellation is intentionally not represented as a failure value. */
sealed class MbResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MbResult<T>()

    data class Failure(
        val error: MbError,
    ) : MbResult<Nothing>()

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (MbError) -> R,
    ): R =
        when (this) {
            is Success -> onSuccess(value)
            is Failure -> onFailure(error)
        }

    inline fun <R> map(transform: (T) -> R): MbResult<R> =
        when (this) {
            is Success -> Success(transform(value))
            is Failure -> this
        }

    inline fun <R> flatMap(transform: (T) -> MbResult<R>): MbResult<R> =
        when (this) {
            is Success -> transform(value)
            is Failure -> this
        }

    inline fun onSuccess(action: (T) -> Unit): MbResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (MbError) -> Unit): MbResult<T> {
        if (this is Failure) action(error)
        return this
    }
}
