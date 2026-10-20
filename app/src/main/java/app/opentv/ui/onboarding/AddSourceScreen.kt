/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.opentv.data.local.isLocalPlaylist
import app.opentv.data.local.safePlaylistDisplayName
import app.opentv.data.local.playlistDisplayName
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.isRunningOnTelevision
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.channels.readClipboardText

/**
 * First-run setup.
 *
 * Two things this screen does that most IPTV players do not:
 *
 * 1. **Test before saving.** The user finds out the password is wrong here, with a sentence
 *    explaining what to change, rather than staring at an empty channel list later.
 * 2. **Says where the credentials go.** People are handing over their provider login. They
 *    are entitled to know it stays on the device, and to be told so without having to go
 *    looking for a privacy policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceScreen(
    viewModel: SourcesViewModel,
    onFinished: () -> Unit,
    editingSourceId: Long? = null,
) {
    val context = LocalContext.current
    // The QR "set it up from your phone" flow only makes sense on a TV — it serves a setup page
    // FROM this device for a phone to fill in. On a phone that same screen shows a QR meant to be
    // scanned BY a phone, which is nonsensical and traps the user — so a non-TV device defaults
    // straight to the direct entry form. On a TV the QR is still offered first, because typing a
    // server address and password with a d-pad is the worst moment in every app of this kind.
    val isTelevision = remember(context) { isRunningOnTelevision(context) }
    // Editing an existing provider always uses the direct form (the fields are pre-filled, and there
    // is nothing to pair); only a fresh add on a TV offers the phone-pairing QR first.
    var usePhone by remember { mutableStateOf(isTelevision && editingSourceId == null) }

    if (usePhone) {
        PhonePairingScreen(
            // Even on a failed sync, leave the pairing screen: the source is already saved, so the
            // home screen shows either the guide (success) or a recoverable error (failure) rather
            // than stranding the user on an endless "Got it — connecting…" spinner.
            onReceived = { draft -> viewModel.saveAndSync(draft) { onFinished() } },
            onCancel = { usePhone = false },
        )
        return
    }

    val ui by viewModel.ui.collectAsState()

    // When editing, find the source being edited and pre-fill every field from it. Keyed on its id
    // so the fields seed once the list has loaded; a fresh add leaves everything blank.
    val existing = remember(editingSourceId, ui.sources) {
        editingSourceId?.let { id -> ui.sources.firstOrNull { it.id == id } }
    }

    var kind by remember(existing?.id) { mutableStateOf(existing?.kind ?: SourceKind.XTREAM) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
    var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "") }
    var password by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var mac by remember(existing?.id) { mutableStateOf(existing?.macAddress ?: "") }
    var epgUrl by remember(existing?.id) { mutableStateOf(existing?.epgUrl ?: "") }
    var showAdvanced by remember { mutableStateOf(false) }
    var userAgent by remember(existing?.id) { mutableStateOf(existing?.userAgent ?: Source.DEFAULT_USER_AGENT) }
    var localMode by remember(existing?.id) { mutableStateOf(existing?.isLocalPlaylist == true) }
    var selectedFile by remember(existing?.id) { mutableStateOf<Uri?>(null) }
    var selectedName by remember(existing?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFile) {
        selectedName = selectedFile?.let { uri ->
            withContext(Dispatchers.IO) {
                safePlaylistDisplayName(runCatching { context.contentResolver.playlistDisplayName(uri) }.getOrNull())
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectedFile = uri
    }
    val isLocal = kind == SourceKind.M3U && localMode

    // Editing copies onto the existing row, so id, enabled state, live format and last-sync stamp
    // are preserved; a fresh add starts from a blank Source (id 0, so save() inserts).
    fun draft(): Source = (existing ?: Source(name = "", kind = kind, url = "")).copy(
        name = name.ifBlank {
            if (kind == SourceKind.M3U) context.getString(R.string.onboarding_default_playlist_name)
            else context.getString(R.string.onboarding_default_provider_name)
        },
        kind = kind,
        url = if (isLocal) "" else url,
        username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        macAddress = mac.takeIf { it.isNotBlank() },
        epgUrl = epgUrl.takeIf { it.isNotBlank() },
        userAgent = userAgent.ifBlank { Source.DEFAULT_USER_AGENT },
    )

    val canSubmit = if (isLocal) selectedFile != null || existing?.isLocalPlaylist == true else url.isNotBlank() && when (kind) {
        SourceKind.XTREAM -> username.isNotBlank() && password.isNotBlank()
        SourceKind.M3U -> true
        SourceKind.STALKER -> mac.isNotBlank()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Text(
                stringResource(
                    if (existing != null) R.string.settings_edit_provider_title
                    else R.string.onboarding_add_provider_title,
                ),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_add_provider_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            // Material segments expose selected/radio semantics and 48dp touch targets. Omit the
            // check icon so all three short labels fit equally on a portrait phone.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val kinds = listOf(SourceKind.XTREAM, SourceKind.M3U, SourceKind.STALKER)
                kinds.forEachIndexed { index, provider ->
                    SegmentedButton(
                        selected = kind == provider,
                        enabled = existing?.isLocalPlaylist != true && !ui.syncing,
                        onClick = { kind = provider },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                        modifier = Modifier.weight(1f),
                        icon = {},
                        label = {
                            Text(
                                stringResource(when (provider) {
                                    SourceKind.XTREAM -> R.string.provider_picker_xtream
                                    SourceKind.M3U -> R.string.provider_picker_m3u
                                    SourceKind.STALKER -> R.string.provider_picker_stalker
                                }),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.epg_name_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            if (kind == SourceKind.M3U) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { localMode = false }, enabled = existing?.isLocalPlaylist != true && !ui.syncing) {
                        Text(stringResource(R.string.local_playlist_url))
                    }
                    OutlinedButton(onClick = { localMode = true }, enabled = existing == null && !ui.syncing) {
                        Text(stringResource(R.string.local_playlist_file))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (isLocal) {
                Text(stringResource(R.string.local_playlist_help))
                existing?.localPlaylistDisplayName?.let { Text(safePlaylistDisplayName(it)) }
                if (selectedFile != null) Text(selectedName ?: stringResource(R.string.local_playlist_selected))
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !ui.syncing) {
                    Text(stringResource(if (existing == null) R.string.local_playlist_choose else R.string.local_playlist_reimport))
                }
            } else OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = {
                    Text(
                        when (kind) {
                            SourceKind.XTREAM -> stringResource(R.string.onboarding_server_address)
                            SourceKind.M3U -> stringResource(R.string.onboarding_playlist_url)
                            SourceKind.STALKER -> stringResource(R.string.onboarding_portal_url)
                        },
                    )
                },
                supportingText = {
                    Text(
                        when (kind) {
                            SourceKind.XTREAM -> stringResource(R.string.onboarding_server_help)
                            SourceKind.M3U -> stringResource(R.string.onboarding_playlist_help)
                            SourceKind.STALKER -> stringResource(R.string.onboarding_portal_help)
                        },
                    )
                },
                trailingIcon = { PasteButton { url += it } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            if (kind == SourceKind.XTREAM) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.recset_field_username)) },
                    trailingIcon = { PasteButton { username += it } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.recset_field_password)) },
                    trailingIcon = { PasteButton { password += it } },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (kind == SourceKind.STALKER) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    label = { Text(stringResource(R.string.onboarding_mac_address)) },
                    supportingText = { Text(stringResource(R.string.onboarding_mac_help)) },
                    trailingIcon = { PasteButton { mac += it } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) stringResource(R.string.onboarding_hide_advanced) else stringResource(R.string.onboarding_advanced))
            }

            if (showAdvanced) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text(stringResource(R.string.onboarding_guide_url_optional)) },
                    supportingText = {
                        Text(stringResource(R.string.onboarding_guide_blank_help))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = { Text("User-Agent") },
                    supportingText = {
                        Text(stringResource(R.string.onboarding_user_agent_help))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))

            ui.testResult?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
            }
            ui.testError?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        message,
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            ui.syncMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.test(draft()) },
                    enabled = !isLocal && canSubmit && !ui.testing && !ui.syncing,
                ) {
                    if (ui.testing) {
                        CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.recset_test_connection))
                    }
                }
                Button(
                    onClick = {
                        val uri = selectedFile
                        if (isLocal && uri != null) viewModel.importLocal(draft(), uri) { ok -> if (ok) onFinished() }
                        else viewModel.saveAndSync(draft()) { ok -> if (ok) onFinished() }
                    },
                    enabled = canSubmit && !ui.testing && !ui.syncing,
                ) {
                    Text(stringResource(when {
                        ui.syncing -> R.string.onboarding_working
                        isLocal && selectedFile != null -> if (existing == null) R.string.local_playlist_import else R.string.local_playlist_reimport
                        isLocal -> R.string.common_save
                        else -> R.string.onboarding_save_load
                    }))
                }
            }

            // "Set up from your phone" (the QR pairing flow) is offered only on a TV — see the
            // isTelevision note above. On a phone it would just show a QR to nowhere.
            if (isTelevision) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { usePhone = true }) {
                    Text(stringResource(R.string.onboarding_use_phone))
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.onboarding_privacy_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A trailing "paste" affordance for a provider field: reads the system clipboard and appends it
 * via [onPaste]. A long Xtream URL or username is miserable to type on a d-pad, so this is exactly
 * what the reporter asked for. Reads the same clipboard as the on-screen keyboard's Paste key; an
 * empty clipboard just shows a brief message rather than doing nothing silently.
 */
@Composable
private fun PasteButton(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val pasted = readClipboardText(context)
            if (pasted != null) onPaste(pasted)
            else Toast.makeText(context, context.getString(R.string.kbd_clipboard_empty), Toast.LENGTH_SHORT).show()
        },
    ) {
        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.kbd_paste))
    }
}
