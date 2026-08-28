package com.getair.video.mediamp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.getair.video.AudioTrack
import com.getair.video.BackendEvent
import com.getair.video.DefaultVideoPlayer
import com.getair.video.ExternalSubtitleSource
import com.getair.video.HardwareAcceleration
import com.getair.video.OpenedMedia
import com.getair.video.PlaybackError
import com.getair.video.PlaybackErrorCode
import com.getair.video.PlaybackFailure
import com.getair.video.PlaybackKind
import com.getair.video.PlaybackSessionId
import com.getair.video.PlaybackSource
import com.getair.video.PlaybackTimeline
import com.getair.video.PlayerCapabilities
import com.getair.video.SeekableRange
import com.getair.video.SubtitleTrack
import com.getair.video.TrackSelectionResult
import com.getair.video.VideoBackend
import com.getair.video.VideoBackendFactory
import com.getair.video.VideoPlayer
import com.getair.video.VideoTrack
import java.awt.EventQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.MediaTimeline
import org.openani.mediamp.features.MediaTimelineSnapshot
import org.openani.mediamp.metadata.AudioTrack as MediampAudioTrack
import org.openani.mediamp.metadata.SubtitleTrack as MediampSubtitleTrack
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.VideoTrack as MediampVideoTrack
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData

/** Optional bundled-MPV desktop backend. Applications see only Air player types. */
class MediampDesktopBackendFactory(
    private val context: Any = Unit,
    private val parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : VideoBackendFactory {
    override val id: String = "mediamp-mpv"

    override suspend fun probe(): PlayerCapabilities = withContext(Dispatchers.Default) {
        val player = createEngine()
        try {
            MEDIAMP_DESKTOP_CAPABILITIES
        } finally {
            player.close()
        }
    }

    override fun create(): VideoPlayer = createDesktopPlayer()

    fun createDesktopPlayer(): MediampDesktopVideoPlayer {
        val engine = createEngine()
        val backend = MediampDesktopBackend(engine)
        return DefaultMediampDesktopVideoPlayer(
            engine = engine,
            delegate = DefaultVideoPlayer(backend, engine.mainDispatcher),
        )
    }

    private fun createEngine(): MpvMediampPlayer = onPlayerThreadBlocking {
        MpvMediampPlayer(
            context = context,
            parentCoroutineContext = parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]),
            mainDispatcher = Dispatchers.Main,
        )
    }
}

interface MediampDesktopVideoPlayer : VideoPlayer

private class DefaultMediampDesktopVideoPlayer(
    val engine: MpvMediampPlayer,
    private val delegate: VideoPlayer,
) : MediampDesktopVideoPlayer, VideoPlayer by delegate

/** A regular Compose node: it can move, resize, clip, and sit under arbitrary overlays. */
@Composable
fun MediampDesktopVideoSurface(
    player: MediampDesktopVideoPlayer,
    modifier: Modifier = Modifier,
) {
    val implementation = player as? DefaultMediampDesktopVideoPlayer
        ?: error("MediampDesktopVideoPlayer must be created by MediampDesktopBackendFactory")
    MpvMediampPlayerSurface(implementation.engine, modifier)
}

private class MediampDesktopBackend(
    private val player: MpvMediampPlayer,
) : VideoBackend {
    private val scope = CoroutineScope(SupervisorJob() + player.mainDispatcher)
    private val eventFlow = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    private var observationJob: Job? = null
    private var released = false
    @Volatile private var activeSessionId: PlaybackSessionId? = null
    @Volatile private var audioTargets: Map<String, MediampAudioTrack> = emptyMap()
    @Volatile private var subtitleTargets: Map<String, MediampSubtitleTrack> = emptyMap()
    @Volatile private var videoTargets: Map<String, MediampVideoTrack> = emptyMap()
    private var audio: List<AudioTrack> = emptyList()
    private var subtitles: List<SubtitleTrack> = emptyList()
    private var video: List<VideoTrack> = emptyList()
    private var selectedAudioId: String? = null
    private var selectedSubtitleId: String? = null
    private var selectedVideoId: String? = null

    override val capabilities: PlayerCapabilities = MEDIAMP_DESKTOP_CAPABILITIES
    override val events: Flow<BackendEvent> = eventFlow

    override suspend fun open(
        sessionId: PlaybackSessionId,
        source: PlaybackSource,
        playWhenReady: Boolean,
    ): OpenedMedia = withContext(player.mainDispatcher) {
        check(!released) { "Mediamp desktop backend is closed" }
        observationJob?.cancelAndJoin()
        activeSessionId = sessionId
        clearTrackSnapshots()
        val ready = AtomicBoolean(false)
        observationJob = observeSession(sessionId, source.kindHint, ready)
        try {
            player.setMediaData(source.toMediampData(), playWhenReady = playWhenReady)
            snapshotTracks()
            ready.set(true)
            val state = player.state.value
            if (state.mediaStatus == MediaStatus.Ended) {
                scope.launch { eventFlow.emit(BackendEvent.PlaybackEnded(sessionId)) }
            }
            OpenedMedia(
                timeline = currentTimeline(source.kindHint),
                audioTracks = audio,
                subtitleTracks = subtitles,
                videoTracks = video,
                selectedAudioTrackId = selectedAudioId,
                selectedSubtitleTrackId = selectedSubtitleId,
                selectedVideoTrackId = selectedVideoId,
                playWhenReady = state.playWhenReady,
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
            )
        } catch (error: CancellationException) {
            endFailedOpen(sessionId)
            throw error
        } catch (error: PlaybackException) {
            endFailedOpen(sessionId)
            throw PlaybackFailure(error.toAirError())
        } catch (_: Exception) {
            endFailedOpen(sessionId)
            throw PlaybackFailure(
                PlaybackError(PlaybackErrorCode.Internal, "MPV could not open the media", false),
            )
        }
    }

    private fun observeSession(
        sessionId: PlaybackSessionId,
        kindHint: PlaybackKind?,
        ready: AtomicBoolean,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        supervisorScope {
            launch {
                player.state.collect { state ->
                    if (!isCurrent(sessionId) ||
                        (state.mediaStatus != MediaStatus.Ready && state.mediaStatus != MediaStatus.Ended)
                    ) {
                        return@collect
                    }
                    eventFlow.tryEmit(
                        BackendEvent.PlaybackChanged(sessionId, state.isPlaying, state.playWhenReady),
                    )
                    eventFlow.tryEmit(BackendEvent.BufferingChanged(sessionId, state.isBuffering))
                }
            }
            launch {
                player.currentPositionMillis.collect { position ->
                    if (isCurrent(sessionId)) {
                        eventFlow.tryEmit(BackendEvent.PositionChanged(sessionId, position.coerceAtLeast(0)))
                    }
                }
            }
            launch {
                player.events.collect { event ->
                    if (!isCurrent(sessionId) || !ready.get()) return@collect
                    when (event) {
                        is PlaybackEvent.MediaEnded -> eventFlow.tryEmit(BackendEvent.PlaybackEnded(sessionId))
                        is PlaybackEvent.SeekCompleted -> eventFlow.tryEmit(
                            BackendEvent.SeekFinished(sessionId, event.positionMillis.coerceAtLeast(0)),
                        )
                        is PlaybackEvent.ErrorOccurred -> eventFlow.tryEmit(
                            BackendEvent.Failed(sessionId, event.error.toAirError()),
                        )
                        is PlaybackEvent.ExternalPlayWhenReadyChanged -> Unit
                    }
                }
            }
            launch {
                val timeline = player.features[MediaTimeline]?.snapshot
                if (timeline != null) {
                    timeline.collect { snapshot ->
                        if (isCurrent(sessionId)) {
                            eventFlow.tryEmit(
                                BackendEvent.TimelineChanged(
                                    sessionId,
                                    snapshot.toAirTimeline(kindHint, player.mediaProperties.value?.durationMillis),
                                ),
                            )
                        }
                    }
                } else {
                    player.mediaProperties.collect { properties ->
                        if (isCurrent(sessionId)) {
                            eventFlow.tryEmit(
                                BackendEvent.TimelineChanged(
                                    sessionId,
                                    MediaTimelineSnapshot(durationMillis = properties?.durationMillis)
                                        .toAirTimeline(kindHint, properties?.durationMillis),
                                ),
                            )
                        }
                    }
                }
            }
            observeTracks(sessionId)
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.observeTracks(sessionId: PlaybackSessionId) {
        val metadata = player.features[MediaMetadata] ?: return
        metadata.audioTracks?.let { group ->
            launch {
                group.candidates.collect { tracks ->
                    audioTargets = tracks.associateBy(MediampAudioTrack::id)
                    audio = tracks.map(MediampAudioTrack::toAirTrack)
                    publishTracks(sessionId)
                }
            }
            launch {
                group.selected.collect { selected ->
                    selectedAudioId = selected?.id
                    publishTracks(sessionId)
                }
            }
        }
        metadata.subtitleTracks?.let { group ->
            launch {
                group.candidates.collect { tracks ->
                    subtitleTargets = tracks.associateBy(MediampSubtitleTrack::id)
                    subtitles = tracks.map(MediampSubtitleTrack::toAirTrack)
                    publishTracks(sessionId)
                }
            }
            launch {
                group.selected.collect { selected ->
                    selectedSubtitleId = selected?.id
                    publishTracks(sessionId)
                }
            }
        }
        metadata.videoTracks?.let { group ->
            launch {
                group.candidates.collect { tracks ->
                    videoTargets = tracks.associateBy(MediampVideoTrack::id)
                    video = tracks.map(MediampVideoTrack::toAirTrack)
                    publishTracks(sessionId)
                }
            }
            launch {
                group.selected.collect { selected ->
                    selectedVideoId = selected?.id
                    publishTracks(sessionId)
                }
            }
        }
    }

    private suspend fun snapshotTracks() {
        val metadata = player.features[MediaMetadata]
        metadata?.audioTracks?.snapshot()?.let { (tracks, selected) ->
            audioTargets = tracks.associateBy(MediampAudioTrack::id)
            audio = tracks.map(MediampAudioTrack::toAirTrack)
            selectedAudioId = selected?.id
        }
        metadata?.subtitleTracks?.snapshot()?.let { (tracks, selected) ->
            subtitleTargets = tracks.associateBy(MediampSubtitleTrack::id)
            subtitles = tracks.map(MediampSubtitleTrack::toAirTrack)
            selectedSubtitleId = selected?.id
        }
        metadata?.videoTracks?.snapshot()?.let { (tracks, selected) ->
            videoTargets = tracks.associateBy(MediampVideoTrack::id)
            video = tracks.map(MediampVideoTrack::toAirTrack)
            selectedVideoId = selected?.id
        }
    }

    private fun publishTracks(sessionId: PlaybackSessionId) {
        if (!isCurrent(sessionId)) return
        eventFlow.tryEmit(
            BackendEvent.TracksChanged(
                sessionId,
                audio,
                subtitles,
                video,
                selectedAudioId,
                selectedSubtitleId,
                selectedVideoId,
            ),
        )
    }

    override fun play() = onPlayerThreadBlocking { player.play() }
    override fun pause() = onPlayerThreadBlocking { player.pause() }
    override fun seekTo(positionMillis: Long) = onPlayerThreadBlocking {
        player.seekTo(positionMillis.coerceAtLeast(0))
    }

    override fun selectAudioTrack(id: String?): TrackSelectionResult = selectTrack(
        id,
        audioTargets,
        player.features[MediaMetadata]?.audioTracks,
    )

    override fun selectSubtitleTrack(id: String?): TrackSelectionResult = selectTrack(
        id,
        subtitleTargets,
        player.features[MediaMetadata]?.subtitleTracks,
    )

    override fun selectVideoTrack(id: String?): TrackSelectionResult = selectTrack(
        id,
        videoTargets,
        player.features[MediaMetadata]?.videoTracks,
    )

    private fun <T> selectTrack(
        id: String?,
        targets: Map<String, T>,
        group: TrackGroup<T>?,
    ): TrackSelectionResult {
        if (group == null) return TrackSelectionResult.NotSupported
        val target = id?.let(targets::get)
        if (id != null && target == null) return TrackSelectionResult.NotFound(id)
        val selected = onPlayerThreadBlocking { group.select(target) }
        if (!selected) return TrackSelectionResult.NotSupported
        return TrackSelectionResult.Requested(id)
    }

    override fun stop() {
        if (released) return
        activeSessionId = null
        observationJob?.cancel()
        observationJob = null
        clearTrackSnapshots()
        onPlayerThreadBlocking { player.stopPlayback() }
    }

    override fun close() {
        if (released) return
        released = true
        activeSessionId = null
        observationJob?.cancel()
        observationJob = null
        clearTrackSnapshots()
        player.close()
        scope.cancel()
    }

    private fun endFailedOpen(sessionId: PlaybackSessionId) {
        if (!isCurrent(sessionId)) return
        activeSessionId = null
        observationJob?.cancel()
        observationJob = null
        clearTrackSnapshots()
    }

    private fun isCurrent(sessionId: PlaybackSessionId): Boolean =
        !released && activeSessionId == sessionId

    private fun currentTimeline(kindHint: PlaybackKind?): PlaybackTimeline {
        val snapshot = player.features[MediaTimeline]?.snapshot?.value
            ?: MediaTimelineSnapshot(durationMillis = player.mediaProperties.value?.durationMillis)
        return snapshot.toAirTimeline(kindHint, player.mediaProperties.value?.durationMillis)
    }

    private fun clearTrackSnapshots() {
        audioTargets = emptyMap()
        subtitleTargets = emptyMap()
        videoTargets = emptyMap()
        audio = emptyList()
        subtitles = emptyList()
        video = emptyList()
        selectedAudioId = null
        selectedSubtitleId = null
        selectedVideoId = null
    }
}

private suspend fun <T> TrackGroup<T>.snapshot(): Pair<List<T>, T?> = candidates.first() to selected.value

private fun PlaybackSource.toMediampData() = UriMediaData(
    uri = uri,
    headers = headers,
    extraFiles = MediaExtraFiles(externalSubtitles.map(ExternalSubtitleSource::toMediampSubtitle)),
)

private fun ExternalSubtitleSource.toMediampSubtitle(): Subtitle = Subtitle(
    uri = uri,
    mimeType = mimeType,
    language = language,
    label = label,
)

@JvmSynthetic
internal fun MediaTimelineSnapshot.toAirTimeline(
    kindHint: PlaybackKind?,
    mediaDurationMillis: Long?,
): PlaybackTimeline {
    val duration = durationMillis ?: mediaDurationMillis
    val latestRange = seekableRanges.maxByOrNull { it.endMillis }?.let { range ->
        val start = range.startMillis.coerceAtLeast(0)
        val end = range.endMillis.coerceAtLeast(start)
        SeekableRange(start, end)
    }
    val fallbackRange = if (acceptsSeek && duration != null) SeekableRange(0, duration) else null
    return when (kindHint) {
        PlaybackKind.Live -> PlaybackTimeline(
            kind = PlaybackKind.Live,
            liveEdgeMillis = latestRange?.endMillis ?: duration,
        )
        PlaybackKind.SeekableLive -> PlaybackTimeline(
            kind = PlaybackKind.SeekableLive,
            seekableRange = latestRange ?: fallbackRange,
            liveEdgeMillis = latestRange?.endMillis ?: duration,
        )
        PlaybackKind.OnDemand -> PlaybackTimeline(
            kind = PlaybackKind.OnDemand,
            durationMillis = duration ?: 0,
        )
        null -> when {
            duration != null -> PlaybackTimeline(PlaybackKind.OnDemand, durationMillis = duration)
            acceptsSeek -> PlaybackTimeline(
                kind = PlaybackKind.SeekableLive,
                seekableRange = latestRange,
                liveEdgeMillis = latestRange?.endMillis,
            )
            else -> PlaybackTimeline(PlaybackKind.Live)
        }
    }
}

@JvmSynthetic
internal fun MediampAudioTrack.toAirTrack(): AudioTrack = AudioTrack(
    id = id,
    label = name ?: labels.firstOrNull()?.value ?: "Audio",
    language = labels.firstOrNull()?.language,
    isDefault = isDefault,
    isForced = isForced,
    channels = channels,
    codec = codec,
)

@JvmSynthetic
internal fun MediampSubtitleTrack.toAirTrack(): SubtitleTrack = SubtitleTrack(
    id = id,
    label = labels.firstOrNull()?.value ?: language ?: "Subtitles",
    language = language,
    isDefault = isDefault,
    isForced = isForced,
    format = format,
    external = external,
)

@JvmSynthetic
internal fun MediampVideoTrack.toAirTrack(): VideoTrack = VideoTrack(
    id = id,
    label = labels.firstOrNull()?.value ?: resolutionLabel(width, height),
    language = language,
    isDefault = isDefault,
    isForced = isForced,
    width = width,
    height = height,
    bitrate = bitrate,
    codec = codec,
)

private fun resolutionLabel(width: Int?, height: Int?): String = when {
    height != null -> "${height}p"
    width != null -> "${width}px"
    else -> "Video"
}

@JvmSynthetic
internal fun PlaybackException.toAirError(): PlaybackError = when (code) {
    org.openani.mediamp.PlaybackErrorCode.UNSUPPORTED_FORMAT -> PlaybackError(
        PlaybackErrorCode.UnsupportedCodec,
        "MPV does not support the media format",
        recoverable = false,
    )
    org.openani.mediamp.PlaybackErrorCode.IO -> PlaybackError(
        PlaybackErrorCode.Network,
        "MPV could not read the media source",
        recoverable = true,
    )
    org.openani.mediamp.PlaybackErrorCode.DECODING -> PlaybackError(
        PlaybackErrorCode.Decode,
        "MPV could not decode the media",
        recoverable = false,
    )
    org.openani.mediamp.PlaybackErrorCode.ACCESS_DENIED -> PlaybackError(
        PlaybackErrorCode.Source,
        "MPV could not access the media source",
        recoverable = false,
    )
    org.openani.mediamp.PlaybackErrorCode.INTERNAL -> PlaybackError(
        PlaybackErrorCode.Internal,
        "MPV playback failed",
        recoverable = false,
    )
}

private val MEDIAMP_DESKTOP_CAPABILITIES = PlayerCapabilities(
    containers = setOf("mkv", "matroska", "mp4", "mpegts", "ts", "webm"),
    videoCodecs = setOf("h264", "hevc", "av1", "vp8", "vp9", "mpeg2video", "mpeg4"),
    audioCodecs = setOf("aac", "ac3", "eac3", "dts", "flac", "mp3", "opus", "vorbis"),
    subtitleFormats = setOf("ass", "ssa", "srt", "vtt", "pgs"),
    adaptiveProtocols = setOf("hls", "dash"),
    supportsAudioTrackSelection = true,
    supportsSubtitleTrackSelection = true,
    supportsVideoTrackSelection = true,
    supportsExternalSubtitles = true,
    supportsLive = true,
    supportsSeekableLive = true,
    supportsPlaybackRate = true,
    supportsMovableSurface = true,
    supportsSurfaceReattachment = true,
    supportsCompositedOverlays = true,
    hardwareAcceleration = HardwareAcceleration.Unknown,
)

private fun <T> onPlayerThreadBlocking(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val task = FutureTask(block)
    EventQueue.invokeLater(task)
    return task.get()
}
