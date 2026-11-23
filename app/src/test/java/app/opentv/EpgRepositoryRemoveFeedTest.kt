/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class EpgRepositoryRemoveFeedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
    private val http = OkHttpClient()
    private val repository = EpgRepository(
        db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(),
        XtreamApi(http), http, db,
    )

    @After fun close() = db.close()

    private suspend fun addFeed(name: String, epgId: String): EpgFeed {
        val id = db.epgFeeds().insert(EpgFeed(name = name, url = "https://example.test/$name.xml"))
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(id, epgId, "Shared Channel", "sharedchannel")))
        db.programmes().upsertAll(listOf(
            Programme(feedId = id, epgChannelId = epgId, title = "One", startUtcMillis = 1, endUtcMillis = 2),
            Programme(feedId = id, epgChannelId = epgId, title = "Two", startUtcMillis = 3, endUtcMillis = 4),
            Programme(feedId = id, epgChannelId = epgId, title = "Three", startUtcMillis = 5, endUtcMillis = 6),
        ))
        return db.epgFeeds().all().single { it.id == id }
    }

    @Test fun `remove feed clears only its rows rebuilds once and preserves channel ids and overrides`() = runTest {
        val sourceId = db.sources().insert(Source(name = "Test", kind = SourceKind.XTREAM, url = "https://example.test"))
        val channel = Channel(
            sourceId = sourceId, streamId = "stream", name = "Shared Channel", displayName = "Shared Channel",
            groupKey = "sharedchannel", categoryId = null, logoUrl = null, epgChannelId = "provider-original",
            matchedEpgId = "stale-id", epgOverrideId = "manual-id", number = null, streamUrl = "https://stream.invalid",
        )
        db.channels().upsertAll(listOf(channel))
        val storedChannel = db.channels().allForMatching().single()
        val removed = addFeed("remove", "removed-id")
        val retained = addFeed("retain", "remaining-id")
        assertThat(repository.guideVersion.value).isEqualTo(0L)

        repository.removeFeed(removed)

        assertThat(db.epgFeeds().all().map { it.id }).containsExactly(retained.id)
        assertThat(db.programmes().countForFeed(removed.id)).isEqualTo(0)
        assertThat(db.programmes().countForFeed(retained.id)).isEqualTo(3)
        assertThat(db.epgAliases().all().map { it.feedId }).containsExactly(retained.id)
        val refreshed = db.channels().byId(storedChannel.id)!!
        assertThat(refreshed.matchedEpgId).isEqualTo("remaining-id")
        assertThat(refreshed.epgOverrideId).isEqualTo("manual-id")
        assertThat(refreshed.epgChannelId).isEqualTo("provider-original")
        assertThat(repository.guideVersion.value).isEqualTo(1L)
    }

    @Test fun `failed feed deletion rolls back and does not publish guide change`() = runTest {
        val removed = addFeed("broken", "broken-id")
        val retained = addFeed("retained", "retained-id")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_epg_alias_delete BEFORE DELETE ON epg_channels " +
                "BEGIN SELECT RAISE(ABORT, 'intentional test failure'); END",
        )

        try {
            repository.removeFeed(removed)
            error("Expected trigger to reject alias deletion")
        } catch (_: SQLiteException) { }

        assertThat(db.epgFeeds().all().map { it.id }).containsExactly(removed.id, retained.id)
        assertThat(db.programmes().countForFeed(removed.id)).isEqualTo(3)
        assertThat(db.programmes().countForFeed(retained.id)).isEqualTo(3)
        assertThat(db.epgAliases().all().map { it.feedId }).containsExactly(removed.id, retained.id)
        assertThat(repository.guideVersion.value).isEqualTo(0L)
    }
}
