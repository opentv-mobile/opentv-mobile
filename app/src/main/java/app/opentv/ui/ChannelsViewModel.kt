/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.core.AppText
import app.opentv.core.AppSettings
import app.opentv.data.model.shownName
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.distinctByQuality
import app.opentv.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import app.opentv.core.StatusBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.opentv.ui.channels.PhoneGuideTimeline
import app.opentv.data.db.PhoneGuideHorizon

class ChannelsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** null = All. Exposed so the sidebar can highlight the active entry. */
    val selectedCategory = MutableStateFlow<String?>(null)
    val favouritesOnly = MutableStateFlow(false)
    /** The "Recent" list: channels played lately, most recent first. */
    val recentsOnly = MutableStateFlow(false)
    /** Provider filter for the live guide. null = every source. Only surfaced when there's >1 source. */
    val selectedSource = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")
    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    /**
     * One rail entry = one *logical* category.
     *
     * Providers split categories by codec — 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc'
     * are the same shelf twice. Fold them by normalised name, show the clean label, and
     * filter across every underlying id at once. This is the on-device version of what
     * Viewella did on a server it had to pay for.
     */
    /**
     * [key] includes the country (`ES:deportes`) so `ES| Deportes` and `DE| Deportes` stay apart.
     * [legacyKey] is the country-less key older builds stored (`deportes`); saved hidden / shown
     * sets may still hold it, and it keeps matching every country's version — see [matches].
     */
    data class CategoryGroup(
        val key: String,
        val label: String,
        val ids: List<String>,
        val legacyKey: String = key,
    ) {
        fun matches(keys: Set<String>): Boolean = key in keys || legacyKey in keys
    }

    val categoryGroups: StateFlow<List<CategoryGroup>> =
        combine(
            // Film/series syncs rewrite the categories table too; skip re-folding when the live
            // list itself didn't change.
            graph.catalogRepository.observeCategories(StreamKind.LIVE).distinctUntilChanged(),
            selectedSource,
        ) { raw, sourceId ->
            // Scope the category rail to the chosen provider, so a second playlist's categories show
            // on their own (cardiodoc's "keep sources separate"); null folds across every provider.
            val scoped = if (sourceId == null) raw else raw.filter { it.sourceId == sourceId }
            foldCategories(scoped)
        }
            // Normalising thousands of category names is real work — keep it off the UI thread,
            // where it used to freeze typing and taps whenever the categories table changed.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Folds codec-split provider categories into one logical [CategoryGroup] each — 'UK| GENERAL
     * HD/RAW' and 'UK| GENERAL hevc' become a single "General" entry that filters across every
     * underlying id at once. Shared by the guide's [categoryGroups] and the manager's
     * [managerCategoryGroups] so both group the rail identically.
     */
    private fun foldCategories(raw: List<Category>): List<CategoryGroup> {
        val groups = LinkedHashMap<String, Triple<String, String, MutableList<String>>>()
        for (category in raw) {
            val n = ChannelNameNormalizer.normalize(category.name)
            val legacy = n.groupKey.ifEmpty { category.id }
            // Keep the country: it's what people filter by ("ES|"), and folding it away merged
            // every country's "Sports" into one group.
            val region = n.region?.uppercase()
            val key = region?.let { "$it:$legacy" } ?: legacy
            val label = region?.let { "$it| ${n.baseName}" } ?: n.baseName
            val entry = groups.getOrPut(key) { Triple(label, legacy, mutableListOf()) }
            entry.third += category.id
        }
        return groups.map { (key, v) -> CategoryGroup(key, v.first, v.third, legacyKey = v.second) }
    }

    /**
     * The category ids to hide from the guide *right now*: the ids behind every group the user
     * marked adult — unless the session is unlocked, in which case nothing is hidden.
     */
    private val hiddenCategoryIds: StateFlow<Set<String>> =
        combine(categoryGroups, settings.hiddenCategories, settings.hiddenUnlocked) { groups, hidden, unlocked ->
            if (unlocked) emptySet()
            else groups.filter { it.matches(hidden) }.flatMap { it.ids }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Every category the user may pick from: hidden ones drop out until the session is unlocked. */
    val filterableCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(categoryGroups, settings.hiddenCategories, settings.hiddenUnlocked) { groups, hidden, unlocked ->
            if (unlocked) groups else groups.filter { !it.matches(hidden) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Channels per category group (all its folded ids summed), for the category sheet. */
    val categoryCounts: StateFlow<Map<String, Int>> =
        combine(filterableCategoryGroups, graph.catalogRepository.observeChannelCounts()) { groups, counts ->
            val byId = counts.associate { it.categoryId to it.count }
            groups.associate { g -> g.key to g.ids.sumOf { byId[it] ?: 0 } }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val channelSort: StateFlow<AppSettings.ChannelSort> = settings.channelSort

    fun setChannelSort(sort: AppSettings.ChannelSort) = settings.setChannelSort(sort)

    /** The "My categories" choice (group keys); empty = no filter. */
    val shownCategories: StateFlow<Set<String>> = settings.shownCategories

    fun setShownCategories(keys: Set<String>) = settings.setShownCategories(keys)

    /** The rail's view of categories: the filterable ones, narrowed to "My categories" if set. */
    val visibleCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(filterableCategoryGroups, settings.shownCategories) { groups, shown ->
            if (shown.isEmpty()) groups else groups.filter { it.matches(shown) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Category ids behind "My categories", or null when no filter is set. Scopes All + global search. */
    private val allowedCategoryIds: StateFlow<Set<String>?> =
        combine(categoryGroups, settings.shownCategories) { groups, shown ->
            if (shown.isEmpty()) null
            else groups.filter { it.matches(shown) }.flatMap { it.ids }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * One row on screen = one *logical* channel.
     *
     * [primary] is the best-quality variant and what plays on click; [variants] is every
     * quality of the same channel, for the in-player switch. `UK| BBC ONE SD/HD/FHD/RAW`
     * is one row, not four — the guide has one BBC One, and so should we.
     */
    @androidx.compose.runtime.Immutable
    data class Row(
        val primary: Channel,
        val variants: List<Channel>,
        val now: Programme?,
        val next: Programme?,
        /** Every programme for this channel inside the guide window, start-ordered. */
        val programmes: List<Programme>,
    ) {
        val key: Any get() = if (primary.groupKey.isEmpty()) primary.id else primary.groupKey
    }

    /**
     * The guide's left edge: the current half-hour, rounded down. Programme block positions
     * are measured from here. Recomputed on each tick so the grid drifts with real time.
     */
    /** Which day the guide is browsing: 0 = today, 1 = tomorrow… so it pages forward like Sky Q. */
    private val _guideDayOffset = MutableStateFlow(0)
    val guideDayOffset: StateFlow<Int> = _guideDayOffset.asStateFlow()

    /** Page the guide a day forward/back (clamped to today…+[GUIDE_MAX_DAYS]), or jump back to now. */
    fun nudgeGuideDay(delta: Int) {
        _guideDayOffset.value = (_guideDayOffset.value + delta).coerceIn(0, GUIDE_MAX_DAYS)
    }

    fun guideToNow() { _guideDayOffset.value = 0 }

    val windowStartMillis: StateFlow<Long> =
        combine(nowTick.map { it - it % HALF_HOUR_MILLIS }, _guideDayOffset) { base, day ->
            // Today tracks the current half-hour so live is always on screen; a future day starts
            // at that day's local midnight, so the whole day's schedule is browsable and drifts
            // with real time only on the "today" page.
            if (day <= 0) base else startOfLocalDay(base) + day * DAY_MILLIS
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            System.currentTimeMillis().let { it - it % HALF_HOUR_MILLIS },
        )

    private fun startOfLocalDay(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private data class RowsKey(
        val categoryIds: List<String>?,
        val favs: Boolean,
        val query: String,
        val hiddenIds: Set<String>,
        val allowedIds: Set<String>? = null,
        val recents: Boolean = false,
    )

    /** Phone EPG is loaded separately for bounded visible rows, never by programmeIndex. */
    // Until HomeScreen reports the form factor, never start a catalogue-wide EPG read.
    private val compactGuide = MutableStateFlow<Boolean?>(null)

    fun setCompactGuide(compact: Boolean) { compactGuide.value = compact }

    val phoneGuideVersion = graph.epgRepository.guideVersion

    suspend fun phoneGuideHorizon(): PhoneGuideHorizon = withContext(Dispatchers.IO) {
        graph.database.programmes().phoneHorizon()
    }

    /** Callers pass only a bounded viewport of logical rows, never the full channel catalogue. */
    suspend fun phoneProgrammes(visibleRows: List<Row>, window: PhoneGuideTimeline.Window, nowNext: Boolean = false): Map<Any, List<Programme>> =
        withContext(Dispatchers.IO) {
            val bounded = visibleRows.take(40)
            val candidates = bounded.flatMap { row -> row.variants.flatMap { it.epgCandidates } }.distinct()
            val dao = graph.database.programmes()
            val index = (if (nowNext) dao.phoneNowNext(candidates, window.start)
                else dao.phoneWindow(candidates, window.start, window.end)).groupBy { it.epgChannelId }
            bounded.associate { row ->
                row.key to row.variants.asSequence().flatMap { it.epgCandidates.asSequence() }
                    .firstNotNullOfOrNull { index[it] }.orEmpty()
            }
        }

    /**
     * The whole guide window's programmes, grouped by their EPG channel id once — the single
     * expensive step. Grouping the entire catalogue's programmes (hundreds of thousands of rows
     * on a big provider) is what made every category tap slow, because it used to run inside the
     * per-category flow and rebuild from scratch each time. Built here once, shared across every
     * category switch, so a tap only has to regroup that category's channels — quick.
     *
     * Keyed on the half-hour bucket (not the raw minute tick) so it holds steady while you flick
     * between categories and refreshes at most twice an hour — plus once per EPG feed that lands
     * ([EpgRepository.guideVersion]). Deliberately not a Room flow on the programmes table: that
     * re-ran this whole-window read, and the 20k-row rebuild behind it, on every batch a guide
     * sync wrote, which kept the database and CPU pinned for the entire sync.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val programmeIndex: StateFlow<Map<String, List<Programme>>> =
        // windowStartMillis is a StateFlow, which already conflates equal values — an explicit
        // distinctUntilChanged() on it is a no-op (and a build error under our warnings-as-errors).
        combine(windowStartMillis, graph.epgRepository.guideVersion, compactGuide) { windowStart, _, compact ->
            windowStart to if (compact == false) GUIDE_LOOKAHEAD_MILLIS else 0L
        }
            .mapLatest { (windowStart, lookahead) ->
                if (lookahead == 0L) return@mapLatest emptyMap()
                graph.epgRepository
                    .window(windowStart, windowStart + lookahead)
                    .groupBy { it.epgChannelId }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Guards the start-up loading bar so it shows once, on the first build, not on every tap. */
    @Volatile
    private var guideBuilt = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<Row>> =
        combine(selectedCategory, favouritesOnly, query, categoryGroups, hiddenCategoryIds) {
                category, favs, q, groups, hidden ->
            val ids = category?.let { key -> groups.firstOrNull { it.key == key }?.ids }
            RowsKey(ids, favs, q, hidden)
        }
            .combine(allowedCategoryIds) { key, allowed -> key.copy(allowedIds = allowed) }
            .combine(recentsOnly) { key, recents -> key.copy(recents = recents) }
            .combine(selectedSource) { key, source -> key to source }
            .flatMapLatest { (key, source) ->
                // Inside a category (or favourites) the query narrows that list; only from All does
                // it search the whole catalogue.
                val q = key.query.trim()
                val scopedList = key.recents || key.favs || key.categoryIds != null
                val allowedIds = key.allowedIds
                val baseFlow = when {
                    // Recent: look up the played ids in order (a handful), newest first.
                    key.recents -> settings.recentChannels.mapLatest { ids ->
                        ids.mapNotNull { graph.catalogRepository.channel(it) }.filterNot { it.hidden }
                    }
                    key.favs -> graph.catalogRepository.observeFavouriteChannels()
                    key.categoryIds != null -> graph.catalogRepository.observeChannelsIn(key.categoryIds)
                    q.isNotEmpty() -> graph.catalogRepository.searchChannels(q)
                    // "All" with My categories set: let SQL fetch only those categories rather than
                    // loading the whole line-up (50k+ channels on big providers) to filter in memory.
                    // Bounded by SQLite's bind-variable limit; larger sets fall back to the filter below.
                    allowedIds != null && allowedIds.size <= MAX_SQL_CATEGORY_IDS ->
                        graph.catalogRepository.observeChannelsIn(allowedIds.toList())
                    else -> graph.catalogRepository.observeChannels(source)
                }
                val channelFlow = baseFlow.map { list ->
                    var out = list
                    if (scopedList && q.isNotEmpty()) {
                        out = out.filter { it.displayName.contains(q, true) || it.name.contains(q, true) }
                    }
                    // "My categories" narrows All and global search, never favourites or a picked category.
                    val allowed = key.allowedIds
                    if (!scopedList && allowed != null) out = out.filter { it.categoryId in allowed }
                    out
                }
                // Combine the (per-category) channel list with the shared, already-grouped
                // programme index. Switching category rebuilds only the channel→row grouping;
                // the heavy programme grouping happened once and is reused, so a tap is quick.
                // Deliberately NOT combined with the minute tick: building "All channels" (20k
                // rows) can take longer than 60s, and a tick landing mid-build would cancel and
                // restart it via flatMapLatest so it would never finish. "Now" is captured per
                // build instead; the guide's live progress bars advance off the screen's clock.
                combine(channelFlow, programmeIndex) { channels, byEpgChannel ->
                    val now = System.currentTimeMillis()
                    // Scope to the chosen provider. The "All" branch already fetched only this source
                    // in SQL, so this is a no-op there; for favourites/search/category (which don't
                    // take a sourceId) it's what actually keeps a second playlist separate.
                    val scoped = if (source == null) channels else channels.filter { it.sourceId == source }
                    // Adult/hidden channels drop out everywhere they could otherwise leak —
                    // All, search and favourites — until the session is unlocked.
                    val visible =
                        if (key.hiddenIds.isEmpty()) scoped
                        else scoped.filter { it.categoryId !in key.hiddenIds }
                    // Show the size-aware loading bar for the FIRST guide build only (start-up).
                    // After that the guide is built and flicking between categories is cheap, so
                    // don't flash a loading line on every tap — that bar was only ever meant for
                    // the one-time heavy load, and leaking it per-category read as a stuck "100%".
                    val firstBuild = !guideBuilt
                    if (firstBuild) StatusBus.set(sizeMessage(visible.size), 0f)
                    buildRows(visible, byEpgChannel, now, reportProgress = firstBuild)
                }
            }
            // Re-sorts the built rows only — changing the order never rebuilds the guide.
            .combine(combine(settings.channelSort, recentsOnly) { s, r -> s to r }) { rows, (sort, recents) ->
                // Recent keeps its own order (most recently watched first) whatever the sort.
                if (recents) return@combine rows
                when (sort) {
                    AppSettings.ChannelSort.PROVIDER -> rows
                    AppSettings.ChannelSort.NAME -> rows.sortedBy { it.primary.shownName.lowercase() }
                    AppSettings.ChannelSort.NUMBER -> rows.sortedWith(compareBy(nullsLast()) { it.primary.number })
                }
            }
            // Grouping thousands of channels against a 12-hour, all-feeds programme window is heavy
            // enough to freeze the UI for a big provider — a category tap that took minutes. Run the
            // whole pipeline off the main thread so the list just appears when it's ready.
            .flowOn(Dispatchers.Default)
            // Once the first guide is on screen, clear the start-up bar — and never show it for a
            // plain category switch again.
            .onEach { if (!guideBuilt) { guideBuilt = true; StatusBus.set(null) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildRows(
        channels: List<Channel>,
        byEpgChannel: Map<String, List<Programme>>,
        now: Long,
        reportProgress: Boolean = false,
    ): List<Row> {
        // The programme→channel grouping is done once upstream (see [programmeIndex]) and passed
        // in ready-made, rather than regrouping the whole EPG on every category tap. Grouping is
        // kept out of SQL because the programme query cannot take a channel-id list without
        // hitting SQLite's bound-variable cap, and quality-variant folding is pure list work.
        val groups = LinkedHashMap<Any, MutableList<Channel>>()
        for (channel in channels) {
            val key: Any = channel.groupKey.ifEmpty { channel.id }
            groups.getOrPut(key) { mutableListOf() } += channel
        }

        val total = groups.size.coerceAtLeast(1)
        var built = 0
        return groups.values.map { group ->
            group.sortByDescending { it.qualityRank }
            // Only genuinely different qualities are switchable; identical-quality dupes
            // (same stream in two categories) collapse to one, so no false "2 qualities".
            val variants = distinctByQuality(group)
            val primary = variants.first()

            // Walk every variant's guide-id candidates (override → provider → matched)
            // and use the first that actually has programmes. An id that never lights up
            // must not shadow a sibling variant whose id does.
            val list = variants
                .flatMap { it.epgCandidates }
                .firstNotNullOfOrNull { id -> byEpgChannel[id] }
                ?.sortedBy { it.startUtcMillis }
                .orEmpty()

            // Feed the progress bar as rows come together — this is the slow part on a big
            // provider, so it's what the percentage should actually track.
            if (reportProgress) {
                built++
                if (built % 400 == 0 || built == total) {
                    StatusBus.setProgress(built.toFloat() / total)
                }
            }

            Row(
                primary = primary,
                variants = variants,
                now = list.firstOrNull { it.isLiveAt(now) },
                next = list.firstOrNull { it.startUtcMillis > now },
                programmes = list,
            )
        }
    }

    /** A friendly, size-aware line for the load — a small provider gets a quick word, a huge one
     * gets a "bear with me". Used at start-up so the wait always says what it's doing. */
    private fun sizeMessage(count: Int): String = when {
        count <= 0 -> AppText.get(R.string.msg_building_guide_short)
        count < 2000 -> AppText.get(R.string.msg_loading_small, count)
        count < 8000 -> AppText.get(R.string.msg_loading_medium, count)
        else -> AppText.get(R.string.msg_loading_big, count)
    }

    val favourites: StateFlow<List<Channel>> =
        graph.catalogRepository.observeFavouriteChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the catalogue has any channels yet, as a tri-state that stops the home screen
     * spinning forever when a provider fails to load:
     *  - null  → the count query hasn't returned; still checking, keep showing the loader.
     *  - true  → channels are on disk, so an empty [rows] just means the guide is still building.
     *  - false → confirmed empty; if a provider is configured the last sync failed or returned
     *            nothing, so the screen shows a recoverable error instead of an endless spinner.
     */
    val channelsPresent: StateFlow<Boolean?> =
        graph.catalogRepository.observeChannelCount()
            .map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectCategory(id: String?) {
        favouritesOnly.value = false
        recentsOnly.value = false
        selectedCategory.value = id
    }

    fun selectFavourites() {
        favouritesOnly.value = true
        recentsOnly.value = false
        selectedCategory.value = null
    }

    fun selectRecents() {
        recentsOnly.value = true
        favouritesOnly.value = false
        selectedCategory.value = null
    }

    /** Switch the live guide's provider filter; the category resets since categories differ per source. */
    fun selectSource(sourceId: Long?) {
        selectedSource.value = sourceId
        selectedCategory.value = null
        favouritesOnly.value = false
        recentsOnly.value = false
    }

    fun search(text: String) { query.value = text }

    // ---- Dedicated channel search --------------------------------------------------------------
    // Kept separate from the guide's `rows` on purpose: `rows` loads the entire 12-hour EPG
    // window, which is far too heavy to run on every keystroke (that was the ~30s search lag).
    // This path is channel-only — no programme window — debounced, and capped by the DAO's LIMIT.
    private val searchInput = MutableStateFlow("")

    fun setSearchQuery(text: String) { searchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: StateFlow<List<Row>> =
        searchInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList())
                else graph.catalogRepository.searchChannels(q)
                    // No EPG here — group channels into rows with no now/next. Finding and
                    // playing the channel is the job; guide detail would cost the slow query.
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun tick() { nowTick.value = System.currentTimeMillis() }

    // ---- Channel manager (search, then hide/show or favourite) ---------------------------------
    private val managerInput = MutableStateFlow("")

    fun setManagerQuery(text: String) { managerInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val managerResults: StateFlow<List<Row>> =
        managerInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList())
                else graph.catalogRepository.searchChannelsIncludingHidden(q)
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Channel manager: browse by category ---------------------------------------------------
    // Deliberately independent of the guide's `selectedCategory`/`rows`: browsing here never moves
    // what Live TV is showing. Two differences from the guide feed — it can be scoped to a single
    // provider (cardiodoc's "keep sources separate"), and it includes HIDDEN channels so they can
    // be brought back. Always scoped to one category, never the whole catalogue, so the right pane
    // stays a size a remote can scroll (no flat 20k-row list).

    /** Provider filter for the manager. null = every source ("All sources"). */
    val managerSelectedSource = MutableStateFlow<Long?>(null)

    /** The [CategoryGroup.key] whose channels fill the manager's right pane; null = none picked. */
    val managerSelectedCategory = MutableStateFlow<String?>(null)

    /** Providers, so the manager can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The manager's category rail: like [categoryGroups] but scoped to [managerSelectedSource], and
     * NOT filtered by the adult/hidden-category setting — the manager shows every category so a
     * hidden one's channels can still be reached and un-hidden.
     */
    val managerCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.LIVE).distinctUntilChanged(),
            managerSelectedSource,
        ) { raw, sourceId ->
            val scoped = if (sourceId == null) raw else raw.filter { it.sourceId == sourceId }
            foldCategories(scoped)
        }
            // Normalising thousands of category names is real work — keep it off the UI thread,
            // where it used to freeze typing and taps whenever the categories table changed.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Channels in the selected manager category (and source), grouped into [Row]s exactly like the
     * guide — but including hidden channels. Empty until a category is picked. Reuses [buildRows]
     * with no EPG window (the manager needs logo/name/state, not now/next).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val managerRows: StateFlow<List<Row>> =
        combine(managerSelectedSource, managerSelectedCategory, managerCategoryGroups) { source, key, groups ->
            val ids = key?.let { k -> groups.firstOrNull { it.key == k }?.ids }
            source to ids
        }
            .distinctUntilChanged()
            .flatMapLatest { (source, ids) ->
                if (ids.isNullOrEmpty()) flowOf(emptyList())
                else graph.catalogRepository.observeChannelsInIncludingHidden(source, ids)
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Switch the manager's provider filter; the category resets since categories differ per source. */
    fun selectManagerSource(sourceId: Long?) {
        managerSelectedSource.value = sourceId
        managerSelectedCategory.value = null
    }

    fun selectManagerCategory(key: String?) { managerSelectedCategory.value = key }

    /** Hide or show a whole logical channel — every quality variant follows. */
    fun setRowHidden(row: Row, hidden: Boolean) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, hidden) }
        }
    }

    fun toggleFavourite(row: Row) {
        viewModelScope.launch {
            // Favouriting the row favourites the logical channel: every variant follows,
            // so the choice survives switching quality.
            val target = !row.primary.favourite
            row.variants.forEach { graph.catalogRepository.setChannelFavourite(it.id, target) }
        }
    }

    fun hide(row: Row) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, true) }
        }
    }

    private companion object {
        const val GUIDE_LOOKAHEAD_MILLIS = 24 * 60 * 60 * 1000L  // a full day fits the grid
        const val MAX_SQL_CATEGORY_IDS = 900  // under SQLite's 999 bind-variable cap
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val GUIDE_MAX_DAYS = 6  // browse up to a week out, matching typical XMLTV depth
        const val HALF_HOUR_MILLIS = 30 * 60 * 1000L
    }
}

/**
 * Toggle [group] in a saved key set (parental hidden categories), coping with country-less legacy
 * keys: turning off a group that only matched through its legacy key swaps that key for the
 * explicit keys of its sibling countries, so only this one country is revealed.
 */
fun toggleCategoryKey(
    current: Set<String>,
    group: ChannelsViewModel.CategoryGroup,
    all: List<ChannelsViewModel.CategoryGroup>,
    on: Boolean,
): Set<String> {
    if (on) return current + group.key
    var next = current - group.key
    if (group.legacyKey != group.key && group.legacyKey in next) {
        next = next - group.legacyKey +
            all.filter { it.legacyKey == group.legacyKey && it.key != group.key }.map { it.key }
    }
    return next
}
