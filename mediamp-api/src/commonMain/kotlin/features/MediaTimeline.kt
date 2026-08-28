/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer

/** A native timestamp interval that the active backend currently accepts for seeking. */
public data class SeekableTimeRange(
    public val startMillis: Long,
    public val endMillis: Long,
) {
    init {
        require(endMillis >= startMillis)
    }
}

/**
 * Backend facts used to distinguish fixed-duration media, plain live streams, and
 * live streams with a moving DVR/cache window. Source policy remains the app's job.
 */
public data class MediaTimelineSnapshot(
    public val durationMillis: Long? = null,
    public val seekable: Boolean = false,
    public val partiallySeekable: Boolean = false,
    public val seekableRanges: List<SeekableTimeRange> = emptyList(),
) {
    init {
        require(durationMillis == null || durationMillis >= 0)
    }

    public val acceptsSeek: Boolean get() = seekable || partiallySeekable || seekableRanges.isNotEmpty()
}

@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediaTimeline : Feature {
    public val snapshot: StateFlow<MediaTimelineSnapshot>

    public companion object Key : FeatureKey<MediaTimeline>
}

/** Stable shortcut to the backend timeline feature, when one is available. */
public val MediampPlayer.timeline: StateFlow<MediaTimelineSnapshot>?
    get() = features[MediaTimeline]?.snapshot
