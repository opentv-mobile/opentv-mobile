/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.opentv.data.local.isLocalPlaylist
import app.opentv.data.local.safePlaylistDisplayName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.SourcesViewModel

/**
 * The provider list: what's connected, with the plumbing to add another or remove one. Removing
 * a source deletes its channels and guide with it — the confirm step is here because that is a
 * lot of data to lose to a stray click on a remote.
 */
@Composable
fun ProvidersScreen(
    onAddSource: () -> Unit,
    onEditSource: (Source) -> Unit,
    onBack: () -> Unit,
    viewModel: SourcesViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    var pendingRemove by remember { mutableStateOf<Source?>(null) }
    var pendingImport by remember { mutableStateOf<Source?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = pendingImport
        pendingImport = null
        if (source != null && uri != null) viewModel.importLocal(source, uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .screenPadding(),
    ) {
        ScreenHeader(stringResource(R.string.settings_providers_title), onBack = onBack) {
            OutlinedButton(onClick = onAddSource) { Text(stringResource(R.string.providers_add)) }
        }

        Spacer(Modifier.height(16.dp))
        ui.syncMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (ui.sources.isEmpty()) {
            Text(
                stringResource(R.string.providers_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 760.dp),
        ) {
            items(ui.sources, key = { it.id }) { source ->
                ProviderRow(
                    source = source,
                    confirming = pendingRemove?.id == source.id,
                    onEdit = { onEditSource(source) },
                    importing = ui.syncing,
                    onReimport = { pendingImport = source; filePicker.launch(arrayOf("*/*")) },
                    onAskRemove = { pendingRemove = source },
                    onCancelRemove = { pendingRemove = null },
                    onConfirmRemove = {
                        viewModel.delete(source)
                        pendingRemove = null
                    },
                    onSetLiveFormat = { viewModel.setLiveFormat(source, it) },
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    source: Source,
    confirming: Boolean,
    onEdit: () -> Unit,
    importing: Boolean,
    onReimport: () -> Unit,
    onAskRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onSetLiveFormat: (LiveStreamFormat) -> Unit,
) {
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (source.isLocalPlaylist) stringResource(R.string.local_playlist_file) + " · " +
                            safePlaylistDisplayName(source.localPlaylistDisplayName)
                        else "${source.kind.name} · ${hostOf(source.url)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (confirming) {
                    Text(
                        stringResource(R.string.providers_remove_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onConfirmRemove) { Text(stringResource(R.string.providers_yes_remove)) }
                    TextButton(onClick = onCancelRemove) { Text(stringResource(R.string.common_cancel)) }
                } else {
                    if (source.isLocalPlaylist) {
                        TextButton(onClick = onReimport, enabled = !importing) { Text(stringResource(R.string.local_playlist_reimport)) }
                    }
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                    TextButton(onClick = onAskRemove) { Text(stringResource(R.string.common_remove)) }
                }
            }

            // Stream format is an Xtream-panel concept — M3U sources carry their own URLs, so the
            // choice is meaningless there and hidden.
            if (source.kind == SourceKind.XTREAM) {
                Spacer(Modifier.height(14.dp))
                StreamFormatSelector(selected = source.liveFormat, onSelect = onSetLiveFormat)
            }
        }
    }
}

/**
 * The per-source HLS / MPEG-TS picker. A compact two-option segmented control (the selected
 * container is a filled button, the other outlined) with a one-line hint on when to reach for it.
 */
@Composable
private fun StreamFormatSelector(
    selected: LiveStreamFormat,
    onSelect: (LiveStreamFormat) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.provider_stream_format),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatSegment(
                label = stringResource(R.string.provider_stream_format_hls),
                selected = selected == LiveStreamFormat.HLS,
                onClick = { onSelect(LiveStreamFormat.HLS) },
            )
            FormatSegment(
                label = stringResource(R.string.provider_stream_format_ts),
                selected = selected == LiveStreamFormat.MPEG_TS,
                onClick = { onSelect(LiveStreamFormat.MPEG_TS) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.provider_stream_format_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One option of the stream-format control: filled when chosen, outlined otherwise. */
@Composable
private fun FormatSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/** Host only — the full URL carries the login and has no place on a settings list. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
