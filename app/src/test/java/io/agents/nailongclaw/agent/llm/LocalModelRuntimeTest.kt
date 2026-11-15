package io.agents.nailongclaw.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the failure classifiers [LocalModelRuntime] uses to steer its
 * openConversation retry loop. Which branch an error takes decides whether
 * the engine gets reset — the self-heal that keeps a wedged native session
 * (e.g. after an 8192-overflow task failure) from blocking every later
 * local task with "A session already exists".
 */
class LocalModelRuntimeTest {

    @Test
    fun `session conflict messages are recognised`() {
        assertTrue(LocalModelRuntime.isSessionConflict(Exception("A session already exists")))
        assertTrue(LocalModelRuntime.isSessionConflict(Exception("Only one session is supported at a time")))
        assertTrue(LocalModelRuntime.isSessionConflict(Exception("session already in use")))
        // Case-insensitive, matching LiteRT's own phrasing wherever it lands.
        assertTrue(LocalModelRuntime.isSessionConflict(Exception("a session already exists")))
    }

    @Test
    fun `non-conflict errors are not session conflicts`() {
        assertFalse(LocalModelRuntime.isSessionConflict(null))
        assertFalse(LocalModelRuntime.isSessionConflict(Exception("")))
        assertFalse(LocalModelRuntime.isSessionConflict(Exception("Input token ids are too long")))
        assertFalse(LocalModelRuntime.isSessionConflict(Exception("OpenCL library not found")))
        assertFalse(LocalModelRuntime.isSessionConflict(Exception("Model file missing")))
    }

    @Test
    fun `gpu backend failures are recognised`() {
        assertTrue(LocalModelRuntime.isGpuBackendFailure(Exception("OpenCL library not found")))
        assertTrue(LocalModelRuntime.isGpuBackendFailure(Exception("Failed to create engine")))
        assertTrue(LocalModelRuntime.isGpuBackendFailure(Exception("failed to load compiled model")))
    }

    @Test
    fun `runtime errors are not gpu backend failures`() {
        assertFalse(LocalModelRuntime.isGpuBackendFailure(null))
        assertFalse(LocalModelRuntime.isGpuBackendFailure(Exception("")))
        assertFalse(LocalModelRuntime.isGpuBackendFailure(Exception("A session already exists")))
        assertFalse(LocalModelRuntime.isGpuBackendFailure(Exception("Input token ids are too long. Exceeding the maximum number of tokens allowed: 8209 >= 8192")))
    }
}
