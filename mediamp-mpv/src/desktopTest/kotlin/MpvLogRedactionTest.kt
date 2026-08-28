/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvLogRedactionTest {
    @Test
    fun `native log sink redacts URIs media paths and credentials`() {
        val secret = "do-not-print-this"
        val captured = mutableListOf<MPVLogMessage>()
        MPVHandle.setLogHandler { captured += it }
        try {
            onNativeLog(
                instanceHandle = 1,
                level = MPVLog.ERROR,
                prefix = "ffmpeg",
                message = "open https://provider.invalid/live?token=$secret " +
                    "Authorization: Bearer $secret Cookie=$secret /home/user/private/movie.mkv",
            )
        } finally {
            MPVHandle.setLogHandler(null)
        }

        val rendered = captured.single().toString()
        assertFalse("provider.invalid" in rendered)
        assertFalse(secret in rendered)
        assertFalse("/home/user" in rendered)
        assertTrue("<redacted-uri>" in rendered)
        assertTrue("<redacted-media-path>" in rendered)
    }

    @Test
    fun `log lines are bounded before delivery`() {
        val rendered = redactMpvLog("x".repeat(32 * 1024))

        assertTrue(rendered.length < 17 * 1024)
        assertTrue(rendered.endsWith("<truncated>"))
    }
}
