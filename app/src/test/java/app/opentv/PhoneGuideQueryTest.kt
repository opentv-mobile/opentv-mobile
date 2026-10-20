package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PhoneGuideQueryTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), OpenTvDatabase::class.java,
    ).build()
    private val dao = database.programmes()
    @After fun close() = database.close()

    private fun programme(channel: String, start: Long, end: Long) = Programme(
        feedId = 1, epgChannelId = channel, startUtcMillis = start, endUtcMillis = end, title = "$channel:$start",
    )

    @Test fun `now next includes old started live show and next beyond six hours without loading whole future`() = runTest {
        val hour = 3_600_000L
        dao.upsertAll(listOf(programme("a", -12 * hour, 8 * hour), programme("a", 8 * hour, 9 * hour),
            programme("a", 9 * hour, 10 * hour), programme("a", -hour, 0), programme("b", 20 * hour, 21 * hour)))
        val result = dao.phoneNowNext(listOf("a", "a", "", "b"), 0)
        assertThat(result.filter { it.epgChannelId == "a" }.map { it.startUtcMillis })
            .containsExactly(-12 * hour, 8 * hour).inOrder()
        assertThat(result.filter { it.epgChannelId == "b" }.map { it.startUtcMillis }).containsExactly(20 * hour)
        assertThat(dao.phoneNowNext(emptyList(), 0)).isEmpty()
    }

    @Test fun `query restricts candidates and uses overlapping half open window`() = runTest {
        dao.upsertAll(listOf(programme("a", 0, 100), programme("a", 100, 200),
            programme("a", 200, 300), programme("b", 100, 200), programme("c", 50, 250)))
        val result = dao.phoneWindow(listOf("a", "a", "", "c"), 100, 200)
        assertThat(result.map { it.epgChannelId }).containsExactly("a", "c")
        assertThat(result.first { it.epgChannelId == "a" }.startUtcMillis).isEqualTo(100L)
        assertThat(dao.phoneWindow(emptyList(), 100, 200)).isEmpty()
        assertThat(dao.phoneWindow(listOf("a"), 200, 100)).isEmpty()
        val horizon = dao.phoneHorizon()
        assertThat(horizon.start).isEqualTo(0L)
        assertThat(horizon.end).isEqualTo(300L)
    }

    @Test fun `more than SQLite legacy parameter limit is batched without duplicates or missing channels`() = runTest {
        val ids = (1..2005).map { "channel-$it" }
        dao.upsertAll(ids.map { programme(it, 100, 200) } + programme("not-visible", 100, 200))
        val result = dao.phoneWindow(ids + ids.take(20), 100, 200)
        assertThat(result).hasSize(2005)
        assertThat(result.map { it.epgChannelId }).containsExactlyElementsIn(ids)
        assertThat(dao.phoneNowNext(ids + ids.take(20), 150).map { it.epgChannelId }).containsExactlyElementsIn(ids)
    }

    @Test fun `now next query does not depend on future browse window and reread sees new feed`() = runTest {
        dao.upsertAll(listOf(programme("matched", 100, 200), programme("matched", 200, 300),
            programme("matched", 10_000, 11_000)))
        assertThat(dao.phoneWindow(listOf("override", "matched"), 10_000, 11_000)).hasSize(1)
        val live = dao.phoneWindow(listOf("override", "matched"), 150, 1000)
        assertThat(live.first { it.isLiveAt(150) }.startUtcMillis).isEqualTo(100L)
        assertThat(live.first { it.startUtcMillis > 150 }.startUtcMillis).isEqualTo(200L)
        dao.upsertAll(listOf(programme("override", 100, 200)))
        assertThat(dao.phoneWindow(listOf("override", "matched"), 150, 1000)
            .map { it.epgChannelId }).contains("override")
    }
}
