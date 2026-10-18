package pt.carrismetropolitana.tracker

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polling loops wait on this instead of running forever.
 *
 * They used to run for the life of the process, so a backgrounded app kept spending ~44 MB/hour on
 * `/vehicles` for a map nobody was looking at. The loop shape is `await(); poll(); delay(interval)`,
 * so the only thing that matters is that [ForegroundGate.await] blocks while backgrounded and lets
 * go the moment the app returns.
 */
class ForegroundGateTest {

    @Test
    fun defaultsToForegroundSoAPollLoopNeverWaitsForever() = runBlocking {
        val gate = ForegroundGate()
        assertTrue(gate.isForeground)
        // Must return rather than hang: a preview or a test starts loops without a lifecycle.
        withTimeoutOrNull(1_000) { gate.await() }
            ?: throw AssertionError("await() blocked on a gate that was never backgrounded")
    }

    @Test
    fun blocksWhileBackgroundedAndReleasesOnReturn() = runBlocking {
        val gate = ForegroundGate()
        gate.set(false)
        assertFalse(gate.isForeground)

        var released = false
        val waiter = launch {
            gate.await()
            released = true
        }

        delay(200)
        assertFalse("await() returned while the app was backgrounded", released)

        gate.set(true)
        withTimeoutOrNull(1_000) { waiter.join() }
            ?: throw AssertionError("await() did not return after the app came back")
        assertTrue(released)
    }

    /** A second ON_START must not need a matching pair of calls to unblock. */
    @Test
    fun repeatedSetIsIdempotent() = runBlocking {
        val gate = ForegroundGate()
        gate.set(true)
        gate.set(true)
        withTimeoutOrNull(1_000) { gate.await() }
            ?: throw AssertionError("await() blocked after a redundant foreground call")

        gate.set(false)
        gate.set(false)
        assertFalse(gate.isForeground)
        assertNull(withTimeoutOrNull(200) { gate.await() })
    }

    /**
     * The loop has to re-check after each sleep, so a quick trip to the background and back must
     * let exactly one more poll through rather than resuming the old cadence.
     */
    @Test
    fun backgroundingMidLoopSuspendsTheNextIteration() = runBlocking {
        val gate = ForegroundGate()
        var polls = 0

        val loop = launch {
            while (true) {
                gate.await()
                polls++
                delay(50)
            }
        }

        delay(160)
        val whileVisible = polls
        assertTrue("the loop never polled", whileVisible > 0)

        gate.set(false)
        delay(200)
        assertEquals("polled while backgrounded", whileVisible, polls)

        gate.set(true)
        delay(200)
        assertTrue("the loop did not resume", polls > whileVisible)

        loop.cancel()
    }
}