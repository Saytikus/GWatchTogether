package com.saytikus.gwatchtogether.domain.result

/** Executes a boundary operation without converting coroutine cancellation into a domain failure. */
suspend fun <T> executeCancellable(operation: suspend () -> MbResult<T>): MbResult<T> = operation()
