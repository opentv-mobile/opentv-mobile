/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/** Session policy, deliberately independent of screen dimensions (PiP changes those dimensions). */
enum class PlayerOrientation { LANDSCAPE, PORTRAIT, SYSTEM, LOCKED }
enum class PlayerOrientationTarget { SENSOR_LANDSCAPE, SENSOR_PORTRAIT, FULL_USER, LANDSCAPE, PORTRAIT, REVERSE_LANDSCAPE, REVERSE_PORTRAIT }

data class PlayerOrientationSession(
    val active: Boolean = false,
    val orientation: PlayerOrientation = PlayerOrientation.LANDSCAPE,
    /** Surface rotation 0..3, captured when Lock is selected, not on configuration changes. */
    val lockedRotation: Int = 0,
    val naturalLandscape: Boolean = false,
) {
    fun destination(playing: Boolean): PlayerOrientationSession = when {
        playing == active -> this // Channel/episode switches are not new playback sessions.
        else -> PlayerOrientationSession(active = playing)
    }

    fun select(value: PlayerOrientation, rotation: Int, naturalLandscape: Boolean = false) =
        copy(orientation = value, lockedRotation = rotation.coerceIn(0, 3), naturalLandscape = naturalLandscape)

    fun target(television: Boolean, inPip: Boolean, multiWindow: Boolean): PlayerOrientationTarget? {
        if (!active || television || inPip || multiWindow) return null
        return when (orientation) {
            PlayerOrientation.LANDSCAPE -> PlayerOrientationTarget.SENSOR_LANDSCAPE
            PlayerOrientation.PORTRAIT -> PlayerOrientationTarget.SENSOR_PORTRAIT
            PlayerOrientation.SYSTEM -> PlayerOrientationTarget.FULL_USER
            PlayerOrientation.LOCKED -> {
                val landscape = (lockedRotation % 2 == 0) == naturalLandscape
                when {
                    landscape && lockedRotation >= 2 -> PlayerOrientationTarget.REVERSE_LANDSCAPE
                    landscape -> PlayerOrientationTarget.LANDSCAPE
                    lockedRotation >= 2 -> PlayerOrientationTarget.REVERSE_PORTRAIT
                    else -> PlayerOrientationTarget.PORTRAIT
                }
            }
        }
    }
}

/** Android accepts PiP aspect ratios only within [1/2.39, 2.39]. Account for anamorphic pixels. */
fun pipAspectRatio(width: Int, height: Int, pixelRatio: Float = 1f): Float {
    val ratio = width.toDouble() * pixelRatio / height
    return if (width <= 0 || height <= 0 || !ratio.isFinite() || ratio <= 0) 16f / 9f
    else ratio.coerceIn(1.0 / 2.39, 2.39).toFloat()
}

fun shouldEnterPlayerPip(eligible: Boolean, isPlaying: Boolean, supported: Boolean): Boolean =
    eligible && isPlaying && supported

fun mayPlayVideo(activityVisible: Boolean, inPip: Boolean): Boolean = activityVisible || inPip
