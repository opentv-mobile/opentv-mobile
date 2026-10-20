package app.opentv

import app.opentv.ui.channels.PhoneGuideTimeline as Timeline
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class PhoneGuideTimelineTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun label(instant: String, zone: ZoneId = madrid) =
        Timeline.timeLabel(Instant.parse(instant).toEpochMilli(), zone)

    @Test fun `ordinary local clocks omit offsets including non whole hour zones`() {
        assertThat(label("2026-10-20T12:00:00Z")).isEqualTo("14:00")
        assertThat(label("2026-10-20T12:00:00Z", ZoneId.of("Asia/Kathmandu"))).isEqualTo("17:45")
        assertThat(label("2026-10-20T12:00:00Z", ZoneId.of("UTC"))).isEqualTo("12:00")
    }

    @Test fun `only repeated local times show their distinguishing offsets`() {
        assertThat(label("2026-10-25T00:30:00Z")).isEqualTo("02:30 +02:00")
        assertThat(label("2026-10-25T01:30:00Z")).isEqualTo("02:30 +01:00")
        assertThat(label("2026-10-25T02:00:00Z")).isEqualTo("03:00")
        assertThat(label("2026-03-29T00:30:00Z")).isEqualTo("01:30")
        assertThat(label("2026-03-29T01:00:00Z")).isEqualTo("03:00")
    }

    @Test fun `half hour autumn overlap is also disambiguated`() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        assertThat(label("2026-04-04T14:45:00Z", zone)).isEqualTo("01:45 +11:00")
        assertThat(label("2026-04-04T15:15:00Z", zone)).isEqualTo("01:45 +10:30")
        assertThat(label("2026-04-04T15:30:00Z", zone)).isEqualTo("02:00")
    }

    @Test fun `local days span 23 and 25 real hours across DST`() {
        val spring = Timeline.day(LocalDate.of(2026, 3, 29), madrid)
        val autumn = Timeline.day(LocalDate.of(2026, 10, 25), madrid)
        assertThat(spring.end - spring.start).isEqualTo(23 * Timeline.HOUR)
        assertThat(autumn.end - autumn.start).isEqualTo(25 * Timeline.HOUR)
        assertThat(Timeline.date(spring.end, madrid)).isEqualTo(LocalDate.of(2026, 3, 30))
        assertThat(Timeline.date(autumn.end, madrid)).isEqualTo(LocalDate.of(2026, 10, 26))
    }

    @Test fun `repeated autumn wall times remain an hour apart on timeline`() {
        val first = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        val second = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        assertThat(Instant.ofEpochMilli(first).atZone(madrid).hour).isEqualTo(2)
        assertThat(Instant.ofEpochMilli(second).atZone(madrid).hour).isEqualTo(2)
        assertThat(Timeline.position(second, first, 180f)).isEqualTo(180f)
    }

    @Test fun `short shows have exact width and half open clipping`() {
        assertThat(Timeline.position(60_000, 0, 180f)).isEqualTo(3f)
        val view = Timeline.Window(100, 200)
        assertThat(Timeline.clip(0, 100, view)).isNull()
        assertThat(Timeline.clip(200, 300, view)).isNull()
        assertThat(Timeline.clip(50, 250, view)).isEqualTo(view)
        assertThat(Timeline.clip(150, 150, view)).isNull()
    }

    @Test fun `six hour prefetch stable within three hour tile covers viewport`() {
        val base = 12 * Timeline.HOUR
        val tile = Timeline.prefetch(base)
        assertThat(Timeline.prefetch(base + Timeline.HOUR)).isEqualTo(tile)
        assertThat(Timeline.prefetch(base + 3 * Timeline.HOUR - 1)).isEqualTo(tile)
        assertThat(tile.end - tile.start).isEqualTo(6 * Timeline.HOUR)
        assertThat(Timeline.prefetch(base + 3 * Timeline.HOUR)).isNotEqualTo(tile)
    }
}
