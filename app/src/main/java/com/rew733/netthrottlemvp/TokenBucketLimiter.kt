package com.rew733.netthrottlemvp

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TokenBucketLimiter(
    private val bytesPerSecond: Long = 150L * 1024L,
    private val burstCapacity: Long = 150L * 1024L
) {
    private val mutex = Mutex()
    private var tokens: Double = burstCapacity.toDouble()
    private var lastRefillNano: Long = System.nanoTime()

    suspend fun acquire(requestedBytes: Int) {
        if (requestedBytes <= 0 || bytesPerSecond <= 0L) return

        val target = requestedBytes.toDouble().coerceAtMost(burstCapacity.toDouble())

        while (true) {
            var sleepMs = 0L

            mutex.withLock {
                refillLocked()
                if (tokens >= target) {
                    tokens -= target
                    return
                } else {
                    val missing = target - tokens
                    val secondsNeeded = missing / bytesPerSecond.toDouble()
                    sleepMs = (secondsNeeded * 1000.0).toLong().coerceAtLeast(5L)
                }
            }

            if (sleepMs > 0L) {
                delay(sleepMs)
            }
        }
    }

    private fun refillLocked() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillNano).coerceAtLeast(0L) / 1_000_000_000.0
        if (elapsedSeconds > 0.0) {
            tokens = (tokens + elapsedSeconds * bytesPerSecond.toDouble())
                .coerceAtMost(burstCapacity.toDouble())
            lastRefillNano = now
        }
    }
}
