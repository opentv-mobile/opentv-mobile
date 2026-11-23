/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.EpgChannelAlias
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgAliasesMigrationTest {
    @Test
    fun `version 15 schema migrates without losing rows and permits multiple alias keys`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "epg-migration-${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        try {
            createVersion15Database(dbFile)
            val migrated = Room.databaseBuilder(context, OpenTvDatabase::class.java, dbName)
                .addMigrations(OpenTvDatabase.MIGRATION_15_16)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = migrated.openHelper.writableDatabase // Runs migration and Room validation.
                assertThat(sqlite.version).isEqualTo(16)
                assertThat(migrated.sources().all().map { it.id }).containsExactly(7L)
                assertThat(migrated.channels().allForMatching().map { it.streamId }).containsExactly("stream-1")
                assertThat(migrated.epgFeeds().all().map { it.id }).containsExactly(9L)
                assertThat(migrated.programmes().countForFeed(9L)).isEqualTo(1)

                val aliases = migrated.epgAliases()
                assertThat(aliases.all().map { it.normalizedKey }).containsExactly("rallytv")
                aliases.upsertAll(listOf(
                    EpgChannelAlias(9L, "Rally.TV.de", "Rally TV", "rallytv"),
                    EpgChannelAlias(9L, "Rally.TV.de", "DAZN RALLY TV", "daznrallytv"),
                ))
                assertThat(aliases.all()).hasSize(2)
                aliases.upsertAll(listOf(EpgChannelAlias(9L, "Rally.TV.de", "Rally TV alt spelling", "rallytv")))
                assertThat(aliases.all()).hasSize(2)
                assertThat(aliases.all().single { it.normalizedKey == "rallytv" }.displayName)
                    .isEqualTo("Rally TV alt spelling")
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun createVersion15Database(file: File) {
        val schemaFile = sequenceOf(
            File("schemas/app.opentv.data.db.OpenTvDatabase/15.json"),
            File("app/schemas/app.opentv.data.db.OpenTvDatabase/15.json"),
        ).firstOrNull { it.isFile } ?: error("Exported Room v15 schema not found from ${System.getProperty("user.dir")}")
        val root = JSONObject(schemaFile.readText()).getJSONObject("database")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = root.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                val sql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
                sqlite.execSQL(sql)
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = root.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) {
                val query = setup.getString(i)
                if (query.startsWith("CREATE TRIGGER", ignoreCase = true)) sqlite.execSQL(query)
            }
            sqlite.execSQL("INSERT INTO sources (id,name,kind,url,userAgent,liveFormat,enabled,lastCatalogSyncMillis) VALUES (7,'test','XTREAM','https://example.invalid','', 'HLS',1,0)")
            sqlite.execSQL("INSERT INTO channels (id,sourceId,streamId,name,displayName,groupKey,qualityRank,qualityLabel,categoryId,logoUrl,epgChannelId,matchedEpgId,epgOverrideId,tvArchive,tvArchiveDays,number,streamUrl,cmd,favourite,hidden,sortIndex,customName,lastSeenMillis) VALUES (3,7,'stream-1','Rally','Rally','rally',0,'',NULL,NULL,NULL,NULL,NULL,0,0,NULL,'test://stream',NULL,1,0,0,NULL,0)")
            sqlite.execSQL("INSERT INTO epg_feeds (id,name,url,providerSourceId,builtIn,enabled,lastSyncMillis,lastResult) VALUES (9,'guide','https://example.invalid/guide.xml',NULL,0,1,0,'')")
            sqlite.execSQL("INSERT INTO epg_channels (feedId,epgId,displayName,normalizedKey) VALUES (9,'Rally.TV.de','Rally TV','rallytv')")
            sqlite.execSQL("INSERT INTO programmes (id,feedId,epgChannelId,startUtcMillis,endUtcMillis,title,description,category,season,episode,iconUrl) VALUES (11,9,'Rally.TV.de',1000,2000,'Test',NULL,NULL,NULL,NULL,NULL)")
            sqlite.version = 15
        } finally {
            sqlite.close()
        }
    }
}
