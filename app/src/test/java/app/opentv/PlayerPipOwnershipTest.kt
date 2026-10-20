/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.PipState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerPipOwnershipTest {
    @Test fun `old entry disposal and callbacks cannot clear the next player`() {
        val old = Any()
        val next = Any()
        var oldPauses = 0
        try {
            PipState.setActivityVisible(true)
            PipState.attach(old) { oldPauses++ }
            PipState.update(old, true, 1f)
            PipState.attach(next) {}
            PipState.update(next, true, 2f)
            PipState.detach(old)
            PipState.update(old, false, 1f)
            assertThat(oldPauses).isEqualTo(1)
            assertThat(PipState.eligible).isTrue()
            assertThat(PipState.isPlaying).isTrue()
            assertThat(PipState.aspectRatio).isEqualTo(2f)
        } finally {
            PipState.detach(next)
            PipState.setActivityVisible(false)
        }
    }

    @Test fun `closing PiP in background pauses but expanding into visible activity does not`() {
        val token = Any()
        var pauses = 0
        try {
            PipState.setActivityVisible(true)
            PipState.attach(token) { pauses++ }
            PipState.setInPip(true)
            PipState.setActivityVisible(false)
            assertThat(pauses).isEqualTo(0)
            PipState.setInPip(false)
            assertThat(pauses).isEqualTo(1)
            PipState.setActivityVisible(true)
            PipState.setInPip(true)
            PipState.setInPip(false)
            assertThat(pauses).isEqualTo(1)
        } finally {
            PipState.detach(token)
            PipState.setActivityVisible(false)
            PipState.setInPip(false)
        }
    }
}
