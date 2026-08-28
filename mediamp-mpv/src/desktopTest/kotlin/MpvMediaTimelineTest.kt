/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.features.SeekableTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvMediaTimelineTest {
    @Test
    fun `cache ranges are parsed sorted and merged`() {
        val state = """
            {
              "seekable-ranges": [
                {"end": 31.25, "start": 20.0},
                {"start": -2.5, "end": 4.0},
                {"start": 3.5, "end": 8.125}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                SeekableTimeRange(-2_500, 8_125),
                SeekableTimeRange(20_000, 31_250),
            ),
            parseMpvSeekableRanges(state),
        )
    }

    @Test
    fun `missing and malformed cache ranges are ignored`() {
        assertEquals(emptyList(), parseMpvSeekableRanges(null))
        assertEquals(emptyList(), parseMpvSeekableRanges("{}"))
        assertEquals(
            listOf(SeekableTimeRange(2_000, 3_000)),
            parseMpvSeekableRanges(
                """{"seekable-ranges":[{"start":5,"end":4},{"start":2,"end":3}]}""",
            ),
        )
    }
}
