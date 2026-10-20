/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.opentv.data.model.Programme
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgrammeDao {
    @Query("""
        SELECT * FROM programmes
        WHERE epgChannelId IN (:channelIds)
          AND endUtcMillis > :fromUtcMillis AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
    """)
    suspend fun phoneWindowBatch(channelIds: List<String>, fromUtcMillis: Long, toUtcMillis: Long): List<Programme>

    /** One-shot reads: refresh on guideVersion, not on every import batch. */
    suspend fun phoneWindow(channelIds: List<String>, fromUtcMillis: Long, toUtcMillis: Long): List<Programme> {
        if (fromUtcMillis >= toUtcMillis) return emptyList()
        return channelIds.filter { it.isNotBlank() }.distinct().chunked(900).flatMap {
            phoneWindowBatch(it, fromUtcMillis, toUtcMillis)
        }
    }

    @Query("SELECT MIN(startUtcMillis) AS `start`, MAX(endUtcMillis) AS `end` FROM programmes")
    suspend fun phoneHorizon(): PhoneGuideHorizon

    @Query("""
        SELECT * FROM programmes p
        WHERE p.epgChannelId IN (:channelIds) AND p.endUtcMillis > :now
          AND (p.startUtcMillis <= :now OR p.startUtcMillis = (
              SELECT MIN(n.startUtcMillis) FROM programmes n
              WHERE n.epgChannelId = p.epgChannelId AND n.startUtcMillis > :now
          ))
        ORDER BY p.epgChannelId, p.startUtcMillis
    """)
    suspend fun phoneNowNextBatch(channelIds: List<String>, now: Long): List<Programme>

    /** Live shows can start before now; the next show can be arbitrarily far in the future. */
    suspend fun phoneNowNext(channelIds: List<String>, now: Long): List<Programme> =
        channelIds.filter { it.isNotBlank() }.distinct().chunked(900).flatMap { phoneNowNextBatch(it, now) }

    /** Time-only window; a channel-id IN list would hit old SQLite's 999 bind limit. */
    @Query(
        """
        SELECT * FROM programmes
        WHERE endUtcMillis > :fromUtcMillis
          AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
        """
    )
    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>>

    /** One-shot window for readers refreshing on EpgRepository.guideVersion. */
    @Query(
        """
        SELECT * FROM programmes
        WHERE endUtcMillis > :fromUtcMillis
          AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
        """
    )
    suspend fun window(fromUtcMillis: Long, toUtcMillis: Long): List<Programme>

    @Query(
        """
        SELECT * FROM programmes
        WHERE startUtcMillis <= :nowUtcMillis AND endUtcMillis > :nowUtcMillis
        """
    )
    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>>

    @Query(
        """
        SELECT * FROM programmes
        WHERE epgChannelId = :channelId AND endUtcMillis > :nowUtcMillis
        ORDER BY startUtcMillis LIMIT :limit
        """
    )
    suspend fun upcoming(channelId: String, nowUtcMillis: Long, limit: Int): List<Programme>

    @Query("SELECT COUNT(*) FROM programmes WHERE feedId = :feedId")
    suspend fun countForFeed(feedId: Long): Int

    /** Distinct guide channels that actually have programmes — the match report's baseline. */
    @Query("SELECT DISTINCT epgChannelId FROM programmes")
    suspend fun channelIdsWithProgrammes(): List<String>

    @Upsert
    suspend fun upsertAll(programmes: List<Programme>)

    @Query("DELETE FROM programmes WHERE endUtcMillis < :beforeUtcMillis")
    suspend fun deleteEndedBefore(beforeUtcMillis: Long)

    @Query("DELETE FROM programmes WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

data class PhoneGuideHorizon(val start: Long?, val end: Long?)
