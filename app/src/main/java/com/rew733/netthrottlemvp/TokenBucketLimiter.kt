package com.rew733.netthrottlemvp

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TokenBucketLimiter(
    initialBytesPerSecond: Long = 150L * 1024L
) {
    private val mutex = Mutex()
    private var bytesPerSecond: Long = initialBytesPerSecond.coerceAtLeast(10L * 1024L)
    private var burstCapacity: Long = bytesPerSecond
    private var tokens: Double = burstCapacity.toDouble()
    private var lastRefillNano: Long = System.nanoTime()

    suspend fun setRateBytesPerSecond(newBytesPerSecond: Long) {
        mutex.withLock {
            refillLocked()
            bytesPerSecond = newBytesPerSecond.coerceAtLeast(10L * 1024L)
            burstCapacity = bytesPerSecond
            tokens = tokens.coerceAtMost(burstCapacity.toDouble())
        }
    }

    suspend fun acquire(requestedBytes: Int) {
        if (requestedBytes <= 0) return

        while (true) {
            var sleepMs = 0L

            mutex.withLock {
                refillLocked()
                val target = requestedBytes.toDouble().coerceAtMost(burstCapacity.toDouble())
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
