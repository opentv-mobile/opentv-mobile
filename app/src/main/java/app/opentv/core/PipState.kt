/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared picture-in-picture state, bridging the Compose player and the host Activity.
 *
 * The Activity owns PiP (only it can call enterPictureInPictureMode and hear the mode change), but
 * the player screen is what knows whether a video is actually on. These flags let the Activity
 * auto-shrink to PiP when the user presses Home mid-programme — and only then, never from a menu.
 */
object PipState {
    /** True only while the full-screen player is on and eligible to shrink into PiP. */
    val eligible: Boolean get() = owner != null

    /** Whether playback is currently going — no point PiP-ing a paused/black screen. */
    var isPlaying: Boolean = false
        private set
    var aspectRatio: Float = 16f / 9f
        private set

    private var owner: Any? = null
    private var pausePlayback: (() -> Unit)? = null
    private var activityVisible = false
    val playbackAllowed: Boolean get() = mayPlayVideo(activityVisible, _inPip.value)
    fun owns(token: Any): Boolean = owner === token

    /** A departing navigation entry must never clear the next player's registration. Main thread. */
    fun attach(token: Any, pause: () -> Unit) {
        pausePlayback?.invoke()
        owner = token
        pausePlayback = pause
        isPlaying = false
        aspectRatio = 16f / 9f
    }

    fun update(token: Any, playing: Boolean, ratio: Float) {
        if (owner !== token) return
        isPlaying = playing
        aspectRatio = ratio
    }

    fun detach(token: Any) {
        if (owner !== token) return
        owner = null
        pausePlayback = null
        isPlaying = false
    }

    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        pauseIfHidden()
    }

    private fun pauseIfHidden() {
        if (!playbackAllowed) pausePlayback?.invoke()
    }

    /** Set by the Activity from onPictureInPictureModeChanged; the player hides its chrome on it. */
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun setInPip(value: Boolean) {
        _inPip.value = value
        pauseIfHidden()
    }
}
