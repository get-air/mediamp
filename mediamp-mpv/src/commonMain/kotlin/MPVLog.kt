/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlin.concurrent.Volatile

/**
 * A single log line emitted by the mpv backend. Sources:
 *  - mpv itself (`MPV_EVENT_LOG_MESSAGE`), keeping mpv's own [prefix] and level;
 *  - the mediamp native (JNI) layer, with prefix `mediampv`;
 *  - mediamp Kotlin code, with prefix `mediamp`.
 *
 * [level] follows mpv's `mpv_log_level` scale, where a *lower* number is more severe
 * (see [MPVLog]).
 */
public class MPVLogMessage(
    public val instanceHandle: Long,
    public val level: Int,
    public val prefix: String,
    public val line: String,
) {
    /** True for fatal/error lines (mpv scale <= [MPVLog.ERROR]). */
    public val isError: Boolean get() = level in MPVLog.FATAL..MPVLog.ERROR

    override fun toString(): String = "[$prefix] $line"
}

public fun interface MPVLogHandler {
    public fun onLog(message: MPVLogMessage)
}

/**
 * Central log sink for the whole mpv backend: mpv's own messages, the native JNI layer,
 * and mediamp Kotlin code all funnel through here so a single [MPVLogHandler] (installed
 * via [MPVHandle.setLogHandler]) observes everything on one scale.
 *
 * Levels mirror mpv's `mpv_log_level` (client.h): **lower is more severe**.
 */
public object MPVLog {
    public const val FATAL: Int = 10 // critical, aborting errors
    public const val ERROR: Int = 20 // simple errors
    public const val WARN: Int = 30  // possible problems
    public const val INFO: Int = 40  // informational messages
    public const val V: Int = 50     // noisy informational messages
    public const val DEBUG: Int = 60 // very noisy technical detail
    public const val TRACE: Int = 70 // extremely noisy

    /** Prefix used for lines originating in mediamp Kotlin code. */
    public const val PREFIX: String = "mediamp"

    @Volatile
    private var handler: MPVLogHandler? = null

    internal fun setHandler(handler: MPVLogHandler?) {
        this.handler = handler
    }

    /**
     * Emits a log line. [throwable], when present, is appended as its full stack trace so
     * exception detail is never swallowed. When no handler is installed, warnings and
     * errors still surface via [println] so real problems are not lost silently; quieter
     * levels are dropped.
     */
    public fun log(instanceHandle: Long, level: Int, message: String, throwable: Throwable? = null, prefix: String = PREFIX) {
        val body = if (throwable == null) {
            message
        } else {
            buildString {
                append(message)
                append('\n')
                append(throwable.stackTraceToString())
            }
        }
        val normalized = redactMpvLog(body.trimEnd('\r', '\n'))
        if (normalized.isEmpty()) {
            return
        }

        val current = handler
        if (current != null) {
            current.onLog(MPVLogMessage(instanceHandle, level, prefix, normalized))
        } else if (level <= WARN) {
            // No sink installed: don't silently swallow problems.
            println("[$prefix] $normalized")
        }
    }

    public fun fatal(handle: Long, message: String, throwable: Throwable? = null): Unit = log(handle, FATAL, message, throwable)
    public fun error(handle: Long, message: String, throwable: Throwable? = null): Unit = log(handle, ERROR, message, throwable)
    public fun warn(handle: Long, message: String, throwable: Throwable? = null): Unit = log(handle, WARN, message, throwable)
    public fun info(handle: Long, message: String, throwable: Throwable? = null): Unit = log(handle, INFO, message, throwable)
    public fun debug(handle: Long, message: String, throwable: Throwable? = null): Unit = log(handle, DEBUG, message, throwable)
    public fun verbose(handle: Long, message: String, throwable: Throwable? = null): Unit = log(handle, V, message, throwable)
}

private const val MAX_LOG_CHARS = 16 * 1024
private val URI_IN_LOG = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s'\\\"<>]+")
private val MEDIA_PATH_IN_LOG = Regex(
    "(?:[A-Za-z]:[\\\\/]|/)[^\\s'\\\"<>]+\\.(?:mkv|mp4|m4v|webm|ts|m2ts|m3u8?|mpd|srt|ass|ssa|vtt)(?:\\?[^\\s'\\\"<>]*)?",
    RegexOption.IGNORE_CASE,
)
private val SENSITIVE_HEADER_IN_LOG = Regex(
    "(?:authorization|proxy-authorization|cookie|set-cookie|x-[A-Za-z0-9-]*(?:token|key)|api[-_]key)\\s*[:=]\\s*(?:Bearer\\s+)?[^\\s,;]+",
    RegexOption.IGNORE_CASE,
)
private val BEARER_IN_LOG = Regex("\\bBearer\\s+[^\\s,;]+", RegexOption.IGNORE_CASE)

/** Redacts external media locations and credentials before any log sink observes them. */
internal fun redactMpvLog(value: String): String {
    val redacted = value
        .replace(URI_IN_LOG, "<redacted-uri>")
        .replace(MEDIA_PATH_IN_LOG, "<redacted-media-path>")
        .replace(SENSITIVE_HEADER_IN_LOG) { match ->
            val separator = match.value.indexOfFirst { it == ':' || it == '=' }
            val name = match.value.take(separator.coerceAtLeast(0)).trim()
            "$name=<redacted>"
        }
        .replace(BEARER_IN_LOG, "Bearer <redacted>")
    return if (redacted.length <= MAX_LOG_CHARS) redacted
    else redacted.take(MAX_LOG_CHARS) + "… <truncated>"
}

@Suppress("unused") // Called from JNI as MPVLogKt.onNativeLog(int, String, String).
public fun onNativeLog(instanceHandle: Long, level: Int, prefix: String, message: String) {
    MPVLog.log(instanceHandle, level, message, prefix = prefix)
}
