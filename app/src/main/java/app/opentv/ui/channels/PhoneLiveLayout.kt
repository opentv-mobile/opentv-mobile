/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Sort
import app.opentv.core.AppSettings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.Alignment
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.isPhoneLandscape

/**
 * Live TV on a phone. There's no room for the TV's category rail beside the list, so:
 *  - a filter field narrows the current list by name as you type;
 *  - chips switch between Favourites / All / the picked category;
 *  - the full category list (and provider switch) lives in a bottom sheet with its own filter,
 *    because provider group lists run to hundreds of entries.
 * Tap a channel to play it; long-press for the Watch / Record / Schedule menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLiveLayout(
    rows: List<ChannelsViewModel.Row>,
    sources: List<Source>,
    selectedSource: Long?,
    categories: List<ChannelsViewModel.CategoryGroup>,
    allCategories: List<ChannelsViewModel.CategoryGroup>,
    shownCategories: Set<String>,
    onSetShownCategories: (Set<String>) -> Unit,
    categoryCounts: Map<String, Int>,
    sort: AppSettings.ChannelSort,
    onSort: (AppSettings.ChannelSort) -> Unit,
    selectedCategory: String?,
    favouritesOnly: Boolean,
    recentsOnly: Boolean,
    onSelectSource: (Long?) -> Unit,
    onSelectRecents: () -> Unit,
    onSelectFavourites: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onQuery: (String) -> Unit,
    emptyContent: @Composable () -> Unit,
    layout: AppSettings.PhoneLiveLayout,
    onLayout: (AppSettings.PhoneLiveLayout) -> Unit,
    listContent: @Composable () -> Unit,
    guideContent: @Composable () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSheet by remember { mutableStateOf(false) }
    val categoryLabel = categories.firstOrNull { it.key == selectedCategory }?.label
    val compact = isPhoneLandscape()

    Column(Modifier.fillMaxSize()) {
      PhoneLiveControls(compact) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = layout == AppSettings.PhoneLiveLayout.LIST,
                onClick = { onLayout(AppSettings.PhoneLiveLayout.LIST) },
                label = { Text(stringResource(R.string.phone_guide_list)) })
            FilterChip(selected = layout == AppSettings.PhoneLiveLayout.GUIDE,
                onClick = { onLayout(AppSettings.PhoneLiveLayout.GUIDE) },
                label = { Text(stringResource(R.string.phone_guide_guide)) })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onQuery(it) },
            // Says where the query looks: inside the picked category / favourites, or everywhere.
            placeholder = {
                Text(
                    when {
                        recentsOnly -> stringResource(R.string.phone_search_in, stringResource(R.string.phone_live_recent))
                        favouritesOnly -> stringResource(R.string.phone_search_in, stringResource(R.string.guide_favourites))
                        categoryLabel != null -> stringResource(R.string.phone_search_in, categoryLabel)
                        else -> stringResource(R.string.phone_search_all)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.phone_clear))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.then(if (compact) Modifier.width(220.dp) else Modifier.fillMaxWidth())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !recentsOnly && !favouritesOnly && selectedCategory != null,
                onClick = { showSheet = true },
                label = {
                    Text(
                        if (!recentsOnly && !favouritesOnly && categoryLabel != null) categoryLabel
                        else stringResource(R.string.phone_live_categories),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            FilterChip(
                selected = recentsOnly,
                onClick = onSelectRecents,
                label = { Text(stringResource(R.string.phone_live_recent)) },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
            )
            FilterChip(
                selected = favouritesOnly,
                onClick = onSelectFavourites,
                label = { Text(stringResource(R.string.guide_favourites)) },
            )
            FilterChip(
                selected = !recentsOnly && !favouritesOnly && selectedCategory == null,
                onClick = { onSelectCategory(null) },
                label = { Text(stringResource(R.string.guide_all_channels)) },
            )
            // Order: provider's own → A–Z → by number, one tap each.
            AssistChip(
                onClick = {
                    onSort(
                        when (sort) {
                            AppSettings.ChannelSort.PROVIDER -> AppSettings.ChannelSort.NAME
                            AppSettings.ChannelSort.NAME -> AppSettings.ChannelSort.NUMBER
                            AppSettings.ChannelSort.NUMBER -> AppSettings.ChannelSort.PROVIDER
                        },
                    )
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                label = {
                    Text(
                        stringResource(
                            when (sort) {
                                AppSettings.ChannelSort.PROVIDER -> R.string.phone_sort_provider
                                AppSettings.ChannelSort.NAME -> R.string.phone_sort_name
                                AppSettings.ChannelSort.NUMBER -> R.string.phone_sort_number
                            },
                        ),
                    )
                },
            )
        }
      }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                emptyContent()
            } else if (layout == AppSettings.PhoneLiveLayout.GUIDE) guideContent()
            else listContent()
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CategorySheet(
                sources = sources,
                selectedSource = selectedSource,
                categories = categories,
                allCategories = allCategories,
                shownCategories = shownCategories,
                onSetShownCategories = onSetShownCategories,
                counts = categoryCounts,
                selectedCategory = if (favouritesOnly) null else selectedCategory,
                onSelectSource = onSelectSource,
                onSelectCategory = { onSelectCategory(it); showSheet = false },
            )
        }
    }
}

@Composable
private fun PhoneLiveControls(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) { content() }
    } else Column { content() }
}

@Composable
private fun CategorySheet(
    sources: List<Source>,
    selectedSource: Long?,
    categories: List<ChannelsViewModel.CategoryGroup>,
    allCategories: List<ChannelsViewModel.CategoryGroup>,
    shownCategories: Set<String>,
    onSetShownCategories: (Set<String>) -> Unit,
    counts: Map<String, Int>,
    selectedCategory: String?,
    onSelectSource: (Long?) -> Unit,
    onSelectCategory: (String?) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    // "My categories" editing: a draft of ticked group keys, saved on Done. No filter saved means
    // everything is ticked.
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(emptySet<String>()) }
    val source = if (editing) allCategories else categories
    val shown = remember(source, filter) {
        if (filter.isBlank()) source
        else source.filter { it.label.contains(filter.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (sources.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedSource == null,
                    onClick = { onSelectSource(null) },
                    label = { Text(stringResource(R.string.channels_manager_all_sources)) },
                )
                sources.forEach { source ->
                    FilterChip(
                        selected = selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                        label = { Text(source.name, maxLines = 1) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (editing) stringResource(R.string.phone_categories_count, draft.size, allCategories.size)
                else stringResource(R.string.phone_live_categories),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                TextButton(
                    enabled = draft.isNotEmpty(),
                    onClick = {
                        // Everything ticked is the same as no filter — store it as such.
                        onSetShownCategories(if (draft.size >= allCategories.size) emptySet() else draft)
                        editing = false
                    },
                ) { Text(stringResource(R.string.phone_done)) }
            } else {
                TextButton(onClick = {
                    draft = if (shownCategories.isEmpty()) allCategories.map { it.key }.toSet()
                    else allCategories.filter { it.matches(shownCategories) }.map { it.key }.toSet()
                    editing = true
                }) {
                    Icon(Icons.Filled.Tune, contentDescription = null)
                    Text(" " + stringResource(R.string.phone_my_categories))
                }
            }
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text(stringResource(R.string.phone_live_filter_categories)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (editing) {
            // Bulk picks: filter by e.g. "ES" and tap "Only these" to keep just those groups.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filter.isNotBlank()) {
                    AssistChip(
                        onClick = { draft = shown.map { it.key }.toSet() },
                        label = { Text(stringResource(R.string.phone_only_filtered)) },
                    )
                }
                AssistChip(
                    onClick = { draft = draft + shown.map { it.key } },
                    label = { Text(stringResource(R.string.phone_select_all)) },
                )
                AssistChip(
                    onClick = { draft = draft - shown.map { it.key }.toSet() },
                    label = { Text(stringResource(R.string.phone_select_none)) },
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.key }) { group ->
                    val checked = group.key in draft
                    ListItem(
                        headlineContent = { Text(group.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            draft = if (checked) draft - group.key else draft + group.key
                        },
                    )
                }
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (filter.isBlank()) {
                item(key = "all") {
                    SheetRow(stringResource(R.string.guide_all_channels), selectedCategory == null) {
                        onSelectCategory(null)
                    }
                    HorizontalDivider()
                }
            }
            items(shown, key = { it.key }) { group ->
                SheetRow(group.label, selectedCategory == group.key, counts[group.key]) { onSelectCategory(group.key) }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = count?.let { { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
