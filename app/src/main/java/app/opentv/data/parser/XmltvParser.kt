/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import android.util.Xml
import app.opentv.data.model.Programme
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser

/**
 * Streaming XMLTV guide parser.
 *
 * Two properties matter more than anything else here, because between them they account
 * for most of the "my EPG is broken" reports that plague IPTV players:
 *
 * 1. **It streams.** Programmes are emitted to a callback as they are parsed rather than
 *    accumulated into a list. A week of guide data for 5,000 channels is comfortably
 *    150 MB of XML and several million programmes; nothing about that fits in the heap
 *    of a £30 TV stick. The caller batches into the database as they arrive.
 *
 * 2. **Times are parsed to UTC, correctly.** XMLTV timestamps look like
 *    `20260727183000 +0100`. The offset is frequently *not* the device's offset, and is
 *    sometimes absent entirely. Getting this wrong shifts the whole guide by hours —
 *    the single most-reported EPG bug in this category of app. We normalise to epoch
 *    millis at parse time and never store anything else.
 */
object XmltvParser {

    /** Result of a parse run, for logging and for surfacing honest errors to the user. */
    data class Stats(
        val channelCount: Int,
        val programmeCount: Int,
        val skippedProgrammes: Int,
    )

    /**
     * @param onChannelAlias called with (channelId, displayName) for every `<display-name>`.
     * @param onProgramme called for each successfully parsed `<programme>`.
     *
     * [onProgramme] is a suspending callback, and this function is therefore suspending too.
     * That is deliberate: the whole point of streaming programmes out one at a time is that the
     * caller writes them to the database as they arrive, and Room's DAO methods suspend. A
     * plain callback would force the caller into either `runBlocking` on every batch or
     * buffering the entire guide in memory — and a week of data for a large provider is
     * comfortably 150 MB of XML, which is exactly what this design exists to avoid.
     *
     * The XML reading itself is blocking, so call this from a coroutine on an IO dispatcher.
     */
    suspend fun parse(
        input: InputStream,
        feedId: Long,
        onChannelAlias: (id: String, displayName: String?) -> Unit = { _, _ -> },
        onProgramme: suspend (Programme) -> Unit,
    ): Stats {
        var channelCount = 0
        var programmeCount = 0
        var skipped = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val displayNames = readChannelDisplayNames(parser)
                        if (!id.isNullOrBlank()) {
                            channelCount++
                            if (displayNames.isEmpty()) onChannelAlias(id, null)
                            else displayNames.forEach { onChannelAlias(id, it) }
                        }
                    }

                    "programme" -> {
                        val programme = readProgramme(parser, feedId)
                        if (programme != null) {
                            programmeCount++
                            onProgramme(programme)
                        } else {
                            skipped++
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Stats(channelCount, programmeCount, skipped)
    }

    private fun readChannelDisplayNames(parser: XmlPullParser): List<String> {
        val displayNames = linkedSetOf<String>()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "display-name") {
                        parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(displayNames::add)
                        depth--
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return displayNames.toList()
            }
        }
        return displayNames.toList()
    }

    private fun readProgramme(parser: XmlPullParser, feedId: Long): Programme? {
        val channelId = parser.getAttributeValue(null, "channel")
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))

        var title: String? = null
        var description: String? = null
        var category: String? = null
        var iconUrl: String? = null
        var season: Int? = null
        var episode: Int? = null

        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> {
                            if (title == null) { title = parser.nextText().trim(); depth-- }
                        }
                        "desc" -> {
                            if (description == null) { description = parser.nextText().trim(); depth-- }
                        }
                        "category" -> {
                            if (category == null) { category = parser.nextText().trim(); depth-- }
                        }
                        "icon" -> {
                            if (iconUrl == null) iconUrl = parser.getAttributeValue(null, "src")
                        }
                        "episode-num" -> {
                            val system = parser.getAttributeValue(null, "system")
                            val value = parser.nextText().trim()
                            depth--
                            if (system == "xmltv_ns") {
                                val parts = value.split('.')
                                // xmltv_ns is zero-based and may use "1/2" for part numbering.
                                season = parts.getOrNull(0)
                                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
                                episode = parts.getOrNull(1)
                                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        // A programme with no channel, no start, or a nonsensical span is not recoverable.
        if (channelId.isNullOrBlank() || start == null) return null
        val end = stop ?: (start + DEFAULT_DURATION_MILLIS)
        if (end <= start) return null

        return Programme(
            feedId = feedId,
            epgChannelId = channelId,
            startUtcMillis = start,
            endUtcMillis = end,
            title = title?.takeIf { it.isNotEmpty() } ?: UNTITLED,
            description = description?.takeIf { it.isNotEmpty() },
            category = category?.takeIf { it.isNotEmpty() },
            season = season,
            episode = episode,
            iconUrl = iconUrl?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Parses an XMLTV timestamp to epoch millis (UTC).
     *
     * Accepts `YYYYMMDDHHMMSS`, `YYYYMMDDHHMM`, `YYYYMMDDHH`, `YYYYMMDD`, each optionally
     * followed by whitespace and a `+HHMM` / `-HHMM` offset. When the offset is missing the
     * value is interpreted as UTC — guessing the device's zone instead is what silently
     * shifts guides for users who travel or run a VPN.
     */
    fun parseXmltvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.length < 8) return null

        val remainder = trimmed.drop(digits.length).trim()
        val offsetMinutes = parseOffset(remainder) ?: 0

        return try {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(Calendar.YEAR, digits.substring(0, 4).toInt())
                set(Calendar.MONTH, digits.substring(4, 6).toInt() - 1)
                set(Calendar.DAY_OF_MONTH, digits.substring(6, 8).toInt())
                set(Calendar.HOUR_OF_DAY, digits.segmentOrZero(8))
                set(Calendar.MINUTE, digits.segmentOrZero(10))
                set(Calendar.SECOND, digits.segmentOrZero(12))
            }
            calendar.timeInMillis - offsetMinutes * 60_000L
        } catch (_: NumberFormatException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private fun String.segmentOrZero(from: Int): Int =
        if (length >= from + 2) substring(from, from + 2).toIntOrNull() ?: 0 else 0

    /** `+0100` / `-0530` / `+01:00` / `UTC` / `Z` / empty. Returns minutes east of UTC. */
    private fun parseOffset(raw: String): Int? {
        if (raw.isEmpty()) return null
        if (raw.equals("Z", true) || raw.equals("UTC", true) || raw.equals("GMT", true)) return 0

        val sign = when (raw.first()) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = raw.drop(1).replace(":", "")
        if (body.length < 4) return null
        val hours = body.substring(0, 2).toIntOrNull() ?: return null
        val minutes = body.substring(2, 4).toIntOrNull() ?: return null
        return sign * (hours * 60 + minutes)
    }

    private const val DEFAULT_DURATION_MILLIS = 30 * 60 * 1000L
    private const val UNTITLED = "No information"
}
