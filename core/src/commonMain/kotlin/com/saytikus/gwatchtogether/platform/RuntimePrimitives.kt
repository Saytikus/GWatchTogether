package com.saytikus.gwatchtogether.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Process and monotonic readings are injected so operation tests do not depend on wall-clock timing. */
interface IClock {
    fun epochMillis(): Long

    fun monotonicNanos(): Long
}

interface ICoroutineDispatchers {
    val default: CoroutineDispatcher

    val io: CoroutineDispatcher
}

data class FixedClock(
    private val epochMillisValue: Long,
    private val monotonicNanosValue: Long,
) : IClock {
    override fun epochMillis(): Long = epochMillisValue

    override fun monotonicNanos(): Long = monotonicNanosValue
}
