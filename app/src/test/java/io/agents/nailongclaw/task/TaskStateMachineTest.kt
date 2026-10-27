// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import io.agents.nailongclaw.TaskEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table and the event→step projection, pinned independently of
 * any storage: the store tests care about durability, these care about what
 * a task's status and ledger *mean*.
 */
class TaskStateMachineTest {

    private val t = 1_000L

    // ---- Status transitions: four terminal events → four terminal states ----

    @Test
    fun `completed failed cancelled blocked each land in their terminal state`() {
        assertEquals(
            TaskRecord.Status.COMPLETED,
            TaskStateMachine.nextStatus(TaskRecord.Status.RUNNING, TaskEvent.Completed("done")),
        )
        assertEquals(
            TaskRecord.Status.FAILED,
            TaskStateMachine.nextStatus(TaskRecord.Status.RUNNING, TaskEvent.Failed("boom")),
        )
        assertEquals(
            TaskRecord.Status.CANCELLED,
            TaskStateMachine.nextStatus(TaskRecord.Status.RUNNING, TaskEvent.Cancelled),
        )
        assertEquals(
            TaskRecord.Status.BLOCKED,
            TaskStateMachine.nextStatus(TaskRecord.Status.RUNNING, TaskEvent.Blocked),
        )
    }

    @Test
    fun `terminal states absorb every later event`() {
        // The orchestrator has several paths that can each emit a terminal
        // event; a task that finished twice must not flip status twice.
        for (status in TaskRecord.TERMINAL_STATUSES) {
            assertNull(status.toString(), TaskStateMachine.nextStatus(status, TaskEvent.Completed("again")))
            assertNull(status.toString(), TaskStateMachine.nextStatus(status, TaskEvent.Failed("again")))
            assertNull(status.toString(), TaskStateMachine.nextStatus(status, TaskEvent.Cancelled))
            assertNull(status.toString(), TaskStateMachine.nextStatus(status, TaskEvent.Blocked))
        }
    }

    @Test
    fun `non-terminal events never move the status`() {
        for (event in listOf<TaskEvent>(
            TaskEvent.Response("mid-turn"),
            TaskEvent.ToolAction("Send Message"),
            TaskEvent.ToolResult("Send Message", true, "sent"),
            TaskEvent.LoopStart(2),
            TaskEvent.Progress(1, "step"),
            TaskEvent.Thinking("hmm"),
        )) {
            assertNull(
                event.toString(),
                TaskStateMachine.nextStatus(TaskRecord.Status.RUNNING, event),
            )
        }
    }

    // ---- Event → step projection ----

    @Test
    fun `tool results become TOOL steps carrying success and detail`() {
        val step = TaskStateMachine.stepFor(TaskEvent.ToolResult("Send Message", true, "sent to Mom"), t)
        assertNotNull(step)
        assertEquals(TaskRecord.StepKind.TOOL, step!!.kind)
        assertEquals("sent to Mom", step.text)
        assertEquals("Send Message", step.toolName)
        assertEquals(true, step.success)
        assertEquals(t, step.at)
    }

    @Test
    fun `a tool result with blank detail falls back to the tool name`() {
        val step = TaskStateMachine.stepFor(TaskEvent.ToolResult("Take Screenshot", true, ""), t)
        assertNotNull(step)
        assertEquals("Take Screenshot", step!!.text)
    }

    @Test
    fun `the finish pseudo tool is not a step`() {
        // Same rule as the chat tool cards: it is the loop's bookkeeping,
        // not device work. The two surfaces must tell the same story.
        assertNull(TaskStateMachine.stepFor(TaskEvent.ToolResult("Finish", true, "Task completed"), t))
        assertNull(TaskStateMachine.stepFor(TaskEvent.ToolResult("finish", true, "Task completed"), t))
    }

    @Test
    fun `progress becomes a PROGRESS step`() {
        val step = TaskStateMachine.stepFor(TaskEvent.Progress(3, "Step 3/5: opening WhatsApp"), t)
        assertNotNull(step)
        assertEquals(TaskRecord.StepKind.PROGRESS, step!!.kind)
        assertEquals("Step 3/5: opening WhatsApp", step.text)
        assertNull(step.toolName)
    }

    @Test
    fun `overlong step text is cut visibly not silently`() {
        val long = "x".repeat(1000)
        val step = TaskStateMachine.stepFor(TaskEvent.Progress(1, long), t)!!
        assertTrue(step.text.length <= 301)
        assertTrue(step.text.endsWith("…"))
    }

    @Test
    fun `conversational events are not step material`() {
        assertNull(TaskStateMachine.stepFor(TaskEvent.Response("answer"), t))
        assertNull(TaskStateMachine.stepFor(TaskEvent.ToolAction("Send Message"), t))
        assertNull(TaskStateMachine.stepFor(TaskEvent.LoopStart(1), t))
        assertNull(TaskStateMachine.stepFor(TaskEvent.Thinking("hmm"), t))
    }
}
