/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelEpgCandidatesTest {
    private fun channel(override: String? = null, provider: String? = null, matched: String? = null) = Channel(
        sourceId = 1, streamId = "stream", name = "Channel", categoryId = null, logoUrl = null,
        epgChannelId = provider, matchedEpgId = matched, epgOverrideId = override,
        number = null, streamUrl = "test://stream",
    )

    @Test fun `provider NULL marker leaves valid automatic match available`() {
        assertThat(channel(provider = " nUlL ", matched = "automatic").epgCandidates)
            .containsExactly("automatic")
    }

    @Test fun `manual NULL override remains first and other candidate remains available`() {
        assertThat(channel(override = "NULL", provider = "provider-id", matched = "automatic").epgCandidates)
            .containsExactly("NULL", "provider-id", "automatic").inOrder()
    }

    @Test fun `manual override precedes provider and automatic candidates and duplicates collapse`() {
        assertThat(channel(override = "same", provider = "same", matched = "automatic").epgCandidates)
            .containsExactly("same", "automatic").inOrder()
    }

    @Test fun `missing and blank provider ids leave remaining candidates intact`() {
        assertThat(channel(provider = null, matched = "automatic").epgCandidates).containsExactly("automatic")
        assertThat(channel(provider = "   ", matched = "automatic").epgCandidates).containsExactly("automatic")
    }
}
