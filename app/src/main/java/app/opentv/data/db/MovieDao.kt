/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.paging.PagingSource
import app.opentv.data.repo.MovieBrowseGroup
import app.opentv.data.repo.VodCategoryCount
import app.opentv.data.model.Movie
import app.opentv.data.parser.MovieBrowseKey
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MovieDao {
    /** Page the GROUP BY result, not variants. SQLite 3.8/API 23 compatible (no windows).
     * Both scans use the same scope: a better copy outside it must not become the representative.
     * id breaks exact quality/name ties; group key breaks newest-added ties across pages.
     */
    @Query("""
        SELECT m.*, g.variantCount, g.newestAddedMillis
        FROM (
            SELECT browseGroupKey, COUNT(*) AS variantCount, MAX(addedMillis) AS newestAddedMillis
            FROM movies
            WHERE (:sourceId IS NULL OR sourceId = :sourceId)
              AND (:categoryId IS NULL OR categoryId = :categoryId)
            GROUP BY browseGroupKey
        ) AS g
        JOIN movies AS m ON m.id = (
            SELECT v.id FROM movies AS v
            WHERE v.browseGroupKey = g.browseGroupKey
              AND (:sourceId IS NULL OR v.sourceId = :sourceId)
              AND (:categoryId IS NULL OR v.categoryId = :categoryId)
            ORDER BY v.browseQualityRank DESC, v.name COLLATE BINARY ASC, v.id ASC
            LIMIT 1
        )
        ORDER BY g.newestAddedMillis DESC, g.browseGroupKey COLLATE BINARY ASC
    """)
    abstract fun browseGroups(sourceId: Long?, categoryId: String?): PagingSource<Int, MovieBrowseGroup>

    /** Only fetch a selected group's variants, in exactly the representative's order. */
    @Query("""
        SELECT * FROM movies WHERE browseGroupKey = :groupKey
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY browseQualityRank DESC, name COLLATE BINARY ASC, id ASC
    """)
    abstract suspend fun browseVariants(groupKey: String, sourceId: Long?, categoryId: String?): List<Movie>

    @Query("SELECT * FROM movies WHERE (:sourceId IS NULL OR sourceId = :sourceId) ORDER BY addedMillis DESC, id ASC LIMIT :limit")
    abstract fun observeBrowseRecent(sourceId: Long?, limit: Int): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE (:sourceId IS NULL OR sourceId = :sourceId) ORDER BY addedMillis DESC, id ASC LIMIT :limit")
    abstract suspend fun browseFeedSample(sourceId: Long?, limit: Int): List<Movie>

    @Query("""
        SELECT sourceId, categoryId, COUNT(DISTINCT browseGroupKey) AS count FROM movies
        WHERE categoryId IS NOT NULL AND (:sourceId IS NULL OR sourceId = :sourceId)
        GROUP BY sourceId, categoryId
    """)
    abstract suspend fun browseCategoryCounts(sourceId: Long?): List<VodCategoryCount>

    @Query(
        """
        SELECT * FROM movies
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    abstract fun observe(categoryId: String?): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE favourite = 1 ORDER BY name")
    abstract fun observeFavourites(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    abstract fun search(query: String, limit: Int = 200): Flow<List<Movie>>

    /** Word-prefix title search through [app.opentv.data.model.MovieFts]; [match] is an FTS query. */
    @Query(
        """
        SELECT movies.* FROM movies JOIN movies_fts ON movies.id = movies_fts.docid
        WHERE movies_fts MATCH :match ORDER BY movies.name LIMIT :limit
        """
    )
    abstract fun searchFts(match: String, limit: Int = 200): Flow<List<Movie>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC LIMIT :limit")
    abstract fun observeRecentlyAdded(limit: Int): Flow<List<Movie>>

    /** Capped working set for derived feeds, not the full browse catalogue. */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC LIMIT $FEED_SAMPLE")
    abstract suspend fun all(): List<Movie>

    @Query("SELECT COUNT(*) FROM movies")
    abstract suspend fun count(): Int

    /** Read on category-sheet open, not observed during every sync batch. */
    @Query("SELECT categoryId AS categoryId, COUNT(*) AS count FROM movies WHERE categoryId IS NOT NULL GROUP BY categoryId")
    abstract suspend fun countsByCategory(): List<CategoryCount>

    @Query("SELECT EXISTS(SELECT 1 FROM movies)")
    abstract fun observeAny(): Flow<Boolean>

    /** Fallback for More-Like-This when a movie has no genre to match on. */
    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    abstract suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Movie>

    /** Substring match over provider-formatted cast; blank cast never matches. */
    @Query(
        """
        SELECT * FROM movies
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    abstract suspend fun moviesWithActor(name: String, limit: Int): List<Movie>

    @Query(
        """
        SELECT * FROM movies
        WHERE director IS NOT NULL AND director != '' AND director LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    abstract suspend fun moviesByDirector(name: String, limit: Int): List<Movie>

    @Query("SELECT * FROM movies WHERE id = :id")
    abstract suspend fun byId(id: Long): Movie?

    /** Stream URL is the cross-device key. */
    @Query("SELECT * FROM movies WHERE streamUrl = :url LIMIT 1")
    abstract suspend fun byStreamUrl(url: String): Movie?

    @Query("UPDATE movies SET favourite = :favourite WHERE id = :id")
    abstract suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("SELECT streamUrl FROM movies WHERE favourite = 1")
    abstract suspend fun favouriteUrls(): List<String>

    @Query("UPDATE movies SET favourite = 1 WHERE streamUrl = :url")
    abstract suspend fun markFavouriteByUrl(url: String)

    /** The sole entity-write entry point, including provider/TMDB enrichment. Recompute rather
     * than trusting copy() defaults: a changed name/year must never retain stale browse fields. */
    @Transaction
    open suspend fun upsertAll(movies: List<Movie>) {
        movies.chunked(500).forEach { batch ->
            upsertDerived(batch.map { movie ->
                val key = MovieBrowseKey.from(movie.name, movie.year)
                movie.copy(browseGroupKey = key.groupKey, browseQualityRank = key.qualityRank)
            })
        }
    }

    @Upsert
    protected abstract suspend fun upsertDerived(movies: List<Movie>)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    abstract suspend fun deleteForSource(sourceId: Long)
}
