/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.paging.PagingSource
import app.opentv.data.repo.VodCategoryCount
import app.opentv.data.model.Series
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("""
        SELECT * FROM series
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name COLLATE BINARY ASC, id ASC
    """)
    fun browse(sourceId: Long?, categoryId: String?): PagingSource<Int, Series>

    @Query("SELECT * FROM series WHERE (:sourceId IS NULL OR sourceId = :sourceId) ORDER BY addedMillis DESC, id ASC LIMIT :limit")
    fun observeBrowseRecent(sourceId: Long?, limit: Int): Flow<List<Series>>

    @Query("SELECT * FROM series WHERE (:sourceId IS NULL OR sourceId = :sourceId) ORDER BY addedMillis DESC, id ASC LIMIT :limit")
    suspend fun browseFeedSample(sourceId: Long?, limit: Int): List<Series>

    @Query("""
        SELECT sourceId, categoryId, COUNT(*) AS count FROM series
        WHERE categoryId IS NOT NULL AND (:sourceId IS NULL OR sourceId = :sourceId)
        GROUP BY sourceId, categoryId
    """)
    suspend fun browseCategoryCounts(sourceId: Long?): List<VodCategoryCount>

    @Query(
        """
        SELECT * FROM series
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Series>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Series>>

    /** Word-prefix title search through [app.opentv.data.model.SeriesFts]. */
    @Query(
        """
        SELECT series.* FROM series JOIN series_fts ON series.id = series_fts.docid
        WHERE series_fts MATCH :match ORDER BY series.name LIMIT :limit
        """
    )
    fun searchFts(match: String, limit: Int = 200): Flow<List<Series>>

    /** The show an episode belongs to, by the provider's own series id. */
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId = :seriesId LIMIT 1")
    suspend fun byProviderId(sourceId: Long, seriesId: String): Series?

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Series>>

    /** Newest series (capped, see MovieDao.all) — working set for the by-genre / more-like-this feeds. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC LIMIT $FEED_SAMPLE")
    suspend fun all(): List<Series>

    /** How many series are on disk — the cheap "did the library grow" check for the home feeds. */
    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int

    /** Titles per category id — read once when the category sheet opens (not observed: a live
     *  GROUP BY over the whole library would re-run on every batch a sync writes). */
    @Query("SELECT categoryId AS categoryId, COUNT(*) AS count FROM series WHERE categoryId IS NOT NULL GROUP BY categoryId")
    suspend fun countsByCategory(): List<CategoryCount>

    @Query("SELECT EXISTS(SELECT 1 FROM series)")
    fun observeAny(): Flow<Boolean>

    /** Fallback for More-Like-This when a series has no genre: same source/category, best-rated first. */
    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Series>

    /** Series a person is billed in — the show half of "click an actor, see everything". See
     *  [MovieDao.moviesWithActor]. */
    @Query(
        """
        SELECT * FROM series
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun seriesWithActor(name: String, limit: Int): List<Series>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): Series?

    @Query("UPDATE series SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    // --- Sync: favourites by provider series id (series carry no stream URL) ---
    @Query("SELECT seriesId FROM series WHERE favourite = 1")
    suspend fun favouriteSeriesIds(): List<String>

    @Query("UPDATE series SET favourite = 1 WHERE seriesId = :seriesId")
    suspend fun markFavouriteBySeriesId(seriesId: String)

    @Upsert
    suspend fun upsertAll(items: List<Series>)

    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}
