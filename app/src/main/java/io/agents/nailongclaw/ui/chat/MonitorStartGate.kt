// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.chat

import io.agents.nailongclaw.ServiceBindingState

/**
 * Gate policy for starting a background monitor.
 *
 * [io.agents.nailongclaw.AppCapabilityCoordinator.missingMonitorRequirements] demands
 * READY for both accessibility and notification access. That is the right end state,
 * but taken literally it rejects the user while the bindings are merely *late*: the
 * OS can report a service bound (`dumpsys accessibility`, the notification-access
 * page) while the app's in-process `instance` flag has not been set yet, because
 * `onServiceConnected` / `onListenerConnected` fire in the service's own process
 * lifecycle. QA E3 hit exactly this — both permissions granted system-side, monitor
 * start refused anyway.
 *
 * So the decision here is deliberately not "READY or bust":
 *
 * - **START** — both READY.
 * - **WAIT** — a service is enabled in Android settings but the app has not seen the
 *   binding yet (CONNECTING, or DEGRADED with the setting still on). Give the
 *   callbacks a chance to fire before rejecting; the task path already does this.
 * - **REJECT_DISABLED** — a service is genuinely switched off in Android settings.
 *   Waiting cannot fix that, so send the user straight there.
 * - **REJECT_TIMED_OUT** — we already waited once and the flags still never came.
 * - **REJECT_BUSY** — a wait is already in flight from an earlier tap.
 *
 * [ServiceBindingState.DISABLED] is exactly "not enabled in Android settings"
 * (`bindingState` checks that first), which is what makes the split above possible
 * from the two states alone.
 */
object MonitorStartGate {

    enum class Decision {
        START,
        WAIT,
        REJECT_DISABLED,
        REJECT_TIMED_OUT,
        REJECT_BUSY,
    }

    fun decide(
        accessibility: ServiceBindingState,
        notificationAccess: ServiceBindingState,
        waitInFlight: Boolean,
        alreadyWaited: Boolean,
    ): Decision {
        if (accessibility == ServiceBindingState.READY &&
            notificationAccess == ServiceBindingState.READY
        ) {
            return Decision.START
        }
        // Order matters: while a wait is in flight the retry has already been
        // consumed, so BUSY must be tested before TIMED_OUT.
        if (waitInFlight) return Decision.REJECT_BUSY
        if (accessibility == ServiceBindingState.DISABLED ||
            notificationAccess == ServiceBindingState.DISABLED
        ) {
            return Decision.REJECT_DISABLED
        }
        if (alreadyWaited) return Decision.REJECT_TIMED_OUT
        return Decision.WAIT
    }
}
