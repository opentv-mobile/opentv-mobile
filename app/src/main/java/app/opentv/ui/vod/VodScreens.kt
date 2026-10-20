/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.saveable.rememberSaveable
import app.opentv.data.parser.ChannelNameNormalizer
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import app.opentv.core.ServiceLocator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import app.opentv.ui.isPhoneLandscape
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.sourceTag
import app.opentv.ui.VodViewModel
import app.opentv.data.repo.VodCategoryKey
import androidx.compose.material3.TextButton
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage

/**
 * Movies: a modern, row-based home — Continue Watching, Recommended, Recently added and a row per
 * genre, each a horizontally-scrolling shelf of poster cards. A category chip strip along the top
 * keeps whole-category browsing one press away without a permanent rail eating the width. Clicking
 * a film opens its detail page rather than playing straight away, the streaming-app convention.
 */
@Composable
fun MoviesScreen(
    onOpenMovie: (Movie) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val categories by viewModel.movieCategories.collectAsState()
    // Films only here; episodes belong on the Series tab.
    val allResume by viewModel.continueWatching.collectAsState()
    val recommended by viewModel.recommendedMovies.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedMovies.collectAsState()
    val genreRows by viewModel.movieGenreRows.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedVodSource.collectAsState()
    val resume = remember(allResume, selectedSource) {
        allResume.filter { it.mediaKey.startsWith("movie:") && (selectedSource == null || it.sourceId == selectedSource) }
    }

    // Pull the movie library the first time this tab is opened, not at login; refresh the computed
    // home rows (recommended, by-genre) on open too — cheap, and covers a library already on disk.
    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    // Category is a provider-scoped token held by the VM; Home/All mode is navigation-saveable.
    val browseCategory by viewModel.selectedMovieCategory.collectAsState()
    var browseAll by rememberSaveable { mutableStateOf(false) }
    BackHandler(browseAll || browseCategory != null) {
        browseAll = false; viewModel.selectMovieCategory(null)
    }

    val hasMovies by viewModel.hasMovies.collectAsState()
    val hasContent = resume.isNotEmpty() || recommended.isNotEmpty() ||
        recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
      BrowseControls {
        SearchAffordance(onOpenSearch)
        if (sources.size > 1) {
            ProviderChips(
                sources = sources,
                selected = selectedSource,
                onSelectAll = { viewModel.selectVodSource(null) },
                onSelectSource = viewModel::selectVodSource,
            )
        }
        CategoryChips(
            // Providers decorate category names with superscripts ("⁴ᴷ ³⁸⁴⁰ᴾ"); fold them to plain text.
            entries = categories.map { category ->
                val name = ChannelNameNormalizer.foldSuperscripts(category.name)
                val provider = sources.firstOrNull { it.id == category.sourceId }?.name
                VodCategoryKey(category.sourceId, category.id).token to
                    if (selectedSource == null && sources.size > 1 && provider != null) "$name · $provider" else name
            },
            selected = browseCategory,
            homeSelected = browseCategory == null && !browseAll,
            onSelectHome = { browseAll = false; viewModel.selectMovieCategory(null) },
            onSelectCategory = { id -> browseAll = false; viewModel.selectMovieCategory(id) },
            loadCounts = viewModel::movieCategoryCounts,
        )
        BrowseAllButton(browseAll && browseCategory == null) {
            browseAll = true; viewModel.selectMovieCategory(null)
        }
      }
        // Weighted so the shelves fill the space under the fixed search + chips header, exactly and
        // unambiguously — the same reason Live TV weights its guide grid.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                browseCategory != null || browseAll -> PagedMovieGrid(
                    viewModel.moviePages.collectAsLazyPagingItems(), "$selectedSource/$browseCategory", onOpenMovie,
                    viewModel::movieBrowseVariants, sources.associate { it.id to it.name })
                !hasContent -> when {
                    // Not a confirmed-empty library (still answering, or rows exist and the
                    // shelves are building): show progress, never "no films".
                    vodLoading || isSyncing || hasMovies == null -> LoadingVod(stringResource(R.string.vod_loading_movies))
                    hasMovies == true -> BrowseAllButton(false) { browseAll = true }
                    hasSources -> EmptyVod(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_provider))
                    else -> EmptyVod(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_add))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (resume.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(resume, onResume) }
                    if (recommended.isNotEmpty()) item(key = "rec") {
                        MoviePosterRow(stringResource(R.string.vod_recommended), recommended, onOpenMovie)
                    }
                    if (recentlyAdded.isNotEmpty()) item(key = "recent") {
                        MoviePosterRow(stringResource(R.string.vod_recently_added), recentlyAdded, onOpenMovie)
                    }
                    items(genreRows, key = { "g:${it.genre}" }) { group ->
                        MoviePosterRow(group.genre, group.items, onOpenMovie)
                    }
                }
            }
        }
    }
}

/**
 * Shows: the same row-based home as Movies (Continue Watching, Recently added, genre rows) with the
 * category chip strip for whole-category browsing. A show opens its detail page — where episodes are
 * fetched on demand, since pulling every episode of every series up front is what makes a first sync
 * take forever.
 */
@Composable
fun SeriesScreen(
    onOpenSeries: (Series) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val categories by viewModel.seriesCategories.collectAsState()
    // Episodes only here; films belong on the Films tab.
    val allResume by viewModel.continueWatching.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedSeries.collectAsState()
    val genreRows by viewModel.seriesGenreRows.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedVodSource.collectAsState()
    val resume = remember(allResume, selectedSource) {
        allResume.filter { it.mediaKey.startsWith("ep:") && (selectedSource == null || it.sourceId == selectedSource) }
    }

    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    // Saveable: opening a title and coming back must land in the same category, not the shelves.
    val browseCategory by viewModel.selectedSeriesCategory.collectAsState()
    var browseAll by rememberSaveable { mutableStateOf(false) }
    BackHandler(browseAll || browseCategory != null) {
        browseAll = false; viewModel.selectSeriesCategory(null)
    }

    val hasSeries by viewModel.hasSeries.collectAsState()
    val hasContent = resume.isNotEmpty() || recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
      BrowseControls {
        SearchAffordance(onOpenSearch)
        if (sources.size > 1) {
            ProviderChips(
                sources = sources,
                selected = selectedSource,
                onSelectAll = { viewModel.selectVodSource(null) },
                onSelectSource = viewModel::selectVodSource,
            )
        }
        CategoryChips(
            // Providers decorate category names with superscripts ("⁴ᴷ ³⁸⁴⁰ᴾ"); fold them to plain text.
            entries = categories.map { category ->
                val name = ChannelNameNormalizer.foldSuperscripts(category.name)
                val provider = sources.firstOrNull { it.id == category.sourceId }?.name
                VodCategoryKey(category.sourceId, category.id).token to
                    if (selectedSource == null && sources.size > 1 && provider != null) "$name · $provider" else name
            },
            selected = browseCategory,
            homeSelected = browseCategory == null && !browseAll,
            onSelectHome = { browseAll = false; viewModel.selectSeriesCategory(null) },
            onSelectCategory = { id -> browseAll = false; viewModel.selectSeriesCategory(id) },
            loadCounts = viewModel::seriesCategoryCounts,
        )
        BrowseAllButton(browseAll && browseCategory == null) {
            browseAll = true; viewModel.selectSeriesCategory(null)
        }
      }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                browseCategory != null || browseAll -> PagedSeriesGrid(
                    viewModel.seriesPages.collectAsLazyPagingItems(), "$selectedSource/$browseCategory", onOpenSeries)
                !hasContent -> when {
                    vodLoading || isSyncing || hasSeries == null -> LoadingVod(stringResource(R.string.vod_loading_shows))
                    hasSeries == true -> BrowseAllButton(false) { browseAll = true }
                    hasSources -> EmptyVod(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_provider))
                    else -> EmptyVod(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_add))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (resume.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(resume, onResume) }
                    if (recentlyAdded.isNotEmpty()) item(key = "recent") {
                        SeriesPosterRow(stringResource(R.string.vod_recently_added), recentlyAdded, onOpenSeries)
                    }
                    items(genreRows, key = { "g:${it.genre}" }) { group ->
                        SeriesPosterRow(group.genre, group.items, onOpenSeries)
                    }
                }
            }
        }
    }
}

/** One horizontally scrollable control row, never a vertical scroll parent of a paged grid. */
@Composable
private fun BrowseControls(content: @Composable () -> Unit) {
    if (isPhoneLandscape()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) { content() }
    } else Column { content() }
}

@Composable
private fun BrowseAllButton(selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = !selected, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.vod_paging_see_all))
    }
}

// ---- Shared shelves ----------------------------------------------------------------------------

/** A titled horizontal shelf of movie poster cards. Shared by the home and the detail's "more like this". */
@Composable
internal fun MoviePosterRow(title: String, movies: List<Movie>, onOpenMovie: (Movie) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(movies, key = { it.id }) { movie ->
                PosterCard(
                    title = movie.displayTitle,
                    tagBadge = movie.sourceTag,
                    posterUrl = movie.posterUrl,
                    subtitle = movie.year?.toString(),
                    rating = movie.rating,
                    onClick = { onOpenMovie(movie) },
                )
            }
        }
    }
}

/** A titled horizontal shelf of series poster cards. */
@Composable
internal fun SeriesPosterRow(title: String, series: List<Series>, onOpenSeries: (Series) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(series, key = { it.id }) { item ->
                PosterCard(
                    title = item.displayTitle,
                    tagBadge = item.sourceTag,
                    posterUrl = item.posterUrl,
                    subtitle = item.year?.toString(),
                    rating = item.rating,
                    onClick = { onOpenSeries(item) },
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * The reusable poster card: art, title and an optional year, with a rating chip, a quality badge and
 * a resume progress bar drawn over the art where the data is there. The focused card scales up and
 * gains a primary border — the app's established focus cue — and, being focusable, the lazy row
 * brings it into view on its own.
 */
@Composable
internal fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rating: Double? = null,
    qualityBadge: String? = null,
    progress: Float? = null,
    /** Origin tag from the raw title (`EN`, `DE 4K`, `NF`) — which country/service copy this is. */
    tagBadge: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    Column(
        modifier
            // Smaller on phones so a shelf shows three-and-a-bit, not two-and-a-half.
            .width(if (isPhone()) PHONE_POSTER_WIDTH else POSTER_WIDTH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            rating?.takeIf { it > 0.0 }?.let {
                Badge(
                    text = "★ ${formatRating(it)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
            qualityBadge?.let {
                Badge(
                    text = it,
                    highlight = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            tagBadge?.let {
                Badge(text = it, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A small rounded chip drawn over poster art — a rating or a quality label. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary
    else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f)
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimary else androidx.compose.ui.graphics.Color.White
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Continue watching -------------------------------------------------------------------------

@Composable
internal fun ContinueWatchingRow(
    items: List<VodViewModel.ResumeItem>,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    // Hidden the instant "Remove" is tapped; the database delete (and the list re-reading it) can
    // lag behind a running sync, which made the card seem to ignore the tap.
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val shown = remember(items, removed) { items.filterNot { it.mediaKey in removed } }
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.vod_continue_watching))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.mediaKey }) { item ->
                ResumeCard(
                    item,
                    onClick = { onResume(item.mediaKey, item.streamUrl, item.title) },
                    // Forgetting the saved position drops it from this row (it's a live query).
                    onRemove = {
                        removed = removed + item.mediaKey
                        scope.launch {
                            graph.playbackPositions.delete(graph.settings.activeProfileId.value, item.mediaKey)
                        }
                    },
                )
            }
        }
    }
}

/** A landscape resume thumbnail with a progress fill — a movie or an episode part-way through. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(item: VodViewModel.ResumeItem, onClick: () -> Unit, onRemove: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Long-press (or long OK on a remote) opens a small menu to take it off Continue Watching.
    var menu by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "resumeScale")
    Column(
        Modifier
            .width(190.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(4.dp),
    ) {
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.vod_remove_continue)) },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                onClick = { menu = false; onRemove() },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(107.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Category chips ----------------------------------------------------------------------------

/**
 * A horizontal chip strip along the top of the home: a Home chip returns to the curated rows, and
 * each following chip opens that category's full grid. Keeps whole-category browsing reachable on a
 * d-pad without a permanent side rail taking the width.
 */
@Composable
private fun CategoryChips(
    entries: List<Pair<String, String>>,
    selected: String?,
    homeSelected: Boolean,
    onSelectHome: () -> Unit,
    onSelectCategory: (String) -> Unit,
    loadCounts: (suspend () -> Map<String, Int>)? = null,
) {
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        PhoneCategoryPicker(entries, selected, homeSelected, onSelectHome, onSelectCategory, loadCounts)
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "label") {
            Text(
                stringResource(R.string.vod_categories),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item(key = "all") { Chip(stringResource(R.string.vod_paging_home), homeSelected, onSelectHome) }
        items(entries, key = { it.first }) { (id, name) ->
            Chip(name, selected == id) { onSelectCategory(id) }
        }
    }
}

/**
 * Phones: a "Categories" button opening a searchable bottom sheet (film libraries run to hundreds of
 * categories — scrolling a chip strip sideways to find one doesn't work by touch), plus "All".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneCategoryPicker(
    entries: List<Pair<String, String>>,
    selected: String?,
    homeSelected: Boolean,
    onSelectHome: () -> Unit,
    onSelectCategory: (String) -> Unit,
    loadCounts: (suspend () -> Map<String, Int>)?,
) {
    var open by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(open) { if (open && loadCounts != null) counts = runCatching { loadCounts() }.getOrDefault(emptyMap()) }
    val selectedName = entries.firstOrNull { it.first == selected }?.second
    val compact = isPhoneLandscape()
    Row(
        Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())
            .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = if (compact) 0.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedName != null,
            onClick = { open = true },
            label = {
                Text(
                    selectedName ?: stringResource(R.string.vod_categories),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        FilterChip(selected = homeSelected, onClick = onSelectHome, label = { Text(stringResource(R.string.vod_paging_home)) })
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            var filter by remember { mutableStateOf("") }
            val shown = remember(entries, filter) {
                if (filter.isBlank()) entries else entries.filter { it.second.contains(filter.trim(), ignoreCase = true) }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.phone_live_filter_categories)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (filter.isBlank()) {
                        item(key = "all") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.vod_paging_home)) },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (homeSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                modifier = Modifier.fillMaxWidth().clickable { open = false; onSelectHome() },
                            )
                        }
                    }
                    items(shown, key = { it.first }) { (id, name) ->
                        ListItem(
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = counts[id]?.let { n ->
                                { Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (selected == id) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable { open = false; onSelectCategory(id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A provider filter above the category chips, shown only when more than one source is configured —
 * pick a provider to browse just its Movies/Shows categories, or "All sources" to fold them.
 */
@Composable
private fun ProviderChips(
    sources: List<Source>,
    selected: Long?,
    onSelectAll: () -> Unit,
    onSelectSource: (Long) -> Unit,
) {
    if (isPhoneLandscape()) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(selected = selected != null, onClick = { expanded = true },
                label = { Text(sources.firstOrNull { it.id == selected }?.name
                    ?: stringResource(R.string.channels_manager_all_sources), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.channels_manager_all_sources)) },
                    onClick = { expanded = false; onSelectAll() })
                sources.forEach { source ->
                    DropdownMenuItem(text = { Text(source.name) },
                        onClick = { expanded = false; onSelectSource(source.id) })
                }
            }
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "plabel") {
            Text(
                stringResource(R.string.channels_manager_source_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item(key = "pall") {
            Chip(stringResource(R.string.channels_manager_all_sources), selected == null, onSelectAll)
        }
        items(sources, key = { it.id }) { source ->
            Chip(source.name, selected == source.id) { onSelectSource(source.id) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ---- Search / loading / empty ------------------------------------------------------------------

/**
 * The search entry at the top of the Movies and Shows home. The rail's global search already covers
 * movies and series; this makes it reachable without leaving the tab. Focusable for d-pad on TV and
 * tappable on touch, it just opens the existing search screen.
 */
@Composable
private fun SearchAffordance(onOpenSearch: () -> Unit) {
    if (isPhoneLandscape()) {
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.vod_search_hint))
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onOpenSearch)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (focused) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(Icons.Filled.Search, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.vod_search_hint),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

@Composable
private fun EmptyVod(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingVod(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vod_large_provider_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Poster shelf card width; the grid uses an adaptive min size close to this. */
private val POSTER_WIDTH = 140.dp
private val PHONE_POSTER_WIDTH = 112.dp

@Composable
private fun isPhone(): Boolean = LocalLayoutClass.current == LayoutClass.PHONE

/** Rating to one decimal place, locale-independent (the "★" is drawn beside it). */
internal fun formatRating(rating: Double): String = String.format(java.util.Locale.US, "%.1f", rating)
