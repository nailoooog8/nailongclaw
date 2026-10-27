// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.chat

import io.agents.nailongclaw.ServiceBindingState
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorStartGateTest {

    @Test
    fun `both ready starts`() {
        assertEquals(
            MonitorStartGate.Decision.START,
            MonitorStartGate.decide(
                ServiceBindingState.READY,
                ServiceBindingState.READY,
                waitInFlight = false,
                alreadyWaited = false,
            )
        )
    }

    @Test
    fun `connecting accessibility waits instead of rejecting`() {
        assertEquals(
            MonitorStartGate.Decision.WAIT,
            MonitorStartGate.decide(
                ServiceBindingState.CONNECTING,
                ServiceBindingState.READY,
                waitInFlight = false,
                alreadyWaited = false,
            )
        )
    }

    @Test
    fun `degraded with the setting still on waits — the E3 case`() {
        // QA E3: the system reports the services bound while the app's in-process
        // flags never got set, so the coordinator lands on DEGRADED. Waiting must
        // not be reserved for CONNECTING only.
        assertEquals(
            MonitorStartGate.Decision.WAIT,
            MonitorStartGate.decide(
                ServiceBindingState.DEGRADED,
                ServiceBindingState.DEGRADED,
                waitInFlight = false,
                alreadyWaited = false,
            )
        )
    }

    @Test
    fun `second attempt after a wait times out`() {
        assertEquals(
            MonitorStartGate.Decision.REJECT_TIMED_OUT,
            MonitorStartGate.decide(
                ServiceBindingState.CONNECTING,
                ServiceBindingState.CONNECTING,
                waitInFlight = false,
                alreadyWaited = true,
            )
        )
    }

    @Test
    fun `a wait in flight beats the consumed retry`() {
        // The retry is consumed the moment the wait starts, so BUSY must be tested
        // before TIMED_OUT or every tap during the wait would read as a timeout.
        assertEquals(
            MonitorStartGate.Decision.REJECT_BUSY,
            MonitorStartGate.decide(
                ServiceBindingState.CONNECTING,
                ServiceBindingState.CONNECTING,
                waitInFlight = true,
                alreadyWaited = true,
            )
        )
    }

    @Test
    fun `accessibility switched off in system settings rejects without waiting`() {
        assertEquals(
            MonitorStartGate.Decision.REJECT_DISABLED,
            MonitorStartGate.decide(
                ServiceBindingState.DISABLED,
                ServiceBindingState.CONNECTING,
                waitInFlight = false,
                alreadyWaited = false,
            )
        )
    }

    @Test
    fun `notification access switched off in system settings rejects without waiting`() {
        assertEquals(
            MonitorStartGate.Decision.REJECT_DISABLED,
            MonitorStartGate.decide(
                ServiceBindingState.READY,
                ServiceBindingState.DISABLED,
                waitInFlight = false,
                alreadyWaited = false,
            )
        )
    }

    @Test
    fun `a second tap during a wait never opens settings — the wait owns the outcome`() {
        // The retry is consumed when the wait starts, so without BUSY-first every
        // tap inside the 5s window would fall through to a rejection and bounce
        // the user to Settings while the original wait is still pending. The
        // post-wait re-check runs with waitInFlight=false, so a service that went
        // off in the meantime is still reported accurately — just once.
        assertEquals(
            MonitorStartGate.Decision.REJECT_BUSY,
            MonitorStartGate.decide(
                ServiceBindingState.DISABLED,
                ServiceBindingState.CONNECTING,
                waitInFlight = true,
                alreadyWaited = true,
            )
        )
    }
}
