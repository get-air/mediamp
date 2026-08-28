package com.getair.video.mediamp

import java.lang.reflect.Modifier
import com.getair.video.PlaybackErrorCode
import com.getair.video.PlaybackKind
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.features.MediaTimelineSnapshot
import org.openani.mediamp.features.SeekableTimeRange
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.metadata.VideoTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalMediampApi::class)
class MediampDesktopMappingTest {
    @Test
    fun `public adapter ABI never exposes mediamp implementation types`() {
        val publicTypes = listOf(
            MediampDesktopBackendFactory::class.java,
            MediampDesktopVideoPlayer::class.java,
            Class.forName("com.getair.video.mediamp.MediampDesktopBackendKt"),
        )

        val exposed = buildList {
            publicTypes.flatMapTo(this) { type ->
                type.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                    .flatMap { method -> listOf(method.returnType) + method.parameterTypes }
            }
            publicTypes.flatMapTo(this) { type ->
                type.declaredConstructors
                    .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                    .flatMap { it.parameterTypes.toList() }
            }
        }.map(Class<*>::getName).filter { it.startsWith("org.openani.mediamp") }

        assertEquals(emptyList(), exposed)
    }

    @Test
    fun `plain live never becomes seekable from the engine cache`() {
        val timeline = MediaTimelineSnapshot(
            durationMillis = 120_000,
            seekable = true,
            partiallySeekable = true,
            seekableRanges = listOf(SeekableTimeRange(30_000, 120_000)),
        ).toAirTimeline(PlaybackKind.Live, 120_000)

        assertEquals(PlaybackKind.Live, timeline.kind)
        assertFalse(timeline.canSeek)
        assertFalse(timeline.showSeekBar)
        assertEquals(null, timeline.seekableRange)
        assertEquals(120_000, timeline.liveEdgeMillis)
    }

    @Test
    fun `seekable live uses the newest native DVR range`() {
        val timeline = MediaTimelineSnapshot(
            partiallySeekable = true,
            seekableRanges = listOf(
                SeekableTimeRange(0, 10_000),
                SeekableTimeRange(40_000, 90_000),
            ),
        ).toAirTimeline(PlaybackKind.SeekableLive, null)

        assertEquals(PlaybackKind.SeekableLive, timeline.kind)
        assertEquals(40_000, timeline.seekableRange?.startMillis)
        assertEquals(90_000, timeline.seekableRange?.endMillis)
        assertTrue(timeline.showSeekBar)
    }

    @Test
    fun `track metadata survives the adapter boundary`() {
        val audio = AudioTrack(
            "audio-2", "2", "Director commentary", listOf(TrackLabel("en", "English")),
            channels = 6, codec = "eac3", isDefault = true,
        ).toAirTrack()
        val subtitle = SubtitleTrack(
            "sub-3", "3", "es", listOf(TrackLabel("en", "Spanish")),
            format = "ass", external = true, isForced = true,
        ).toAirTrack()
        val video = VideoTrack(
            "video-4", "4", null, listOf(TrackLabel(null, "2160p")),
            width = 3840, height = 2160, bitrate = 24_000_000, codec = "hevc", isDefault = true,
        ).toAirTrack()

        assertEquals("Director commentary", audio.label)
        assertEquals(6, audio.channels)
        assertEquals("eac3", audio.codec)
        assertTrue(audio.isDefault)
        assertEquals("ass", subtitle.format)
        assertTrue(subtitle.external)
        assertTrue(subtitle.isForced)
        assertEquals(3840, video.width)
        assertEquals(2160, video.height)
        assertEquals(24_000_000, video.bitrate)
        assertEquals("hevc", video.codec)
    }

    @Test
    fun `engine failure messages are replaced with redacted Air errors`() {
        val error = PlaybackException(
            org.openani.mediamp.PlaybackErrorCode.IO,
            "failed https://provider.invalid/live?token=secret",
        ).toAirError()

        assertEquals(PlaybackErrorCode.Network, error.code)
        assertFalse("provider.invalid" in error.toString())
        assertFalse("secret" in error.toString())
    }
}
