package io.agents.nailongclaw.service

import io.agents.nailongclaw.service.ForegroundStartGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine that keeps a fast-failing task from killing the process.
 *
 * v068-fgs-race moved `startForeground()` into `onCreate`, which fixed the
 * "service exists but never went foreground" case. The remaining hole was the
 * gap *before* the service exists: a task that fails ~26ms after being accepted
 * (a11y missing) drives `resetToIdle()` → `stop()` → `stopService()` while the
 * `startForegroundService()` dispatch is still in the air. The service is then
 * never brought up, its obligation to call `startForeground()` within 5s can
 * never be met, and the system kills the app with
 * `ForegroundServiceDidNotStartInTimeException`.
 *
 * These tests pin the guard's contract without an emulator: a stop that lands
 * in the gap is deferred to `onStartCommand`, and everything else stops
 * immediately as before.
 */
class ForegroundStartGuardTest {

    @Test
    fun `stop while service is running stops immediately`() {
        val guard = ForegroundStartGuard()
        guard.onDispatched()
        guard.onServiceCreated()

        assertFalse(guard.deferStop(serviceRunning = true))
        assertFalse(guard.consumeStopAfterStart())
    }

    @Test
    fun `stop before any start is a no-op deferral`() {
        val guard = ForegroundStartGuard()

        // Nothing was ever dispatched — stopService() is harmless and is the
        // only thing that clears a stale notification.
        assertFalse(guard.deferStop(serviceRunning = false))
        assertFalse(guard.consumeStopAfterStart())
    }

    @Test
    fun `stop in the dispatch gap is deferred and honoured once foreground`() {
        val guard = ForegroundStartGuard()
        guard.onDispatched()

        // The fast-fail window: dispatch accepted, service object does not
        // exist yet.
        assertTrue(guard.deferStop(serviceRunning = false))
        // Service comes up, onCreate → onStartCommand runs startForeground.
        guard.onServiceCreated()
        assertTrue(guard.consumeStopAfterStart())
        // Read-and-clear: a second onStartCommand (redelivery) must not
        // stop a service that a newer task is using.
        assertFalse(guard.consumeStopAfterStart())
    }

    @Test
    fun `a new start supersedes a deferred stop`() {
        val guard = ForegroundStartGuard()
        guard.onDispatched()
        assertTrue(guard.deferStop(serviceRunning = false))

        // Another task arrives before the service is created.
        guard.onDispatched()
        assertFalse(guard.consumeStopAfterStart())
    }

    @Test
    fun `a failed dispatch leaves the stop path alone`() {
        val guard = ForegroundStartGuard()
        guard.onDispatched()
        guard.onDispatchFailed()

        // No obligation was registered (background-start refusal), so the
        // normal stopService() path is correct again.
        assertFalse(guard.deferStop(serviceRunning = false))
        assertFalse(guard.consumeStopAfterStart())
    }

    @Test
    fun `service created clears the in-flight flag for later stops`() {
        val guard = ForegroundStartGuard()
        guard.onDispatched()
        guard.onServiceCreated()

        assertFalse(guard.deferStop(serviceRunning = false))
    }
}
