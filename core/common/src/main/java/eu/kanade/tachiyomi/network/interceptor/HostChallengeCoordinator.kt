package eu.kanade.tachiyomi.network.interceptor

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Requests share one recovery attempt, including its failure, without retaining visited hosts. */
internal class HostChallengeCoordinator {
    private val hosts = mutableMapOf<String, Gate>()

    @Synchronized
    fun acquire(host: String): Lease {
        val gate = hosts.getOrPut(host) { Gate() }
        gate.users++
        return Lease(host, gate)
    }

    inner class Lease internal constructor(private val host: String, private val gate: Gate) : AutoCloseable {
        fun <T> request(cancelled: () -> Boolean, block: () -> T): Pair<T, Long> {
            lock(gate.lock.readLock(), cancelled)
            try {
                val generation = gate.generation
                return block() to generation
            } finally {
                gate.lock.readLock().unlock()
            }
        }

        fun recover(generation: Long, cancelled: () -> Boolean, cleared: () -> Boolean, solve: () -> Unit) {
            // A nested request can recover while its parent holds a read lock. Upgrade without waiting on itself.
            val readHolds = gate.lock.readHoldCount
            repeat(readHolds) { gate.lock.readLock().unlock() }
            var acquired = false
            try {
                lock(gate.lock.writeLock(), cancelled)
                acquired = true
                if (gate.generation != generation) {
                    gate.failure?.let { throw IOException(it.message, it) }
                    return
                }
                if (cleared()) return
                try {
                    solve()
                    gate.failure = null
                } catch (error: IOException) {
                    gate.failure = error
                    throw error
                } finally {
                    gate.generation++
                }
            } finally {
                // Restore the parent's read holds before releasing the write hold (a safe downgrade).
                repeat(readHolds) { gate.lock.readLock().lock() }
                if (acquired) gate.lock.writeLock().unlock()
            }
        }

        override fun close() = synchronized(this@HostChallengeCoordinator) {
            gate.users--
            if (gate.users == 0) hosts.remove(host, gate)
        }
    }

    internal class Gate {
        val lock = ReentrantReadWriteLock()
        var users = 0
        var generation = 0L
        var failure: IOException? = null
    }

    private fun lock(lock: java.util.concurrent.locks.Lock, cancelled: () -> Boolean) {
        try {
            while (true) {
                if (cancelled()) throw InterruptedIOException("Request cancelled")
                if (lock.tryLock(100, TimeUnit.MILLISECONDS)) return
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Request interrupted").apply { initCause(error) }
        }
    }
}
