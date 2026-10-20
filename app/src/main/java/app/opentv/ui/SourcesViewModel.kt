/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.Application
import android.util.Log
import android.net.Uri
import app.opentv.data.local.canRefreshCatalogue
import app.opentv.data.local.isLocalPlaylist
import app.opentv.data.local.playlistDisplayName
import app.opentv.data.local.LocalPlaylistException
import app.opentv.data.local.LocalPlaylistProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.R
import app.opentv.core.AppText
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    data class UiState(
        val sources: List<Source> = emptyList(),
        /**
         * False until the saved sources have been read from the database once. Distinguishing
         * "no sources yet loaded" from "loaded, and there are none" is what stops a returning
         * user being sent to the setup screen — and asked for their provider again — during the
         * brief moment before the database responds on a cold launch.
         */
        val loaded: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null,
        val testError: String? = null,
        val syncing: Boolean = false,
        val syncMessage: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            graph.sourceRepository.observeAll().collect { sources ->
                _ui.value = _ui.value.copy(sources = sources, loaded = true)
            }
        }
    }

    fun test(draft: Source) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, testError = null)
            val result = graph.sourceRepository.test(draft)
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = result.getOrNull(),
                testError = result.exceptionOrNull()?.message,
            )
        }
    }

    /** Saves, then immediately pulls the catalogue so the user sees channels, not a spinner. */
    fun saveAndSync(draft: Source, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (draft.isLocalPlaylist) {
                graph.sourceRepository.save(draft)
                onDone(true)
                return@launch
            }
            _ui.value = _ui.value.copy(syncing = true, syncMessage = AppText.get(R.string.msg_saving_source))
            val id = graph.sourceRepository.save(draft)
            val saved = graph.sourceRepository.byId(id)
            if (saved == null) {
                _ui.value = _ui.value.copy(syncing = false, syncMessage = AppText.get(R.string.msg_save_source_failed))
                onDone(false)
                return@launch
            }

            _ui.value = _ui.value.copy(syncMessage = AppText.get(R.string.msg_loading_channels))
            val now = System.currentTimeMillis()
            // Load live channels first and get the user watching straight away. Movies, series and
            // the guide are what make a big provider take minutes — they load in the background so
            // "loading channels" is a few seconds, not a twenty-minute blank screen.
            when (val result = graph.catalogRepository.syncLive(saved, now)) {
                is CatalogRepository.SyncResult.Failed -> {
                    _ui.value = _ui.value.copy(syncing = false, syncMessage = result.reason)
                    onDone(false)
                    return@launch
                }
                is CatalogRepository.SyncResult.Success -> {
                    _ui.value = _ui.value.copy(
                        syncing = false,
                        syncMessage = AppText.get(R.string.msg_loaded_channels, result.channelCount),
                    )
                    onDone(true)
                }
            }

            // Background: the guide. Movies/series are pulled on demand from their own tabs, so
            // nothing the user hasn't asked for ever blocks the channels they can already watch.
            runCatching {
                val summary = StatusBus.during(AppText.get(R.string.msg_building_guide)) {
                    graph.epgRepository.syncAll(now)
                }
                _ui.value = _ui.value.copy(
                    syncMessage = when {
                        summary.channelsMatched > 0 ->
                            AppText.get(R.string.msg_guide_ready, summary.channelsMatched, summary.channelsTotal)
                        else ->
                            AppText.get(R.string.msg_channels_no_guide)
                    },
                )
                // Book any new series-link airings the fresh guide just revealed.
                runCatching { graph.recordingEngine.rescanSeriesRules() }
            }.onFailure { Log.w("OpenTV", "Background VOD/guide load failed", it) }
        }
    }

    fun delete(source: Source) {
        viewModelScope.launch { graph.catalogRepository.deleteSource(source.id) }
    }

    fun importLocal(draft: Source, uri: Uri, onDone: (Boolean) -> Unit = {}) {
        if (_ui.value.syncing) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = AppText.get(R.string.local_playlist_importing))
            try {
                val resolver = getApplication<Application>().contentResolver
                val count = withContext(Dispatchers.IO) {
                    graph.catalogRepository.importLocalPlaylist(draft, resolver.playlistDisplayName(uri)) {
                        checkNotNull(resolver.openInputStream(uri)) { AppText.get(R.string.local_playlist_error) }
                    }
                }
                _ui.value = _ui.value.copy(syncMessage = AppText.get(R.string.msg_loaded_channels, count))
                onDone(true)
                // A selected file never supplies an implicit XMLTV URL; explicit EPG stays remote.
                try {
                    graph.epgRepository.syncAll(System.currentTimeMillis())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("OpenTV", "Guide refresh after local import failed", failure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = when ((failure as? LocalPlaylistException)?.problem) {
                    LocalPlaylistProblem.TOO_LARGE -> R.string.local_playlist_too_large
                    LocalPlaylistProblem.HLS, LocalPlaylistProblem.REMOTE_ONLY -> R.string.local_playlist_remote_only
                    else -> R.string.local_playlist_error
                }
                _ui.value = _ui.value.copy(syncMessage = AppText.get(message))
                onDone(false)
            } finally {
                _ui.value = _ui.value.copy(syncing = false)
            }
        }
    }

    /**
     * Change an Xtream source's live-stream container. The repository rewrites the source's live
     * channel URLs in place, so the switch takes effect without a re-sync; the observed source list
     * then re-emits with the new [Source.liveFormat]. A no-op for M3U sources and unchanged values.
     */
    fun setLiveFormat(source: Source, format: LiveStreamFormat) {
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return
        viewModelScope.launch { graph.catalogRepository.setLiveFormat(source.id, format) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = AppText.get(R.string.msg_refreshing))
            val now = System.currentTimeMillis()
            var channels = 0
            var problems = 0
            for (source in graph.sourceRepository.enabled()) {
                if (!source.canRefreshCatalogue()) continue
                when (val result = graph.catalogRepository.sync(source, now)) {
                    is CatalogRepository.SyncResult.Success -> channels += result.channelCount
                    is CatalogRepository.SyncResult.Failed -> problems++
                }
            }
            val summary = graph.epgRepository.syncAll(now, force = true)
            problems += summary.feedsFailed
            runCatching { graph.recordingEngine.rescanSeriesRules() }
            _ui.value = _ui.value.copy(
                syncing = false,
                syncMessage = if (problems == 0) {
                    AppText.get(R.string.msg_refreshed_ok, channels, summary.channelsMatched, summary.channelsTotal)
                } else {
                    AppText.get(R.string.msg_refreshed_problems, channels, problems)
                },
            )
        }
    }

    fun blankDraft() = Source(name = "", kind = SourceKind.XTREAM, url = "")
}
