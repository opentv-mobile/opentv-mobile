/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import app.opentv.data.model.Download
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.opentv.data.model.Category
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<Source>>

    @Query("SELECT * FROM sources ORDER BY id")
    suspend fun all(): List<Source>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): Source?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    @Update
    suspend fun update(source: Source)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastCatalogSyncMillis = :millis WHERE id = :id")
    suspend fun markCatalogSynced(id: Long, millis: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    fun observe(kind: StreamKind): Flow<List<Category>>

    /** One-shot list of every category of a kind, ordered like [observe] — the web manager reads
     * the live categories once per request rather than subscribing to a Flow. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    suspend fun allByKind(kind: StreamKind): List<Category>

    /** Minimal id+name rows for the re-normalise pass. */
    @Query("SELECT id, name FROM categories WHERE id IN (:ids)")
    suspend fun namesFor(ids: Set<String>): List<CategoryName>

    @Upsert
    suspend fun upsertAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** Projection for [CategoryDao.namesFor]. */
data class CategoryName(val id: String, val name: String)

@Dao
interface EpgFeedDao {
    @Query("SELECT * FROM epg_feeds ORDER BY builtIn DESC, id")
    fun observeAll(): Flow<List<EpgFeed>>

    @Query("SELECT * FROM epg_feeds")
    suspend fun all(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE enabled = 1")
    suspend fun enabled(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE providerSourceId = :sourceId")
    suspend fun forProvider(sourceId: Long): EpgFeed?

    @Query("SELECT * FROM epg_feeds WHERE url = :url")
    suspend fun byUrl(url: String): EpgFeed?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: EpgFeed): Long

    @Update
    suspend fun update(feed: EpgFeed)

    @Query("UPDATE epg_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE epg_feeds SET lastSyncMillis = :millis, lastResult = :result WHERE id = :id")
    suspend fun markSynced(id: Long, millis: Long, result: String)

    @Query("DELETE FROM epg_feeds WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EpgChannelAliasDao {
    @Query("SELECT * FROM epg_channels")
    suspend fun all(): List<EpgChannelAlias>

    @Query("SELECT COUNT(*) FROM epg_channels")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(aliases: List<EpgChannelAlias>)

    @Query("DELETE FROM epg_channels WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeForSeries(sourceId: Long, seriesId: String): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: Long): Episode?

    @Query("SELECT * FROM episodes WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Episode?

    @Upsert
    suspend fun upsertAll(episodes: List<Episode>)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun get(profileId: Long, key: String): PlaybackPosition?

    @Query(
        "SELECT * FROM playback_positions WHERE profileId = :profileId " +
            "ORDER BY updatedAtMillis DESC LIMIT :limit",
    )
    fun observeRecent(profileId: Long, limit: Int = 30): Flow<List<PlaybackPosition>>

    @Query("SELECT * FROM playback_positions")
    suspend fun all(): List<PlaybackPosition>

    /** One profile's whole watch history — the input to genre-affinity recommendations. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId")
    suspend fun forProfile(profileId: Long): List<PlaybackPosition>

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun delete(profileId: Long, key: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun all(): List<Profile>

    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Profile?

    @Insert
    suspend fun insert(profile: Profile): Long

    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Removing a profile takes its watch history with it. */
    @Query("DELETE FROM playback_positions WHERE profileId = :id")
    suspend fun deletePositions(id: Long)
}

@Dao
interface RecordingDao {
    /** The library, newest first — scheduled and in-progress float to the top by recency. */
    @Query("SELECT * FROM recordings ORDER BY COALESCE(NULLIF(startedAtMillis, 0), scheduledStartMillis) DESC, id DESC")
    fun observeAll(): Flow<List<Recording>>

    /** Just the ones capturing right now — the player watches this to light its Record button. */
    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    fun observeActive(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    suspend fun active(): List<Recording>

    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY scheduledStartMillis")
    suspend fun scheduled(): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    /** Snapshot of every recording — the NAS sync reads this to gather the smb:// ones to share. */
    @Query("SELECT * FROM recordings")
    suspend fun all(): List<Recording>

    /** De-dup for NAS sync: a recording's smb:// locator is its stable cross-device identity. */
    @Query("SELECT * FROM recordings WHERE filePath = :filePath LIMIT 1")
    suspend fun byFilePath(filePath: String): Recording?

    /** For series-link de-dup: has this rule already booked this programme window on this channel? */
    @Query(
        """
        SELECT COUNT(*) FROM recordings
        WHERE seriesRuleId = :ruleId AND channelId = :channelId AND scheduledStartMillis = :startMillis
        """
    )
    suspend fun countForRuleAt(ruleId: Long, channelId: Long, startMillis: Long): Int

    /** Any live/scheduled booking for this exact airing (same channel + start) — for manual de-dup. */
    @Query(
        """
        SELECT * FROM recordings
        WHERE channelId = :channelId AND scheduledStartMillis = :startMillis
          AND status IN ('SCHEDULED', 'RECORDING') LIMIT 1
        """
    )
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null)

    @Query("UPDATE recordings SET sizeBytes = :bytes WHERE id = :id")
    suspend fun setSize(id: Long, bytes: Long)

    /** Repoint a row at its real locator — used when a USB (SAF) capture learns its content:// URI. */
    @Query("UPDATE recordings SET filePath = :filePath WHERE id = :id")
    suspend fun setFilePath(id: Long, filePath: String)

    @Query(
        "UPDATE recordings SET status = :status, startedAtMillis = :startedAt WHERE id = :id"
    )
    suspend fun markStarted(id: Long, status: RecordingStatus, startedAt: Long)

    @Query(
        "UPDATE recordings SET status = :status, endedAtMillis = :endedAt, sizeBytes = :bytes, error = :error WHERE id = :id"
    )
    suspend fun markFinished(id: Long, status: RecordingStatus, endedAt: Long, bytes: Long, error: String?)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * On a cold start, any row still marked RECORDING is a leftover from a process that was
     * killed mid-capture — reconcile it to FAILED so the UI never shows a phantom "recording".
     */
    @Query("UPDATE recordings SET status = 'FAILED', error = 'Interrupted' WHERE status = 'RECORDING'")
    suspend fun failInterrupted()
}

@Dao
interface SeriesRuleDao {
    @Query("SELECT * FROM series_rules ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<SeriesRule>>

    @Query("SELECT * FROM series_rules WHERE enabled = 1")
    suspend fun enabled(): List<SeriesRule>

    @Query("SELECT * FROM series_rules WHERE channelId = :channelId AND titleKey = :titleKey LIMIT 1")
    suspend fun forChannelTitle(channelId: Long, titleKey: String): SeriesRule?

    @Insert
    suspend fun insert(rule: SeriesRule): Long

    @Query("DELETE FROM series_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    /** All reminders, soonest first — the reminders list and boot re-arm both read this. */
    @Query("SELECT * FROM reminders ORDER BY startUtcMillis")
    fun observeAll(): Flow<List<Reminder>>

    /** Still-future, not-yet-fired reminders — the set to re-arm after a reboot. */
    @Query("SELECT * FROM reminders WHERE fired = 0 AND startUtcMillis > :nowMillis ORDER BY startUtcMillis")
    suspend fun upcoming(nowMillis: Long): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    /** De-dup: is there already a reminder for this (channel, slot)? */
    @Query("SELECT * FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis LIMIT 1")
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder?

    /** The guide grid asks: which upcoming slots already have a bell? Keyed by start time. */
    @Query("SELECT startUtcMillis FROM reminders WHERE channelId = :channelId AND fired = 0")
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis")
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long)

    /** Housekeeping: drop reminders whose programme has already ended. */
    @Query("DELETE FROM reminders WHERE endUtcMillis < :beforeMillis")
    suspend fun deleteEndedBefore(beforeMillis: Long)
}

/** Titles the derived VOD feeds (genre rows, recommendations, more-like-this) work from. */
const val FEED_SAMPLE = 5000

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdMillis DESC")
    fun observeAll(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE mediaKey = :mediaKey")
    suspend fun byKey(mediaKey: String): Download?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: Download): Long

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    /** The oldest download still waiting in the queue (systemId = PENDING). */
    @Query("SELECT * FROM downloads WHERE systemId = -1 ORDER BY createdMillis LIMIT 1")
    suspend fun nextPending(): Download?

    @Query("UPDATE downloads SET systemId = :systemId WHERE id = :id")
    suspend fun setSystemId(id: Long, systemId: Long)
}

data class CategoryCount(val categoryId: String, val count: Int)
