package app.opentv.ui.channels

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Calendar navigation is local; lengths and positions are elapsed UTC time (including DST). */
object PhoneGuideTimeline {
    const val HOUR = 3_600_000L
    const val HALF_HOUR = HOUR / 2

    data class Window(val start: Long, val end: Long)

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val ambiguousClock = DateTimeFormatter.ofPattern("HH:mm xxx")

    /** Offset is useful only in a repeated local hour, not on every ordinary timeline tick. */
    fun timeLabel(millis: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        val ambiguous = zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
        return local.format(if (ambiguous) ambiguousClock else clock)
    }

    fun day(date: LocalDate, zone: ZoneId): Window = Window(
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    fun date(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Three-hour cache keys, six-hour reads: pixel scrolling inside a tile never queries Room. */
    fun prefetch(visibleStart: Long): Window {
        val start = Math.floorDiv(visibleStart, 3 * HOUR) * 3 * HOUR
        return Window(start, start + 6 * HOUR)
    }

    fun position(millis: Long, start: Long, dpPerHour: Float): Float =
        (millis - start).toDouble().div(HOUR).times(dpPerHour).toFloat()

    fun clip(start: Long, end: Long, viewport: Window): Window? {
        val left = maxOf(start, viewport.start)
        val right = minOf(end, viewport.end)
        return if (left < right) Window(left, right) else null
    }
}
