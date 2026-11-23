/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgMultiAliasIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
    private val responseXml = AtomicReference("<tv/>")
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(responseXml.get().toResponseBody("application/xml".toMediaType())).build()
    }.build()
    private val repo = EpgRepository(
        db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), XtreamApi(http), http, db,
    )

    @After fun close() = db.close()

    @Test fun `secondary Rally alias imports matches and exposes current programmes with null provider id`() = runTest {
        val sourceId = db.sources().insert(Source(name = "synthetic", kind = SourceKind.M3U, url = "", localPlaylistFileName = "synthetic.m3u"))
        val channel = channel(sourceId, "DAZN RALLY TV", null)
        db.channels().upsertAll(listOf(channel))
        val now = System.currentTimeMillis()
        responseXml.set(xmltv(
            """<channel id="Rally.TV.de"><display-name>Rally TV</display-name><display-name>DAZN RALLY TV</display-name></channel>""",
            listOf("Rally.TV.de" to "Rally"), now,
        ))
        repo.addCustomFeed("synthetic", "https://example.invalid/guide.xml")

        repo.syncAll(now, force = true)

        val aliases = db.epgAliases().all()
        assertThat(aliases.map { it.displayName }.toSet()).containsExactly("Rally TV", "DAZN RALLY TV")
        assertThat(aliases.associate { it.displayName to it.normalizedKey })
            .containsExactly("Rally TV", "rallytv", "DAZN RALLY TV", "daznrallytv")
        val stored = db.channels().byId(db.channels().allForMatching().single().id)!!
        assertThat(stored.matchedEpgId).isEqualTo("Rally.TV.de")
        assertThat(stored.epgCandidates).contains("Rally.TV.de")
        assertThat(repo.upcoming("Rally.TV.de", now).map { it.title }).containsExactly("Rally 1", "Rally 2", "Rally 3")
    }

    @Test fun `equivalent LaLiga ids resolve canonically and manual override remains first`() = runTest {
        val sourceId = db.sources().insert(Source(name = "synthetic", kind = SourceKind.M3U, url = "", localPlaylistFileName = "synthetic.m3u"))
        val channel = channel(sourceId, "M+ LaLigaTVSD", "NULL")
        db.channels().upsertAll(listOf(channel))
        val now = System.currentTimeMillis()
        val aliasXml = """<channel id="mliga"><display-name>M+ LALIGA TV</display-name><display-name>M+ LaLigaTV</display-name><display-name>M+ LaLigaTVSD</display-name></channel>
            <channel id="mlaliga.es"><display-name>M+ LALIGA TV</display-name><display-name>M+ LaLigaTV</display-name><display-name>M+ LaLigaTVSD</display-name></channel>"""
        responseXml.set(xmltv(aliasXml, listOf("mliga" to "LaLiga", "mlaliga.es" to "LaLiga"), now))
        repo.addCustomFeed("synthetic", "https://example.invalid/guide.xml")

        repo.syncAll(now, force = true)

        val aliases = db.epgAliases().all()
        assertThat(aliases.filter { it.epgId == "mliga" }.map { it.normalizedKey }.toSet())
            .containsExactly("mlaligatv", "mlaligatvsd")
        assertThat(aliases.filter { it.epgId == "mlaliga.es" }.map { it.normalizedKey }.toSet())
            .containsExactly("mlaligatv", "mlaligatvsd")
        assertThat(aliases.filter { it.displayName == "M+ LaLigaTVSD" }.map { it.normalizedKey }.toSet())
            .containsExactly("mlaligatvsd")
        val stored = db.channels().allForMatching().single()
        assertThat(stored.epgChannelId).isEqualTo("NULL")
        assertThat(stored.matchedEpgId).isEqualTo("mlaliga.es")
        assertThat(stored.epgCandidates.first()).isEqualTo("mlaliga.es")
        assertThat(repo.upcoming("mlaliga.es", now).map { it.title }).containsExactly("LaLiga 1", "LaLiga 2", "LaLiga 3")

        db.channels().setEpgOverride(stored.id, "manual-guide-id")
        assertThat(db.channels().byId(stored.id)!!.epgCandidates.first()).isEqualTo("manual-guide-id")
    }

    @Test fun `forced resync replaces aliases and a failed parse preserves prior aliases and channel state`() = runTest {
        val sourceId = db.sources().insert(Source(name = "synthetic", kind = SourceKind.M3U, url = "", localPlaylistFileName = "synthetic.m3u"))
        val ch = channel(sourceId, "DAZN RALLY TV", null).copy(favourite = true, epgOverrideId = "manual")
        db.channels().upsertAll(listOf(ch))
        val now = System.currentTimeMillis()
        repo.addCustomFeed("synthetic", "https://example.invalid/guide.xml")
        responseXml.set(xmltv("""<channel id="Rally.TV.de"><display-name>Rally TV</display-name></channel>""", listOf("Rally.TV.de" to "Rally"), now))
        repo.syncAll(now, force = true)
        assertThat(db.channels().allForMatching().single().matchedEpgId).isNull()

        // Same feed URL, forced refresh, changed XML: secondary name arrives and old alias disappears.
        responseXml.set(xmltv("""<channel id="Rally.TV.de"><display-name>DAZN RALLY TV</display-name><display-name>Rally Championship</display-name></channel>""", listOf("Rally.TV.de" to "Rally"), now))
        repo.syncAll(now + 1, force = true)
        val afterSuccess = db.channels().allForMatching().single()
        assertThat(db.epgAliases().all().map { it.displayName }.toSet()).containsExactly("DAZN RALLY TV", "Rally Championship")
        assertThat(afterSuccess.matchedEpgId).isEqualTo("Rally.TV.de")
        assertThat(afterSuccess.favourite).isTrue()
        assertThat(afterSuccess.epgOverrideId).isEqualTo("manual")

        responseXml.set("<tv><channel id=\"Rally.TV.de\"><display-name>Broken replacement</display-name></channel><bad")
        repo.syncAll(now + 2, force = true)
        val afterFailure = db.channels().allForMatching().single()
        assertThat(db.epgAliases().all().map { it.displayName }.toSet()).containsExactly("DAZN RALLY TV", "Rally Championship")
        assertThat(afterFailure.favourite).isTrue()
        assertThat(afterFailure.epgOverrideId).isEqualTo("manual")
        assertThat(afterFailure.matchedEpgId).isEqualTo("Rally.TV.de")
    }

    @Test fun `empty guide reports failure and retains previously imported aliases and programmes`() = runTest {
        val sourceId = db.sources().insert(Source(name = "synthetic", kind = SourceKind.M3U, url = "", localPlaylistFileName = "synthetic.m3u"))
        db.channels().upsertAll(listOf(channel(sourceId, "DAZN RALLY TV", null).copy(favourite = true, epgOverrideId = "manual")))
        val now = System.currentTimeMillis()
        repo.addCustomFeed("synthetic", "https://example.invalid/guide.xml")
        responseXml.set(xmltv("""<channel id="Rally.TV.de"><display-name>Old alias</display-name></channel>""", listOf("Rally.TV.de" to "Rally"), now))
        assertThat(repo.syncAll(now, force = true).feedsFailed).isEqualTo(0)
        val oldProgrammes = repo.upcoming("Rally.TV.de", now)
        val oldAliases = db.epgAliases().all()

        responseXml.set("<tv/>")
        val failed = repo.syncAll(now + 1, force = true)

        assertThat(failed.feedsFailed).isEqualTo(1)
        assertThat(db.epgAliases().all()).containsExactlyElementsIn(oldAliases)
        assertThat(repo.upcoming("Rally.TV.de", now).map { it.title })
            .containsExactlyElementsIn(oldProgrammes.map { it.title })
        val stored = db.channels().allForMatching().single()
        assertThat(stored.favourite).isTrue()
        assertThat(stored.epgOverrideId).isEqualTo("manual")
    }

    @Test fun `alias persistence failure rolls back replacement and preserves existing programme`() = runTest {
        val sourceId = db.sources().insert(Source(name = "synthetic", kind = SourceKind.M3U, url = "", localPlaylistFileName = "synthetic.m3u"))
        val ch = channel(sourceId, "DAZN RALLY TV", null).copy(favourite = true, epgOverrideId = "manual")
        db.channels().upsertAll(listOf(ch))
        val now = System.currentTimeMillis()
        repo.addCustomFeed("synthetic", "https://example.invalid/guide.xml")
        responseXml.set(xmltv("""<channel id="Rally.TV.de"><display-name>Old alias</display-name></channel>""", listOf("Rally.TV.de" to "Rally"), now))
        repo.syncAll(now, force = true)
        val oldProgrammes = repo.upcoming("Rally.TV.de", now)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_new_epg_alias BEFORE INSERT ON epg_channels " +
                "WHEN NEW.displayName = 'New alias' BEGIN SELECT RAISE(ABORT, 'intentional alias insert failure'); END",
        )
        responseXml.set(xmltv("""<channel id="Rally.TV.de"><display-name>New alias</display-name></channel>""", listOf("Rally.TV.de" to "Rally"), now))

        val failed = repo.syncAll(now + 1, force = true)

        assertThat(failed.feedsFailed).isEqualTo(1)
        assertThat(db.epgAliases().all().map { it.displayName }).containsExactly("Old alias")
        assertThat(repo.upcoming("Rally.TV.de", now)).containsExactlyElementsIn(oldProgrammes)
        val stored = db.channels().allForMatching().single()
        assertThat(stored.favourite).isTrue()
        assertThat(stored.epgOverrideId).isEqualTo("manual")
    }

    private fun channel(sourceId: Long, name: String, providerEpgId: String?) = Channel(
        sourceId = sourceId, streamId = name, name = name, displayName = name,
        groupKey = ChannelNameNormalizer.normalize(name).groupKey,
        categoryId = null, logoUrl = null, epgChannelId = providerEpgId,
        number = null, streamUrl = "https://stream.invalid/$name",
    )

    private fun xmltv(channels: String, schedules: List<Pair<String, String>>, now: Long): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val programmes = schedules.flatMap { (id, title) -> (0..2).map { i ->
            val start = now + i * 3_600_000L - 60_000L
            val stop = start + 1_800_000L
            "<programme channel=\"$id\" start=\"${format.format(Date(start))}\" stop=\"${format.format(Date(stop))}\"><title>$title ${i + 1}</title></programme>"
        } }.joinToString("\n")
        return "<tv>$channels$programmes</tv>"
    }
}
