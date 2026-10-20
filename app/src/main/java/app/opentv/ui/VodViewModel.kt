/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import app.opentv.data.repo.VodBrowseRepository
import app.opentv.data.repo.VodBrowseFilter
import app.opentv.data.repo.VodCategoryKey
import app.opentv.R
import app.opentv.core.AppText
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.StremioStream
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.MovieVariantGroup
import app.opentv.data.repo.PersonTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VodViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** One card in the Continue Watching shelf — a movie or episode with somewhere left to go. */
    data class ResumeItem(
        val mediaKey: String,
        val title: String,
        val posterUrl: String?,
        val streamUrl: String,
        val progress: Float,
        val sourceId: Long,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<List<ResumeItem>> =
        settings.activeProfileId
            .flatMapLatest { pid -> graph.playbackPositions.observeRecent(pid) }
            .mapLatest { positions -> positions.filter { !it.isFinished }.mapNotNull { resolveResume(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun resolveResume(pos: PlaybackPosition): ResumeItem? {
        val parts = pos.mediaKey.split(":", limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val progress =
            if (pos.durationMillis > 0) (pos.positionMillis.toFloat() / pos.durationMillis).coerceIn(0f, 1f) else 0f
        return when (parts[0]) {
            "movie" -> graph.catalogRepository.movie(id)?.let {
                ResumeItem(pos.mediaKey, it.displayTitle, it.posterUrl, it.streamUrl, progress, it.sourceId)
            }
            "ep" -> graph.catalogRepository.episode(id)?.let {
                // "Show · S1E1 · Episode" reads cleanly; the raw provider title
                // ("A+ - Show - S01E01 - Episode") doesn't fit a card.
                val show = graph.catalogRepository.seriesByProviderId(it.sourceId, it.seriesId)
                val episodeTitle = app.opentv.data.parser.episodeDisplayTitle(it.title, show?.name)
                ResumeItem(
                    pos.mediaKey,
                    listOfNotNull(
                        show?.let { s -> VodTitleCleaner.clean(s.name).replace(Regex("\\s*\\(\\d{4}\\)$"), "") },
                        "S${it.season}E${it.episodeNumber}",
                        episodeTitle.takeIf { t -> t.isNotBlank() },
                    ).joinToString(" · "),
                    it.stillUrl,
                    it.streamUrl,
                    progress,
                    it.sourceId,
                )
            }
            else -> null
        }
    }

    private val movieCategory = MutableStateFlow<String?>(null)
    private val seriesCategory = MutableStateFlow<String?>(null)
    val selectedMovieCategory = movieCategory.asStateFlow()
    val selectedSeriesCategory = seriesCategory.asStateFlow()
    private val browseRepository = VodBrowseRepository(graph.database.movies(), graph.database.series())

    /** Provider filter for Movies/Shows. null = every source. Surfaced only when there's >1 source. */
    private val vodSource = MutableStateFlow<Long?>(null)
    val selectedVodSource = vodSource.asStateFlow()

    /** Providers, so Movies/Shows can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The category token includes its provider even when browsing "All sources".
    val movieCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.MOVIE),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seriesCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.SERIES),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val moviePages = combine(selectedVodSource, movieCategory) { source, token ->
        VodBrowseFilter(source, token?.let(VodCategoryKey::fromToken)
            ?.takeIf { source == null || it.sourceId == source })
    }.distinctUntilChanged().flatMapLatest(browseRepository::movies).cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesPages = combine(selectedVodSource, seriesCategory) { source, token ->
        VodBrowseFilter(source, token?.let(VodCategoryKey::fromToken)
            ?.takeIf { source == null || it.sourceId == source })
    }.distinctUntilChanged().flatMapLatest(browseRepository::series).cachedIn(viewModelScope)

    fun selectMovieCategory(id: String?) { movieCategory.value = id }

    fun selectSeriesCategory(id: String?) { seriesCategory.value = id }

    /** On-demand variants for a selected card; never invoked once per browse row. */
    suspend fun movieBrowseVariants(groupKey: String): List<Movie> = browseRepository.variants(
        groupKey, VodBrowseFilter(selectedVodSource.value,
            movieCategory.value?.let(VodCategoryKey::fromToken)),
    )

    /** True when at least one Stremio add-on is configured — gates the "add-on sources" button. */
    val hasAddons: StateFlow<Boolean> =
        settings.stremioAddons
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.stremioAddons.value.isNotEmpty())

    /**
     * The streams the configured add-ons offer for [movie], resolved through the user's TMDB key for
     * the IMDb id. Empty when there's no key, no add-ons, no IMDb match, or nothing came back. Each
     * add-on is queried independently so one failing doesn't sink the rest.
     */
    suspend fun addonStreams(movie: Movie): List<StremioStream> {
        val addons = settings.stremioAddons.value
        if (addons.isEmpty()) return emptyList()
        val imdb = graph.catalogRepository.imdbIdFor(movie) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            addons.flatMap { addon ->
                runCatching { graph.stremioClient.streams(addon.manifestUrl, addon.name, "movie", imdb) }
                    .getOrDefault(emptyList())
            }
        }
    }

    /** Switch the Movies/Shows provider filter; category selections reset since they differ per source. */
    fun selectVodSource(sourceId: Long?) {
        if (selectedVodSource.value == sourceId) return
        movieCategory.value = null
        seriesCategory.value = null
        _recommendedMovies.value = emptyList()
        _movieGenreRows.value = emptyList()
        _seriesGenreRows.value = emptyList()
        vodSource.value = sourceId
    }

    // ---- Netflix-style home rows ----------------------------------------------------------------
    // "Recently added" is reactive: it fills in live as a VOD sync lands. The computed feeds
    // (recommended, by-genre) are held in the StateFlows below and (re)built by [loadHomeFeeds] from
    // one bounded recent sample — guarded against redundant reads on tab reopens.

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentlyAddedMovies: StateFlow<List<Movie>> =
        selectedVodSource.flatMapLatest { graph.database.movies().observeBrowseRecent(it, 40) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentlyAddedSeries: StateFlow<List<Series>> =
        selectedVodSource.flatMapLatest { graph.database.series().observeBrowseRecent(it, 40) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recommendedMovies = MutableStateFlow<List<Movie>>(emptyList())
    val recommendedMovies: StateFlow<List<Movie>> = _recommendedMovies.asStateFlow()

    private val _movieGenreRows = MutableStateFlow<List<GenreGroup<Movie>>>(emptyList())
    val movieGenreRows: StateFlow<List<GenreGroup<Movie>>> = _movieGenreRows.asStateFlow()

    private val _seriesGenreRows = MutableStateFlow<List<GenreGroup<Series>>>(emptyList())
    val seriesGenreRows: StateFlow<List<GenreGroup<Series>>> = _seriesGenreRows.asStateFlow()

    /** The (profile, catalogue) the computed rows were last built for — the redundant-reload guard. */
    private data class HomeFeedsKey(val profileId: Long, val movieCount: Int, val seriesCount: Int, val sourceId: Long?)

    @Volatile private var loadedHomeFeeds: HomeFeedsKey? = null

    /** Serialises [loadHomeFeeds] so two near-simultaneous calls (screen open + the profile emit in
     *  `init`) can't both start the heavy scan — the second waits, sees the guard satisfied, returns.
     *  MUST be declared before the `init` block below: `activeProfileId` is a StateFlow, so its
     *  `collect` fires synchronously during construction, and Kotlin initialises properties top to
     *  bottom — declared after `init`, this mutex would still be null when the first collect runs. */
    private val homeFeedsMutex = Mutex()

    init {
        // The home feeds are per profile (Recommended) and per catalogue (the genre rows). Rebuild
        // them when the active profile changes — and once at start. Routed through the guarded
        // [loadHomeFeeds] so a profile switch triggers exactly one library scan, and re-opening the
        // tab with the same profile and an unchanged catalogue triggers none. Reads an empty result
        // until a VOD sync has populated the catalogue; [ensureVodLoaded] re-runs it once one has.
        viewModelScope.launch {
            combine(settings.activeProfileId, selectedVodSource) { _, _ -> Unit }.collect { loadHomeFeeds() }
        }
    }

    /** Builds bounded curated shelves from at most 2,000 recent titles per kind and provider.
     * Complete discovery belongs to the paged grids, never an unbounded Home catalogue read. */
    fun loadHomeFeeds() {
        viewModelScope.launch {
            homeFeedsMutex.withLock {
                val profileId = settings.activeProfileId.value
                val sourceId = selectedVodSource.value
                val movieCount = runCatching { graph.catalogRepository.movieCount() }.getOrDefault(0)
                val seriesCount = runCatching { graph.catalogRepository.seriesCount() }.getOrDefault(0)

                // Already built for this profile and this exact catalogue? Then a tab re-open or a
                // recomposition must not trigger another full-library scan. Only a profile change or
                // a catalogue whose size moved gets past here.
                val prev = loadedHomeFeeds
                if (prev != null && prev.profileId == profileId &&
                    prev.movieCount == movieCount && prev.seriesCount == seriesCount && prev.sourceId == sourceId
                ) return@withLock

                // Room suspending queries run off the main thread; reuse each sample for all rows.
                // Home is a bounded sample, not another route to materialising the catalogue.
                val allMovies = graph.database.movies().browseFeedSample(sourceId, 2_000)
                val allSeries = graph.database.series().browseFeedSample(sourceId, 2_000)
                if (selectedVodSource.value != sourceId || settings.activeProfileId.value != profileId) return@withLock

                val recommended = runCatching {
                    graph.catalogRepository.recommendedMoviesFrom(allMovies, profileId)
                }.getOrDefault(emptyList())
                val movieGenres = runCatching {
                    graph.catalogRepository.moviesByGenreFrom(allMovies)
                }.getOrDefault(emptyList())
                val seriesGenres = runCatching {
                    graph.catalogRepository.seriesByGenreFrom(allSeries)
                }.getOrDefault(emptyList())
                if (selectedVodSource.value != sourceId || settings.activeProfileId.value != profileId) return@withLock
                _recommendedMovies.value = recommended
                _movieGenreRows.value = movieGenres
                _seriesGenreRows.value = seriesGenres
                loadedHomeFeeds = HomeFeedsKey(profileId, movieCount, seriesCount, sourceId)
            }
        }
    }

    /**
     * Whether any films / series are on disk: null until the first answer. The tabs only claim
     * "no films" on a confirmed false — reading the empty initial state as "nothing here" is what
     * showed "no movies" right after an update, while a busy database was still answering.
     */
    val hasMovies: StateFlow<Boolean?> = graph.catalogRepository.observeHasMovies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasSeries: StateFlow<Boolean?> = graph.catalogRepository.observeHasSeries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Titles per category, for the phone category sheet (read on open). */
    suspend fun movieCategoryCounts(): Map<String, Int> =
        graph.database.movies().browseCategoryCounts(selectedVodSource.value)
            .associate { VodCategoryKey(it.sourceId, it.categoryId).token to it.count }
    suspend fun seriesCategoryCounts(): Map<String, Int> =
        graph.database.series().browseCategoryCounts(selectedVodSource.value)
            .associate { VodCategoryKey(it.sourceId, it.categoryId).token to it.count }

    // Movies + series load on demand — the first time the user opens Movies or Shows — rather than
    // up front at login. A provider's 40,000-title VOD list is exactly what makes a first sync
    // crawl, and most sessions only ever watch live TV. Loaded once per app run.
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    @Volatile private var vodRequested = false

    /** How long a fetched VOD catalogue is trusted before a warm launch re-syncs it. */
    private val VOD_TTL_MILLIS = 12L * 60 * 60 * 1000  // 12 hours

    fun ensureVodLoaded() {
        if (vodRequested) return
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = false) }
    }

    /**
     * Force a fresh download of the movies/series catalogue, ignoring the freshness cache. Wired
     * to the pull-to-refresh / refresh action so the user always has a way to pull new titles in
     * before the TTL lapses.
     */
    fun refreshVod() {
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = true) }
    }

    private suspend fun syncVodIfStale(force: Boolean) {
        val now = System.currentTimeMillis()

        // Warm-launch fast path. The catalogue is persisted in Room, so once it has been fetched
        // there is no reason to re-download and re-upsert a 40k-title list on every launch — that
        // was both the multi-minute "Loading movies & shows…" and the bandwidth hog that starved
        // the live preview into buffering. When we synced recently and already have rows, skip the
        // network entirely and just (re)build the home shelves off what is stored.
        if (!force) {
            val haveCatalogue = runCatching {
                graph.catalogRepository.movieCount() + graph.catalogRepository.seriesCount()
            }.getOrDefault(0) > 0
            val last = settings.vodSyncedAtMillis
            val fresh = last > 0 && now - last < VOD_TTL_MILLIS
            if (haveCatalogue && fresh) {
                loadHomeFeeds()
                return
            }
        }

        _vodLoading.value = true
        val synced = StatusBus.during(AppText.get(R.string.msg_loading_vod)) {
            runCatching {
                for (source in graph.sourceRepository.enabled()) {
                    graph.catalogRepository.syncVod(source, now)
                }
            }.isSuccess
        }
        _vodLoading.value = false
        // Stamp the cache only when the fetch actually succeeded, so a failed sync retries on the
        // next open instead of being remembered as "fresh" and leaving the user with no catalogue.
        if (synced) settings.vodSyncedAtMillis = now
        if (synced) loadedHomeFeeds = null
        // The catalogue may have grown — recompute the computed home rows off the fresh data.
        loadHomeFeeds()
    }

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    fun setVodSearchQuery(text: String) { vodSearchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchMovies(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchSeries(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleMovieFavourite(movie: Movie) {
        viewModelScope.launch {
            graph.catalogRepository.setMovieFavourite(movie.id, !movie.favourite)
        }
    }

    /**
     * Episodes are fetched on demand rather than during catalogue sync.
     *
     * Pulling every episode of every series up front is what makes a first sync take twenty
     * minutes on a large provider, and most of it is never looked at.
     */
    fun loadEpisodes(series: Series) {
        viewModelScope.launch {
            val source = graph.sourceRepository.byId(series.sourceId) ?: return@launch
            graph.catalogRepository.ensureEpisodes(source, series.seriesId)
        }
    }

    fun episodes(series: Series) =
        graph.catalogRepository.observeEpisodes(series.sourceId, series.seriesId)

    /** For the series detail screen: look the series up by row id. */
    fun seriesById(id: Long): kotlinx.coroutines.flow.Flow<Series?> =
        kotlinx.coroutines.flow.flow { emit(graph.catalogRepository.series(id)) }

    /** An always-empty episode flow, so the detail screen has something before a series loads. */
    val noEpisodes: kotlinx.coroutines.flow.Flow<List<app.opentv.data.model.Episode>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun movieById(id: Long): Movie? = graph.catalogRepository.movie(id)

    // ---- Detail screens -------------------------------------------------------------------------
    // Thin wrappers over the repository's detail feeds. The screens drive them from `produceState`
    // keyed on the id, so each detail instance owns its own state and a "More like this" hop to
    // another title loads cleanly without two screens fighting over one shared StateFlow.

    /** Loads a movie, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.movieDetail. */
    suspend fun movieDetail(id: Long): Movie? = graph.catalogRepository.movieDetail(id)

    /** Loads a series, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.seriesDetail. */
    suspend fun seriesDetail(id: Long): Series? = graph.catalogRepository.seriesDetail(id)

    /** "More like this" for the movie detail screen. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    /** "More like this" for the series detail screen. */
    suspend fun moreLikeThisSeries(series: Series): List<Series> =
        graph.catalogRepository.moreLikeThisSeries(series)

    /** Every library title (movies + series) featuring a person — the Person screen's grid. */
    suspend fun titlesWithPerson(name: String): List<PersonTitle> =
        graph.catalogRepository.titlesWithPerson(name)

    /** The active profile's saved resume position for a movie/episode, or null — drives Resume vs Watch now. */
    suspend fun resumePosition(mediaKey: String): PlaybackPosition? =
        graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)

    /** Collapses quality variants of a movie list for a browse grid. See CatalogRepository.collapseVariants. */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> =
        graph.catalogRepository.collapseVariants(movies)

    /** Star / un-star a whole series. Wraps the series DAO through the shared database — no data-layer change. */
    fun toggleSeriesFavourite(series: Series) {
        viewModelScope.launch {
            graph.database.series().setFavourite(series.id, !series.favourite)
        }
    }

    /** The user-agent to play a movie/episode with (per source). */
    suspend fun userAgentForSource(sourceId: Long): String =
        graph.sourceRepository.byId(sourceId)?.userAgent ?: "OpenTV/0.1 (Android)"
}
