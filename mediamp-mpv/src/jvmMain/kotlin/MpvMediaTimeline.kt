/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.features.MediaTimeline
import org.openani.mediamp.features.MediaTimelineSnapshot
import org.openani.mediamp.features.SeekableTimeRange
import kotlin.math.roundToLong

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvMediaTimeline(
    private val handle: MPVHandle,
) : MediaTimeline {
    override val snapshot: MutableStateFlow<MediaTimelineSnapshot> =
        MutableStateFlow(MediaTimelineSnapshot())

    /** Reads level-triggered native facts. Safe to call for every relevant property event. */
    fun refresh() {
        val durationMillis = (handle.getPropertyDouble("duration") * 1_000)
            .roundToLong()
            .takeIf { it > 0 }
        snapshot.value = MediaTimelineSnapshot(
            durationMillis = durationMillis,
            seekable = handle.getPropertyBoolean("seekable"),
            partiallySeekable = handle.getPropertyBoolean("partially-seekable"),
            seekableRanges = parseMpvSeekableRanges(handle.getPropertyString("demuxer-cache-state")),
        )
    }

    fun clear() {
        snapshot.value = MediaTimelineSnapshot()
    }
}

private val SEEKABLE_RANGES = Regex(
    pattern = "\\\"seekable-ranges\\\"\\s*:\\s*\\[(.*?)]",
    option = RegexOption.DOT_MATCHES_ALL,
)
private val RANGE_OBJECT = Regex("\\{([^{}]*)}")
private const val JSON_NUMBER = "-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
private val RANGE_START = Regex("\\\"start\\\"\\s*:\\s*($JSON_NUMBER)")
private val RANGE_END = Regex("\\\"end\\\"\\s*:\\s*($JSON_NUMBER)")
private const val MAX_SEEKABLE_RANGES = 256

/** Parses mpv's MPV_FORMAT_STRING rendering of the `demuxer-cache-state` node. */
internal fun parseMpvSeekableRanges(value: String?): List<SeekableTimeRange> {
    val body = value?.let(SEEKABLE_RANGES::find)?.groupValues?.get(1) ?: return emptyList()
    val ranges = RANGE_OBJECT.findAll(body)
        .take(MAX_SEEKABLE_RANGES)
        .mapNotNull { match ->
            val fields = match.groupValues[1]
            val start = RANGE_START.find(fields)?.groupValues?.get(1)?.toDoubleOrNull()
            val end = RANGE_END.find(fields)?.groupValues?.get(1)?.toDoubleOrNull()
            if (start == null || end == null || !start.isFinite() || !end.isFinite() || end < start) {
                null
            } else {
                SeekableTimeRange((start * 1_000).roundToLong(), (end * 1_000).roundToLong())
            }
        }
        .sortedWith(compareBy(SeekableTimeRange::startMillis, SeekableTimeRange::endMillis))
        .toList()
    if (ranges.size < 2) return ranges

    return buildList {
        for (range in ranges) {
            val previous = lastOrNull()
            if (previous == null || range.startMillis > previous.endMillis) {
                add(range)
            } else if (range.endMillis > previous.endMillis) {
                this[lastIndex] = previous.copy(endMillis = range.endMillis)
            }
        }
    }
}
