/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Programme
import app.opentv.data.parser.XmltvParser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the `<channel>` half of the parser.
 *
 * The programme half was already tested; the channel half was not, and it is the half the
 * EPG matcher depends on entirely — every alias in `epg_channels` comes from here. A guide
 * whose `<channel>` elements are silently dropped still ingests programmes normally, so the
 * failure shows up much later as "the guide is empty" with no error anywhere.
 */
// Robolectric 4.13 has no SDK 35 image yet; the parser touches nothing version-specific.
// A plain Application avoids booting OpenTvApp, which schedules WorkManager on start — the
// parser needs an Android runtime for android.util.Xml and nothing else.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class XmltvChannelParseTest {

    private data class Parsed(
        val aliases: List<Pair<String, String?>>,
        val programmes: List<Programme>,
        val stats: XmltvParser.Stats,
    )

    private suspend fun parse(xml: String): Parsed {
        val aliases = mutableListOf<Pair<String, String?>>()
        val programmes = mutableListOf<Programme>()
        val stats = XmltvParser.parse(
            input = xml.byteInputStream(),
            feedId = 1L,
            onChannelAlias = { id, name -> aliases += id to name },
            onProgramme = { programmes += it },
        )
        return Parsed(aliases, programmes, stats)
    }

    @Test
    fun `reads display names from a plain guide`() = runTest {
        val result = parse(
            """
            <tv>
              <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
              <channel id="itv1.uk"><display-name>ITV1</display-name></channel>
            </tv>
            """.trimIndent(),
        )

        assertThat(result.aliases).containsExactly(
            "bbc1.uk" to "BBC One",
            "itv1.uk" to "ITV1",
        )
    }

    /**
     * The shape epgshare01 and most real rippers actually ship: an `<icon>` sibling, several
     * `<display-name>` variants, and attributes on the lot.
     */
    @Test
    fun `reads channels that carry icons and multiple display names`() = runTest {
        val result = parse(
            """
            <tv>
              <channel id="BBCOne.uk">
                <display-name lang="en">BBC One</display-name>
                <display-name lang="en">101</display-name>
                <icon src="https://example.invalid/bbc1.png"/>
              </channel>
              <channel id="ITV1.uk">
                <display-name lang="en">ITV1</display-name>
                <icon src="https://example.invalid/itv1.png"/>
              </channel>
            </tv>
            """.trimIndent(),
        )

        assertThat(result.aliases).containsExactly(
            "BBCOne.uk" to "BBC One",
            "BBCOne.uk" to "101",
            "ITV1.uk" to "ITV1",
        )
        assertThat(result.stats.channelCount).isEqualTo(2)
    }

    /** Channels and programmes in one document — the normal case end to end. */
    @Test
    fun `reads channels followed by programmes`() = runTest {
        val result = parse(
            """
            <tv>
              <channel id="bbc1.uk">
                <display-name>BBC One</display-name>
                <icon src="https://example.invalid/bbc1.png"/>
              </channel>
              <programme channel="bbc1.uk" start="20260727183000 +0100" stop="20260727190000 +0100">
                <title>The Six O'Clock News</title>
                <desc>The latest news.</desc>
                <category>News</category>
              </programme>
            </tv>
            """.trimIndent(),
        )

        assertThat(result.aliases).containsExactly("bbc1.uk" to "BBC One")
        assertThat(result.programmes).hasSize(1)
        assertThat(result.programmes.single().title).isEqualTo("The Six O'Clock News")
        assertThat(result.programmes.single().description).isEqualTo("The latest news.")
    }

    /** A self-closing channel with no display-name still has to produce an alias. */
    @Test
    fun `falls back to the id when there is no display name`() = runTest {
        val result = parse(
            """
            <tv>
              <channel id="bare.uk"/>
              <channel id="empty.uk"><display-name></display-name></channel>
            </tv>
            """.trimIndent(),
        )

        assertThat(result.aliases).containsExactly("bare.uk" to null, "empty.uk" to null)
    }
}
