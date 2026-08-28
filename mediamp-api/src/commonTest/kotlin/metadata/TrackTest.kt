/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import org.openani.mediamp.InternalMediampApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Player backends re-create track instances on every native tracks-changed callback
 * and compare candidate lists to decide whether to reset the selection, so these
 * types must have value equality (open-ani/animeko#1128).
 */
@OptIn(InternalMediampApi::class)
class TrackTest {
    private fun subtitleTrack(
        id: String = "sub-1",
        internalId: String = "1",
        language: String? = "zh",
        labels: List<TrackLabel> = listOf(TrackLabel("zh", "简日")),
    ) = SubtitleTrack(id, internalId, language, labels)

    private fun audioTrack(
        id: String = "audio-1",
        internalId: String = "1",
        name: String? = "main",
        labels: List<TrackLabel> = listOf(TrackLabel(null, "AAC")),
    ) = AudioTrack(id, internalId, name, labels)

    private fun videoTrack(
        id: String = "video-1",
        internalId: String = "1",
        language: String? = null,
        labels: List<TrackLabel> = listOf(TrackLabel(null, "1080p")),
        width: Int? = 1920,
        height: Int? = 1080,
        bitrate: Long? = 8_000_000,
        codec: String? = "h264",
    ) = VideoTrack(id, internalId, language, labels, width, height, bitrate, codec)

    @Test
    fun `subtitle tracks with same fields are equal`() {
        assertEquals(subtitleTrack(), subtitleTrack())
        assertEquals(subtitleTrack().hashCode(), subtitleTrack().hashCode())
    }

    @Test
    fun `subtitle tracks with different fields are not equal`() {
        assertNotEquals(subtitleTrack(), subtitleTrack(id = "sub-2"))
        assertNotEquals(subtitleTrack(), subtitleTrack(internalId = "2"))
        assertNotEquals(subtitleTrack(), subtitleTrack(language = null))
        assertNotEquals(subtitleTrack(), subtitleTrack(labels = emptyList()))
    }

    @Test
    fun `audio tracks with same fields are equal`() {
        assertEquals(audioTrack(), audioTrack())
        assertEquals(audioTrack().hashCode(), audioTrack().hashCode())
    }

    @Test
    fun `audio tracks with different fields are not equal`() {
        assertNotEquals(audioTrack(), audioTrack(id = "audio-2"))
        assertNotEquals(audioTrack(), audioTrack(name = null))
    }

    @Test
    fun `video tracks use rendition metadata in value equality`() {
        assertEquals(videoTrack(), videoTrack())
        assertEquals(videoTrack().hashCode(), videoTrack().hashCode())
        assertNotEquals(videoTrack(), videoTrack(width = 1280, height = 720))
        assertNotEquals(videoTrack(), videoTrack(bitrate = 4_000_000))
        assertNotEquals(videoTrack(), videoTrack(codec = "hevc"))
    }

    @Test
    fun `subtitle track does not equal audio track with same fields`() {
        val labels = listOf(TrackLabel("zh", "简日"))
        assertNotEquals<Track>(
            SubtitleTrack("x", "1", null, labels),
            AudioTrack("x", "1", null, labels),
        )
        assertNotEquals<Track>(
            SubtitleTrack("x", "1", null, labels),
            VideoTrack("x", "1", null, labels),
        )
    }

    @Test
    fun `track labels have value equality`() {
        assertEquals(TrackLabel("zh", "CHS"), TrackLabel("zh", "CHS"))
        assertEquals(TrackLabel(null, "CHS"), TrackLabel(null, "CHS"))
        assertNotEquals(TrackLabel("zh", "CHS"), TrackLabel("zh", "CHT"))
        assertNotEquals(TrackLabel("zh", "CHS"), TrackLabel(null, "CHS"))
    }

    @Test
    fun `re-created candidate lists compare equal`() {
        // This is the exact comparison ExoPlayer/VLC backends perform on refresh.
        val first = listOf(subtitleTrack(), subtitleTrack(id = "sub-2", internalId = "2"))
        val second = listOf(subtitleTrack(), subtitleTrack(id = "sub-2", internalId = "2"))
        assertEquals(first, second)
    }
}
