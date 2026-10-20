/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.PlayerOrientation
import app.opentv.core.PlayerOrientationSession
import app.opentv.core.PlayerOrientationTarget
import app.opentv.core.mayPlayVideo
import app.opentv.core.pipAspectRatio
import app.opentv.core.shouldEnterPlayerPip
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerDisplayPolicyTest {
    @Test fun `new sessions start landscape but episode and channel switches retain selection`() {
        val first = PlayerOrientationSession().destination(true)
        assertThat(first.target(false, false, false)).isEqualTo(PlayerOrientationTarget.SENSOR_LANDSCAPE)
        val selected = first.select(PlayerOrientation.SYSTEM, 1)
        assertThat(selected.destination(true)).isEqualTo(selected)
        assertThat(selected.destination(false).destination(true)).isEqualTo(first)
    }

    @Test fun `unlock follows system user policy rather than unrestricted sensors`() {
        val session = PlayerOrientationSession(active = true).select(PlayerOrientation.SYSTEM, 0)
        assertThat(session.target(false, false, false)).isEqualTo(PlayerOrientationTarget.FULL_USER)
    }

    @Test fun `PiP multiwindow and TV never request orientation or mutate session`() {
        val session = PlayerOrientationSession(active = true).select(PlayerOrientation.LOCKED, 3)
        assertThat(session.target(false, true, false)).isNull()
        assertThat(session.target(false, false, true)).isNull()
        assertThat(session.target(true, false, false)).isNull()
        assertThat(session.target(false, false, false)).isEqualTo(PlayerOrientationTarget.REVERSE_LANDSCAPE)
        assertThat(session.lockedRotation).isEqualTo(3)
    }

    @Test fun `lock captures physical orientation including reverse and naturally landscape tablets`() {
        val phone = listOf(PlayerOrientationTarget.PORTRAIT, PlayerOrientationTarget.LANDSCAPE,
            PlayerOrientationTarget.REVERSE_PORTRAIT, PlayerOrientationTarget.REVERSE_LANDSCAPE)
        val tablet = listOf(PlayerOrientationTarget.LANDSCAPE, PlayerOrientationTarget.PORTRAIT,
            PlayerOrientationTarget.REVERSE_LANDSCAPE, PlayerOrientationTarget.REVERSE_PORTRAIT)
        for (rotation in 0..3) {
            val session = PlayerOrientationSession(active = true)
            assertThat(session.select(PlayerOrientation.LOCKED, rotation).target(false, false, false)).isEqualTo(phone[rotation])
            assertThat(session.select(PlayerOrientation.LOCKED, rotation, true).target(false, false, false)).isEqualTo(tablet[rotation])
        }
    }

    @Test fun `explicit portrait does not become a new session on configuration change`() {
        val session = PlayerOrientationSession(active = true).select(PlayerOrientation.PORTRAIT, 0)
        assertThat(session.copy().destination(true).target(false, false, false))
            .isEqualTo(PlayerOrientationTarget.SENSOR_PORTRAIT)
    }

    @Test fun `PiP requires a visible player actually playing for Home and explicit button alike`() {
        for (eligible in listOf(false, true)) for (playing in listOf(false, true)) for (supported in listOf(false, true)) {
            assertThat(shouldEnterPlayerPip(eligible, playing, supported)).isEqualTo(eligible && playing && supported)
        }
    }

    @Test fun `PiP ratio handles anamorphic video clamps extreme dimensions and rejects invalid sizes`() {
        assertThat(pipAspectRatio(720, 576, 64f / 45f)).isWithin(0.001f).of(16f / 9f)
        assertThat(pipAspectRatio(10000, 1)).isWithin(0.0001f).of(2.39f)
        assertThat(pipAspectRatio(1, 10000)).isWithin(0.0001f).of(1f / 2.39f)
        for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            assertThat(pipAspectRatio(1920, 1080, ratio)).isEqualTo(16f / 9f)
        }
        assertThat(pipAspectRatio(0, 0)).isEqualTo(16f / 9f)
    }

    @Test fun `background playback only survives while its PiP window is visible`() {
        assertThat(mayPlayVideo(true, false)).isTrue()
        assertThat(mayPlayVideo(false, true)).isTrue()
        assertThat(mayPlayVideo(false, false)).isFalse()
    }
}
