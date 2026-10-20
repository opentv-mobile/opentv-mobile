package app.opentv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.sourceTag
import app.opentv.data.repo.MovieBrowseGroup
import app.opentv.data.parser.MovieBrowseKey
import kotlinx.coroutines.CancellationException
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass

@Composable
internal fun PagedMovieGrid(
    items: LazyPagingItems<MovieBrowseGroup>, filterKey: String, onOpen: (Movie) -> Unit,
    loadVariants: suspend (String) -> List<Movie>, sourceNames: Map<Long, String>,
) {
    // Reset selection when filters change; LaunchedEffect cancels an obsolete in-flight read.
    var selected by remember(filterKey) { mutableStateOf<MovieBrowseGroup?>(null) }
    PagedVodGrid(items, filterKey, { it.key }) { group ->
        val movie = group.primary
        PosterCard(
            title = movie.displayTitle, posterUrl = movie.posterUrl,
            subtitle = movie.year?.toString(), rating = movie.rating, tagBadge = movie.sourceTag,
            qualityBadge = group.qualityLabel.takeIf { it.isNotBlank() }
                ?: if (group.variantCount > 1) stringResource(R.string.guide_qualities_count, group.variantCount) else null,
            onClick = { if (group.variantCount > 1) selected = group else onOpen(movie) },
        )
    }
    selected?.let { group ->
        androidx.compose.runtime.key(group.key, filterKey) {
            MovieVariantsDialog(group, sourceNames, loadVariants,
                onDismiss = { selected = null }, onOpen = { selected = null; onOpen(it) })
        }
    }
}

@Composable
private fun MovieVariantsDialog(
    group: MovieBrowseGroup, sourceNames: Map<Long, String>,
    loadVariants: suspend (String) -> List<Movie>, onDismiss: () -> Unit, onOpen: (Movie) -> Unit,
) {
    var retry by remember { mutableStateOf(0) }
    var result by remember(group.key) { mutableStateOf<Result<List<Movie>>?>(null) }
    LaunchedEffect(group.key, retry) {
        result = null
        result = try { Result.success(loadVariants(group.key)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.primary.displayTitle) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            when {
                result == null -> PagingLoading()
                result!!.isFailure -> PagingError { retry++ }
                result!!.getOrThrow().isEmpty() -> Text(stringResource(R.string.vod_paging_empty))
                else -> LazyColumn {
                    items(result!!.getOrThrow(), key = { it.id }) { movie ->
                        val quality = MovieBrowseKey.from(movie.name, movie.year).qualityLabel
                            .ifBlank { stringResource(R.string.player_quality_standard) }
                        ListItem(
                            headlineContent = { Text(movie.name) },
                            supportingContent = { Text("${sourceNames[movie.sourceId] ?: stringResource(R.string.rec_source_unknown)} · $quality") },
                            modifier = Modifier.clickable { onOpen(movie) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun PagedSeriesGrid(items: LazyPagingItems<Series>, filterKey: String, onOpen: (Series) -> Unit) {
    PagedVodGrid(items, filterKey, { it.id }) { series ->
        PosterCard(title = series.displayTitle, posterUrl = series.posterUrl,
            subtitle = series.year?.toString(), rating = series.rating, tagBadge = series.sourceTag,
            onClick = { onOpen(series) })
    }
}

@Composable
private fun <T : Any> PagedVodGrid(
    items: LazyPagingItems<T>, filterKey: String, key: (T) -> Any, card: @Composable (T) -> Unit,
) {
    // A detail push saves the viewport, while an actual filter change starts at the top.
    // Declare before load-state branches so invalidations never discard the saved viewport.
    val state = rememberSaveable(filterKey, saver = LazyGridState.Saver) { LazyGridState() }
    val refresh = items.loadState.refresh
    if (items.itemCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (refresh) {
                is LoadState.Loading -> PagingLoading()
                is LoadState.Error -> PagingError(items::retry)
                is LoadState.NotLoading -> Text(stringResource(R.string.vod_paging_empty))
            }
        }
        return
    }
    val phone = LocalLayoutClass.current == LayoutClass.PHONE
    Column(Modifier.fillMaxSize()) {
        when (refresh) {
            is LoadState.Loading -> PagingLoading()
            is LoadState.Error -> PagingError(items::retry)
            else -> Unit
        }
        LazyVerticalGrid(
            columns = if (phone) GridCells.Fixed(3) else GridCells.Adaptive(140.dp),
            state = state, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(if (phone) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (phone) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 16.dp),
        ) {
            items(count = items.itemCount, key = items.itemKey(key)) { index ->
                items[index]?.let { card(it) }
            }
            item(key = "paging-footer", span = { GridItemSpan(maxLineSpan) }) {
                when (items.loadState.append) {
                    is LoadState.Loading -> PagingLoading()
                    is LoadState.Error -> PagingError(items::retry)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun PagingLoading() {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(stringResource(R.string.vod_paging_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PagingError(retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.vod_paging_error))
        TextButton(onClick = retry) { Text(stringResource(R.string.vod_paging_retry)) }
    }
}
