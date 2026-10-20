package app.opentv.widget

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.Routes
import app.opentv.core.PlayRequests
import app.opentv.core.ServiceLocator
import app.opentv.player.PlaybackQueue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** ID-only external handoff, kept separate from browsing/player code. */
@Composable
fun ExternalChannelRequests(start: String, navigate: (Long) -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val repository = remember { RecentChannelsRepository(graph.database.channels()) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Long?>(null) }
    var recordingTitle by remember { mutableStateOf("") }
    var requestGeneration by remember { mutableStateOf(0L) }
    // Capture before consuming the intent; even an invalid explicit request beats resume-last.
    val explicitLaunch = remember { PlayRequests.channelId.value != null }

    fun open(channel: RecentChannelCandidate) {
        // External launches have no browsing context. Never inherit a stale/now-protected zap list.
        PlaybackQueue.items = listOf(PlaybackQueue.Item(channel.id, channel.name, channel.logoUrl))
        navigate(channel.id)
    }

    LaunchedEffect(Unit) {
        if (!explicitLaunch && PlayRequests.channelId.value == null && start == Routes.HOME &&
            graph.settings.resumeLastChannel.value) {
            val channel = repository.playable(graph.settings.lastChannelId, graph.settings)
            if (channel != null && PlayRequests.channelId.value == null) {
                // Let the same recording/privacy guard handle resume-last too.
                PlayRequests.request(channel.id)
            }
        }
        // Start collecting only after the optional boot lookup: an arriving explicit request
        // remains queued and cannot be consumed then accidentally replaced by resume-last.
        PlayRequests.channelId.collectLatest { id ->
            if (id == null) return@collectLatest
            requestGeneration++
            pending = null
            val channel = repository.playable(id, graph.settings)
            val recordings = if (channel != null) graph.recordingRepository.active() else emptyList()
            // Do not consume/navigate an older lookup after a newer tap.
            if (PlayRequests.channelId.value != id) return@collectLatest
            PlayRequests.consume()
            if (channel != null) {
                if (recordings.isEmpty()) open(channel)
                else {
                    recordingTitle = recordings.first().title
                    pending = id
                }
            }
        }
    }

    pending?.let { id ->
        val generation = requestGeneration
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.rec_live_warn_title)) },
            text = { Text(stringResource(R.string.rec_live_warn_body, recordingTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // A source/category may have changed while the dialog was visible.
                        val channel = repository.playable(id, graph.settings)
                        if (pending != id || requestGeneration != generation || PlayRequests.channelId.value != null) return@launch
                        pending = null
                        if (channel != null) {
                            val recordings = graph.recordingRepository.active()
                            if (requestGeneration != generation || PlayRequests.channelId.value != null) return@launch
                            recordings.forEach { graph.recordingEngine.stop(it.id) }
                            // Recheck freshness after the suspending recording read.
                            if (requestGeneration == generation && PlayRequests.channelId.value == null) open(channel)
                        }
                    }
                }) { Text(stringResource(R.string.rec_live_warn_watch)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.rec_live_warn_keep)) }
            },
        )
    }
}
