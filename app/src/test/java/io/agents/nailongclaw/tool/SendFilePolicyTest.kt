// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the R3-04 send_file restriction.
 *
 * The attack this closes: any successful injection (a poisoned search snippet,
 * a planted memory) could ask `send_file` for the app's private data directory —
 * chat database, task log, the MMKV file holding encrypted bot tokens — and the
 * tool would hand it to whatever channel the task came from.
 */
class SendFilePolicyTest {

    private val appData = "/data/user/0/io.agents.nailongclaw"

    @Test
    fun `app private data is never sendable`() {
        val paths = listOf(
            "/data/user/0/io.agents.nailongclaw/databases/pokeclaw.db",
            "/data/user/0/io.agents.nailongclaw/shared_prefs/mmkv.xml",
            "/data/user/0/io.agents.nailongclaw/files/mmkv/crc",
            "/data/data/io.agents.nailongclaw/databases/chat.db",
            appData,
        )
        for (path in paths) {
            assertFalse("$path must be refused", SendFilePolicy.isAllowed(path, appData))
            assertNotNull(SendFilePolicy.rejectReason(path, appData))
        }
    }

    @Test
    fun `traversal out of shared storage into private data is caught`() {
        // The path looks like shared storage until the ".." is folded.
        assertFalse(
            SendFilePolicy.isAllowed(
                "/sdcard/Download/../../data/user/0/io.agents.nailongclaw/databases/x.db",
                appData
            )
        )
    }

    @Test
    fun `other apps private data and system trees are refused`() {
        for (path in listOf(
            "/data/data/com.android.chat/databases/x.db",
            "/data/app/com.example.app-1/base.apk",
            "/system/etc/hosts",
            "/proc/self/environ",
            "/dev/socket/x",
            "/apex/com.android.art/art.jar",
        )) {
            assertFalse("$path must be refused", SendFilePolicy.isAllowed(path, appData))
        }
    }

    @Test
    fun `ordinary user files are allowed`() {
        val paths = listOf(
            "/sdcard/DCIM/Camera/IMG_0001.jpg",
            "/sdcard/Download/report.pdf",
            "/storage/emulated/0/Pictures/Screenshots/shot.png",
            "/sdcard/Android/data/io.agents.nailongclaw/files/exports/trace.txt",
        )
        for (path in paths) {
            assertTrue("$path should be allowed", SendFilePolicy.isAllowed(path, appData))
            assertNull(SendFilePolicy.rejectReason(path, appData))
        }
    }

    @Test
    fun `a null app data dir still denies the private trees`() {
        // The explicit check is belt-and-braces; the root rules must stand alone.
        assertFalse(SendFilePolicy.isAllowed("/data/user/0/io.agents.nailongclaw/databases/x.db", null))
        assertTrue(SendFilePolicy.isAllowed("/sdcard/a.jpg", null))
    }

    @Test
    fun `empty and root paths are refused`() {
        assertFalse(SendFilePolicy.isAllowed("", appData))
        assertFalse(SendFilePolicy.isAllowed("   ", appData))
        assertFalse(SendFilePolicy.isAllowed(null, appData))
        assertFalse(SendFilePolicy.isAllowed("/", appData))
    }

    @Test
    fun `the refusal explains which rule fired`() {
        val privateReason = SendFilePolicy.rejectReason("$appData/databases/x.db", appData)!!
        assertTrue(privateReason.contains("private"))
        val systemReason = SendFilePolicy.rejectReason("/system/etc/hosts", appData)!!
        assertTrue(systemReason.contains("system"))
    }

    @Test
    fun `the attachment cap matches what chat channels accept`() {
        assertEquals(50L * 1024 * 1024, SendFilePolicy.MAX_BYTES)
    }
}
