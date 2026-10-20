package app.opentv.data.db

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.MovieBrowseKey
import app.opentv.data.repo.movieFixture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DatabaseFoundationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: OpenTvDatabase? = null

    @After fun close() {
        database?.close()
        context.deleteDatabase("opentv.db")
    }

    @Test fun `v13 upgrades validate full Room schema preserve data and backfill multiple batches`() =
        verifyUpgrade(fromVersion = 13)

    @Test fun `v14 upgrades preserve local playlist metadata and backfill multiple batches`() =
        verifyUpgrade(fromVersion = 14)

    private fun verifyUpgrade(fromVersion: Int) = runTest {
        val schema = JSONObject(checkNotNull(javaClass.classLoader!!.getResourceAsStream(
            "app.opentv.data.db.OpenTvDatabase/13.json",
        )).bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("opentv.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.getJSONArray("indices")
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                            val triggers = entity.optJSONArray("contentSyncTriggers")
                            if (triggers != null) for (j in 0 until triggers.length()) {
                                db.execSQL(triggers.getString(j))
                            }
                        }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val old = helper.writableDatabase
        // Populate every required v13 column using the exported schema, then override real data.
        fun insert(table: String, overrides: ContentValues) {
            val entities = schema.getJSONArray("entities")
            val entity = (0 until entities.length()).map { entities.getJSONObject(it) }
                .first { it.getString("tableName") == table }
            val values = ContentValues()
            val fields = entity.getJSONArray("fields")
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                if (field.getBoolean("notNull")) {
                    if (field.getString("affinity") == "TEXT") values.put(field.getString("columnName"), "")
                    else values.put(field.getString("columnName"), 0)
                }
            }
            values.putAll(overrides)
            old.insert(table, 0, values)
        }
        insert("sources", ContentValues().apply {
            put("id", 1); put("name", "Provider"); put("kind", "M3U"); put("url", "https://example.com/list")
        })
        old.beginTransaction()
        try {
            for (id in 1L..1003L) insert("movies", ContentValues().apply {
                put("id", id); put("sourceId", 1); put("streamId", "$id")
                put("name", "Film $id 1999 HD"); put("favourite", 1)
                if (id % 2L == 0L) put("year", 2020)
            })
            old.setTransactionSuccessful()
        } finally { old.endTransaction() }
        if (fromVersion == 14) {
            // Build the intermediate version using the actual additive migration, not a guessed
            // fixture. Opening it below exercises the separately registered 14 -> 15 path.
            OpenTvDatabase.MIGRATION_13_14.migrate(old)
            old.execSQL("UPDATE sources SET localPlaylistFileName = 'playlist-test.m3u', localPlaylistDisplayName = 'My list.m3u'")
            old.version = 14
        }
        helper.close()

        database = OpenTvDatabase.build(context)
        val dao = database!!.movies() // First read runs migrations AND Room's schema validation.
        assertThat(dao.count()).isEqualTo(1003)
        for (id in listOf(1L, 500L, 501L, 1000L, 1003L)) {
            val movie = checkNotNull(dao.byId(id))
            val key = MovieBrowseKey.from(movie.name, movie.year)
            assertThat(movie.browseGroupKey).isEqualTo(key.groupKey)
            assertThat(movie.browseQualityRank).isEqualTo(key.qualityRank)
            assertThat(movie.favourite).isTrue()
        }
        val source = checkNotNull(database!!.sources().byId(1))
        assertThat(source.name).isEqualTo("Provider")
        assertThat(source.url).isEqualTo("https://example.com/list")
        if (fromVersion == 14) {
            assertThat(source.localPlaylistFileName).isEqualTo("playlist-test.m3u")
            assertThat(source.localPlaylistDisplayName).isEqualTo("My list.m3u")
        } else {
            assertThat(source.localPlaylistFileName).isNull()
            assertThat(source.localPlaylistDisplayName).isNull()
        }
        assertThat(database!!.openHelper.writableDatabase.version).isEqualTo(15)
        // Backfill updates must leave the external-content FTS index searchable.
        database!!.openHelper.writableDatabase.query("SELECT count(*) FROM movies_fts WHERE movies_fts MATCH 'film*'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(1003)
        }
    }

    @Test fun `DAO recomputes derived fields after copied metadata changes and persists local source contract`() = runTest {
        database = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
        val dao = database!!.movies()
        dao.upsertAll(listOf(movieFixture(1, "Film 1999 HD").copy(browseGroupKey = "stale", browseQualityRank = -1)))
        val original = checkNotNull(dao.byId(1))
        assertThat(original.browseGroupKey).isEqualTo(MovieBrowseKey.from(original.name, null).groupKey)
        dao.upsertAll(listOf(original.copy(name = "Film 4K", year = 2020)))
        val enriched = checkNotNull(dao.byId(1))
        val key = MovieBrowseKey.from(enriched.name, enriched.year)
        assertThat(enriched.browseGroupKey).isEqualTo(key.groupKey)
        assertThat(enriched.browseQualityRank).isEqualTo(key.qualityRank)
        assertThat(enriched.browseGroupKey).isNotEqualTo(original.browseGroupKey)
        val source = Source(name = "Local", kind = SourceKind.M3U, url = "",
            localPlaylistFileName = "playlist-generated.m3u", localPlaylistDisplayName = "My list.m3u")
        val id = database!!.sources().insert(source)
        assertThat(database!!.sources().byId(id)).isEqualTo(source.copy(id = id))
    }
}
