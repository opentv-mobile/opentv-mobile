package app.opentv.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.local.LocalPlaylistStore
import app.opentv.data.local.canRefreshCatalogue
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LocalPlaylistRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
    private val directory = File(context.cacheDir, "local-playlist-test-${java.util.UUID.randomUUID()}")
    private val store = LocalPlaylistStore(directory)
    private val requests = AtomicInteger()
    private val http = OkHttpClient.Builder().addInterceptor {
        requests.incrementAndGet()
        throw IOException("No network expected")
    }.build()
    private val api = XtreamApi(http)
    private val stalker = StalkerApi(http)
    private val repository = CatalogRepository(
        db.sources(), db.channels(), db.categories(), db.movies(), db.series(), db.episodes(), db.positions(),
        api, stalker, http, AppSettings.get(context), db, store,
    )
    private val draft = Source(name = "Local", kind = SourceKind.M3U, url = "")
    private fun playlist(name: String = "One", extra: String = "") =
        "#EXTM3U url-tvg=\"https://example.com/inferred.xml\"\n#EXTINF:-1 tvg-id=\"one\" group-title=\"News\",$name\nhttps://example.com/live\n$extra"
    private suspend fun source() = db.sources().enabled().single()

    @After fun close() { db.close(); directory.deleteRecursively() }

    @Test fun `changed tvg ids and reordered list preserve state by unique exact URL`() = runTest {
        val extra = "#EXTINF:-1 tvg-id=\"two\",Two\nhttps://example.com/two\n"
        repository.importLocalPlaylist(draft, "old.m3u", { playlist(extra = extra).byteInputStream() })
        val original = source()
        val old = db.channels().forSource(original.id).associateBy { it.streamUrl }
        val one = old.getValue("https://example.com/live")
        db.channels().setFavourite(one.id, true)
        db.channels().setHidden(one.id, true)
        db.channels().setSortIndex(one.id, 91)
        db.channels().setCustomName(one.id, "My name")
        db.channels().setEpgOverride(one.id, "manual")
        db.channels().setMatchedEpgId(one.id, "matched")
        val reordered = "#EXTM3U\n" + extra.replace("\"two\"", "\"new-two\"") +
            "#EXTINF:-1 tvg-id=\"new-one\",Renamed\nhttps://example.com/live\n"
        repository.importLocalPlaylist(original, "new.m3u", { reordered.byteInputStream() })
        val fresh = db.channels().forSource(original.id).associateBy { it.streamUrl }
        assertThat(fresh.getValue("https://example.com/two").id).isEqualTo(old.getValue("https://example.com/two").id)
        val merged = fresh.getValue(one.streamUrl)
        assertThat(merged.id).isEqualTo(one.id)
        assertThat(merged.streamId).isEqualTo("tvg:new-one")
        assertThat(merged.favourite).isTrue()
        assertThat(merged.hidden).isTrue()
        assertThat(merged.sortIndex).isEqualTo(91)
        assertThat(merged.customName).isEqualTo("My name")
        assertThat(merged.epgOverrideId).isEqualTo("manual")
        assertThat(merged.matchedEpgId).isEqualTo("matched")
    }

    @Test fun `URL fallback refuses ambiguous URLs and never steals a stable ID match`() = runTest {
        fun list(vararg entries: Pair<String, String>) = "#EXTM3U\n" + entries.joinToString("") { (id, url) ->
            "#EXTINF:-1 tvg-id=\"$id\",Same HD\nhttps://example.com/$url\n"
        }
        repository.importLocalPlaylist(draft, "old.m3u", { list("a" to "shared", "b" to "shared", "c" to "unique", "d" to "reserved").byteInputStream() })
        val original = source()
        val old = db.channels().forSource(original.id)
        old.forEach { db.channels().setFavourite(it.id, true) }
        repository.importLocalPlaylist(original, "new.m3u", {
            list("x" to "shared", "y" to "unique", "z" to "unique", "d" to "changed", "w" to "reserved").byteInputStream()
        })
        val fresh = db.channels().forSource(original.id)
        assertThat(fresh).hasSize(5)
        assertThat(fresh.filter { it.favourite }.map { it.streamId }).containsExactly("tvg:d")
        assertThat(fresh.single { it.streamId == "tvg:d" }.id).isEqualTo(old.single { it.streamId == "tvg:d" }.id)
        assertThat(fresh.map { it.id }.distinct()).hasSize(5)
    }

    @Test fun `reimport preserves IDs favourites overrides and cleanup follows commit`() = runTest {
        repository.importLocalPlaylist(draft, "first.m3u", { playlist().byteInputStream() })
        val original = source()
        assertThat(original.url).isEmpty()
        assertThat(original.epgUrl).isNull() // File headers do not silently opt in to network feeds.
        val channel = db.channels().forSource(original.id).single()
        db.channels().setFavourite(channel.id, true)
        db.channels().setCustomName(channel.id, "Mine")
        db.channels().setEpgOverride(channel.id, "manual")
        db.channels().setHidden(channel.id, true)
        db.channels().setSortIndex(channel.id, 27)
        repository.importLocalPlaylist(original, "second.m3u8", { playlist("Renamed").byteInputStream() })
        val updated = source()
        assertThat(updated.id).isEqualTo(original.id)
        assertThat(updated.localPlaylistFileName).isNotEqualTo(original.localPlaylistFileName)
        val merged = db.channels().forSource(original.id).single()
        assertThat(merged.id).isEqualTo(channel.id)
        assertThat(merged.name).isEqualTo("Renamed")
        assertThat(merged.favourite).isTrue()
        assertThat(merged.hidden).isTrue()
        assertThat(merged.customName).isEqualTo("Mine")
        assertThat(merged.epgOverrideId).isEqualTo("manual")
        assertThat(merged.sortIndex).isEqualTo(27)
        assertThat(directory.listFiles()!!.map { it.name }).containsExactly(updated.localPlaylistFileName)
        repository.deleteSource(updated.id)
        assertThat(db.sources().byId(updated.id)).isNull()
        assertThat(directory.listFiles()).isEmpty()
    }

    @Test fun `validation and database failure leave source catalogue and old file intact`() = runTest {
        repository.importLocalPlaylist(draft, "old.m3u", { playlist().byteInputStream() })
        val original = source()
        val channels = db.channels().forSource(original.id)
        val originalBytes = File(directory, original.localPlaylistFileName!!).readBytes()
        suspend fun assertUnchanged() {
            assertThat(source()).isEqualTo(original)
            assertThat(db.channels().forSource(original.id)).isEqualTo(channels)
            assertThat(directory.listFiles()!!.map { it.name }).containsExactly(original.localPlaylistFileName)
            assertThat(File(directory, checkNotNull(original.localPlaylistFileName)).readBytes()).isEqualTo(originalBytes)
        }
        try {
            repository.importLocalPlaylist(original, "bad.m3u", { "#EXTM3U".byteInputStream() })
            error("Expected validation failure")
        } catch (_: app.opentv.data.local.LocalPlaylistException) { }
        assertUnchanged()
        // Fail after the source row has changed, proving the outer transaction rolls it back too.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON channels BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            repository.importLocalPlaylist(original.copy(name = "Changed"), "new.m3u", { playlist("Changed").byteInputStream() })
            error("Expected database failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertUnchanged()
    }

    @Test fun `cancelled import does not publish or delete the old file`() = runTest {
        repository.importLocalPlaylist(draft, "old.m3u", { playlist().byteInputStream() })
        val original = source()
        val job = launch {
            val caller = currentCoroutineContext().job
            repository.importLocalPlaylist(original, "new.m3u", {
                caller.cancel(CancellationException("User cancelled"))
                playlist("Changed").byteInputStream()
            })
        }
        job.join()
        assertThat(source()).isEqualTo(original)
        assertThat(directory.listFiles()!!.map { it.name }).containsExactly(original.localPlaylistFileName)
    }

    @Test fun `generic refresh is inert and metadata edit does not reimport`() = runTest {
        repository.importLocalPlaylist(draft, "old.m3u", { playlist().byteInputStream() })
        val original = source()
        val channels = db.channels().forSource(original.id)
        assertThat(original.canRefreshCatalogue()).isFalse()
        assertThat(draft.copy(url = "https://example.com/list").canRefreshCatalogue()).isTrue()
        repository.sync(original, Long.MAX_VALUE)
        repository.syncLive(original, Long.MAX_VALUE)
        assertThat(requests.get()).isEqualTo(0)
        assertThat(source()).isEqualTo(original)
        SourceRepository(db.sources(), api, stalker, db).save(original.copy(name = "Edited", epgUrl = "https://example.com/guide.xml"))
        assertThat(source()).isEqualTo(original.copy(name = "Edited", epgUrl = "https://example.com/guide.xml"))
        assertThat(db.channels().forSource(original.id)).isEqualTo(channels)
        assertThat(directory.listFiles()!!.map { it.name }).containsExactly(original.localPlaylistFileName)
        val epg = EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), api, http)
        epg.ensureFeeds()
        assertThat(db.epgFeeds().forProvider(original.id)).isNotNull()
        epg.syncAll(System.currentTimeMillis(), force = true)
        assertThat(requests.get()).isGreaterThan(0) // Explicit remote guide still refreshes.
        requests.set(0)
        // Removing the explicit URL must prevent even a previously-created feed inferring a host.
        SourceRepository(db.sources(), api, stalker, db).save(source().copy(epgUrl = null))
        epg.syncAll(System.currentTimeMillis(), force = true)
        assertThat(requests.get()).isEqualTo(0)
    }

    @Test fun `new local source creates no inferred provider feed and reimport removes stale channels`() = runTest {
        val extra = "#EXTINF:-1 tvg-id=\"two\",Two\nhttps://example.com/two\n"
        repository.importLocalPlaylist(draft, "old.m3u", { playlist(extra = extra).byteInputStream() })
        val original = source()
        assertThat(db.channels().forSource(original.id)).hasSize(2)
        val epg = EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), api, http)
        epg.ensureFeeds()
        assertThat(db.epgFeeds().forProvider(original.id)).isNull()
        repository.importLocalPlaylist(original, "new.m3u", { playlist().byteInputStream() })
        assertThat(db.channels().forSource(original.id)).hasSize(1)
    }

    @Test fun `failed initial import does not leave an empty source behind`() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON channels BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            repository.importLocalPlaylist(draft, "new.m3u", { playlist().byteInputStream() })
            error("Expected database failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertThat(db.sources().enabled()).isEmpty()
        assertThat(db.channels().allForMatching()).isEmpty()
        assertThat(directory.listFiles()).isEmpty()
    }

    @Test fun `malformed explicit local guide fails only its feed and another feed still imports`() = runTest {
        repository.importLocalPlaylist(draft.copy(epgUrl = "not a URL"), "list.m3u", { playlist().byteInputStream() })
        val now = 1_700_000_000_000L
        val xml = """<tv><channel id="news"><display-name>News</display-name></channel>
            <programme start="20231114220000 +0000" stop="20231114230000 +0000" channel="news"><title>Working feed</title></programme></tv>"""
        val workingHttp = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(xml.toResponseBody()).build()
        }.build()
        val epg = EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), XtreamApi(workingHttp), workingHttp)
        epg.ensureFeeds()
        val validId = db.epgFeeds().insert(app.opentv.data.model.EpgFeed(name = "Working", url = "https://example.com/valid.xml", enabled = true))
        val result = epg.syncAll(now, force = true)
        assertThat(result.feedsFailed).isEqualTo(1)
        assertThat(db.programmes().countForFeed(validId)).isEqualTo(1)
    }
}
