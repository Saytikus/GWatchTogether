package com.saytikus.gwatchtogether.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object SystemClock : IClock {
    override fun epochMillis(): Long = System.currentTimeMillis()

    override fun monotonicNanos(): Long = System.nanoTime()
}

class SystemCoroutineDispatchers(
    override val default: CoroutineDispatcher = Dispatchers.Default,
    override val io: CoroutineDispatcher = Dispatchers.IO,
) : ICoroutineDispatchers
