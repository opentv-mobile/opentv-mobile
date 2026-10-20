/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import app.opentv.core.PipState
import app.opentv.core.pipAspectRatio

data class PlayerPlaybackStatus(val isPlaying: Boolean, val playWhenReady: Boolean)

/** One listener and one PiP lease per player. Never infer playback from READY or a UI toggle. */
@Composable
fun BindPlayerPlayback(player: Player, onBackground: () -> Unit = {}): PlayerPlaybackStatus {
    var status by remember(player) {
        mutableStateOf(PlayerPlaybackStatus(player.isPlaying, player.playWhenReady))
    }
    val background by rememberUpdatedState(onBackground)
    DisposableEffect(player) {
        val token = Any()
        fun pause() {
            player.pause()
            background()
        }
        PipState.attach(token, ::pause)
        fun publish() {
            // Also catches a delayed prepare/retry that sets playWhenReady after Home/Stop.
            if ((!PipState.owns(token) || !PipState.playbackAllowed) && player.playWhenReady) pause()
            status = PlayerPlaybackStatus(player.isPlaying, player.playWhenReady)
            val size = player.videoSize
            PipState.update(token, player.isPlaying, pipAspectRatio(size.width, size.height, size.pixelWidthHeightRatio))
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
        }
        player.addListener(listener)
        publish()
        onDispose {
            player.removeListener(listener)
            PipState.detach(token)
        }
    }
    return status
}
