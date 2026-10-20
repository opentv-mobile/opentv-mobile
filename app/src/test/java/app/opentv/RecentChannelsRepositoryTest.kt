package app.opentv.widget

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RecentChannelsRepositoryTest {
    private fun candidate(id: Long, group: String = "", rank: Int = 0) = RecentChannelCandidate(
        id, "Channel $id", null, group, rank, "safe", null, "News",
    )

    @Test fun `MRU logical group order survives shuffled SQL rows and best variant selection`() {
        val rows = listOf(candidate(1, "one", 1), candidate(2, "two"), candidate(3, "one", 3), candidate(4))
        assertThat(selectRecentChannels(listOf(1, 2, 1, 3, 99, 4), rows.reversed(), emptySet(), 6).map { it.id })
            .containsExactly(3L, 2L, 4L).inOrder()
        // Blank group keys must not collapse unrelated channels; equal quality retains MRU variant.
        assertThat(selectRecentChannels(listOf(5, 4, 1, 6), rows + candidate(5) + candidate(6, "one", 1), emptySet(), 6)
            .map { it.id }).containsExactly(5L, 4L, 1L).inOrder()
    }

    @Test fun `parental key matching follows Live regional and legacy folding before dedup`() {
        val protected = candidate(1, "same", 9).copy(categoryName = "UK| ADULT HD")
        val safe = candidate(2, "same", 1)
        assertThat(protected.isProtected(setOf("UK:adult"))).isTrue()
        assertThat(protected.isProtected(setOf("adult"))).isTrue()
        assertThat(protected.isProtected(setOf("ES:adult"))).isFalse()
        assertThat(selectRecentChannels(listOf(1, 2), listOf(protected, safe), setOf("adult"), 6))
            .containsExactly(safe)
        assertThat(selectRecentChannels(listOf(2, 1), listOf(safe, protected), setOf("adult"), 6))
            .containsExactly(safe) // A later higher-quality protected sibling cannot replace it.
        assertThat(protected.copy(categoryName = null).isProtected(setOf("adult"))).isTrue()
    }

    @Test fun `bounded repository filters disabled missing hidden and protected even session unlocked and refreshes local edits`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings.get(context)
        val oldHidden = settings.hiddenCategories.value
        val oldLive = settings.liveEnabled.value
        val oldUnlocked = settings.hiddenUnlocked.value
        val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
        try {
            val source = Source(id = 1, name = "Local", kind = SourceKind.M3U, url = "",
                localPlaylistFileName = "private-list.m3u")
            db.sources().insert(source)
            db.sources().insert(source.copy(id = 2, enabled = false))
            db.categories().upsertAll(listOf(
                Category("safe", 1, "News", StreamKind.LIVE),
                Category("adult", 1, "UK| ADULT HEVC", StreamKind.LIVE),
            ))
            fun channel(id: Long) = Channel(id, 1, "$id", "Channel $id", categoryId = "safe",
                logoUrl = null, epgChannelId = null, number = null, streamUrl = "https://secret/user/password/$id")
            db.channels().upsertAll(listOf(channel(1), channel(2).copy(hidden = true),
                channel(3).copy(sourceId = 2), channel(4).copy(categoryId = "adult"),
                channel(5).copy(sourceId = 99)))
            settings.setHiddenCategories(setOf("adult"))
            settings.setHiddenUnlocked(true)
            settings.setLiveEnabled(true)
            val repo = RecentChannelsRepository(db.channels())
            val recent = listOf(4L, 3L, 2L, 99L, 5L, 1L)
            assertThat(repo.recent(recent, settings.hiddenCategories.value, true).map { it.id }).containsExactly(1L)
            assertThat(repo.playable(4, settings)).isNull()
            assertThat(repo.playable(3, settings)).isNull()
            db.channels().setCustomName(1, "Renamed")
            assertThat(repo.playable(1, settings)?.name).isEqualTo("Renamed")
            db.sources().update(source.copy(enabled = false))
            assertThat(repo.playable(1, settings)).isNull()
            db.sources().update(source)
            db.channels().deleteForSource(1)
            assertThat(repo.recent(recent, emptySet(), true)).isEmpty()
            db.channels().upsertAll(listOf(channel(1))) // local reimport becomes available immediately
            assertThat(repo.playable(1, settings)).isNotNull()
            settings.setLiveEnabled(false)
            assertThat(repo.playable(1, settings)).isNull()
            assertThat(repo.recent(recent, emptySet(), false)).isEmpty()
        } finally {
            db.close()
            settings.setHiddenCategories(oldHidden)
            settings.setHiddenUnlocked(oldUnlocked)
            settings.setLiveEnabled(oldLive)
        }
    }

    @Test fun `widget capacity is bounded to four through six`() {
        assertThat(RecentWidgetUpdates.entryCount(252)).isEqualTo(4)
        assertThat(RecentWidgetUpdates.entryCount(292)).isEqualTo(5)
        assertThat(RecentWidgetUpdates.entryCount(340)).isEqualTo(6)
        assertThat(RecentWidgetUpdates.entryCount(2000)).isEqualTo(6)
        val rows = (1L..10L).map { candidate(it) }
        assertThat(selectRecentChannels((1L..10L).toList(), rows, emptySet(), 100)).hasSize(6)
    }

    @Test fun `disabled live and empty history avoid reads and oversized history stays bounded`() = runTest {
        var reads = 0
        val repo = RecentChannelsRepository { ids ->
            reads++
            assertThat(ids).hasSize(20)
            ids.map { candidate(it) }
        }
        assertThat(repo.recent(listOf(1), emptySet(), false)).isEmpty()
        assertThat(repo.recent(emptyList(), emptySet(), true)).isEmpty()
        assertThat(reads).isEqualTo(0)
        assertThat(repo.recent((1L..2000L).toList(), emptySet(), true).map { it.id })
            .containsExactly(1L, 2L, 3L, 4L, 5L, 6L).inOrder()
        assertThat(reads).isEqualTo(1)
    }
}
