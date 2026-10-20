package app.opentv.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.Embedded
import app.opentv.data.db.MovieDao
import app.opentv.data.db.SeriesDao
import app.opentv.data.model.Movie
import app.opentv.data.parser.MovieBrowseKey

/** Category IDs are provider-local, never a globally unique browse identity. */
data class VodCategoryKey(val sourceId: Long, val categoryId: String) {
    val token: String get() = "$sourceId:$categoryId"

    companion object {
        fun fromToken(token: String): VodCategoryKey = VodCategoryKey(
            token.substringBefore(':').toLong(), token.substringAfter(':'),
        )
    }
}

/** One SQL row per logical film. Variants are deliberately not loaded for browse cards. */
data class MovieBrowseGroup(
    @Embedded val primary: Movie,
    val variantCount: Int,
    val newestAddedMillis: Long,
) {
    val key: String get() = primary.browseGroupKey
    val qualityLabel: String get() = MovieBrowseKey.from(primary.name, primary.year).qualityLabel
}

data class VodBrowseFilter(val sourceId: Long? = null, val category: VodCategoryKey? = null) {
    init { require(category == null || sourceId == null || category.sourceId == sourceId) }
    val effectiveSourceId: Long? get() = category?.sourceId ?: sourceId
}

data class VodCategoryCount(val sourceId: Long, val categoryId: String, val count: Int)

/** Room owns invalidation and LIMIT/OFFSET; no in-memory catalogue grouping or page-boundary loss. */
class VodBrowseRepository(private val movies: MovieDao, private val series: SeriesDao) {
    private fun config() = PagingConfig(pageSize = 48, initialLoadSize = 96, prefetchDistance = 12,
        maxSize = 480, enablePlaceholders = false)

    fun movies(filter: VodBrowseFilter) = Pager(config()) {
        movies.browseGroups(filter.effectiveSourceId, filter.category?.categoryId)
    }.flow

    fun series(filter: VodBrowseFilter) = Pager(config()) {
        series.browse(filter.effectiveSourceId, filter.category?.categoryId)
    }.flow

    suspend fun variants(groupKey: String, filter: VodBrowseFilter): List<Movie> =
        movies.browseVariants(groupKey, filter.effectiveSourceId, filter.category?.categoryId)
}
