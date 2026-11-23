/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.EpgMatcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The matcher's contract: prefer no match to a wrong match. A missing guide entry is an
 * annoyance with a manual override; a wrong one convinces the user the whole guide lies.
 */
class EpgMatcherTest {

    /** Shorthand: build an index from (epgId, displayName) pairs. */
    private fun index(vararg aliases: Pair<String, String>) =
        EpgMatcher.buildIndex(aliases.toList())

    private fun keyOf(providerName: String) =
        ChannelNameNormalizer.normalize(providerName).groupKey

    @Test
    fun `provider channel matches guide channel by normalised name`() {
        val idx = index("bbc1.uk" to "BBC One", "itv1.uk" to "ITV1")

        assertThat(idx.match(keyOf("UK| BBC ONE FHD"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK - ITV 1 HD"))).isEqualTo("itv1.uk")
    }

    @Test
    fun `the raw guide id is matchable too`() {
        // Providers that do fill in epg_channel_id often use the id form directly.
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(ChannelNameNormalizer.groupKeyOf("bbc1.uk"))).isEqualTo("bbc1.uk")
    }

    @Test
    fun `unique prefix matches are accepted`() {
        val idx = index("bbc1london.uk" to "BBC One London")

        // Provider says just "BBC One"; the only candidate is the London region — take it.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isEqualTo("bbc1london.uk")
    }

    @Test
    fun `ambiguous prefix matches are refused`() {
        val idx = index(
            "bbc1london.uk" to "BBC One London",
            "bbc1wales.uk" to "BBC One Wales",
        )

        // Two regions fit and nothing exact does. Guessing here shows the wrong regional
        // news against the channel — refuse, and let the manual override decide.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isNull()
    }

    @Test
    fun `colliding display names disable exact matching for that key`() {
        val idx = index(
            "cnn.us" to "CNN",
            "cnn.int" to "CNN",
        )

        assertThat(idx.match(keyOf("CNN"))).isNull()
    }

    @Test
    fun `exact collisions resolve when verified as equivalent`() {
        val rows = listOf("A" to 1L, "B" to 2L, "C" to 3L)
        val canonical = mapOf("cnn.us" to "cnn.int", "cnn.int" to "cnn.int")
        val idx = EpgMatcher.buildIndex(listOf("cnn.us" to "CNN", "cnn.int" to "CNN"), canonical)
        assertThat(idx.match(keyOf("CNN"))).isEqualTo("cnn.int")
    }

    @Test
    fun `unverified exact collisions remain ambiguous`() {
        val idx = index("cnn.us" to "CNN", "cnn.int" to "CNN")
        assertThat(idx.match(keyOf("CNN"))).isNull()
    }

    @Test
    fun `equivalent aliases do not relax ambiguous regional prefixes`() {
        val idx = EpgMatcher.buildIndex(
            listOf("bbc.london" to "BBC One London", "bbc.wales" to "BBC One Wales"),
            mapOf("bbc.london" to "bbc.london", "bbc.wales" to "bbc.london"),
        )
        assertThat(idx.match(keyOf("BBC One"))).isNull()
    }

    @Test
    fun `exact numbered channels remain distinct`() {
        val idx = EpgMatcher.buildIndex(
            listOf("champ2" to "Champions 2", "champ10" to "Champions 10"),
            mapOf("champ2" to "champ2", "champ10" to "champ2"),
        )
        assertThat(idx.match(keyOf("Champions 2"))).isEqualTo("champ2")
        assertThat(idx.match(keyOf("Champions 10"))).isEqualTo("champ10")
    }

    @Test
    fun `exact collision chooses sole populated id`() {
        val idx = EpgMatcher.buildIndex(
            listOf("channel.empty" to "Example", "channel.live" to "Example"),
            emptyMap(), setOf("channel.live"),
        )
        assertThat(idx.match(keyOf("Example"))).isEqualTo("channel.live")
    }

    @Test
    fun `equivalent populated ids resolve despite an empty third id`() {
        val ids = listOf("feed.a" to "Example", "feed.b" to "Example", "feed.empty" to "Example")
        val idx = EpgMatcher.buildIndex(ids, mapOf("feed.a" to "feed.a", "feed.b" to "feed.a"), setOf("feed.a", "feed.b"))
        assertThat(idx.match(keyOf("Example"))).isEqualTo("feed.a")
    }

    @Test
    fun `all empty exact collision stays ambiguous`() {
        val idx = EpgMatcher.buildIndex(listOf("empty.a" to "Example", "empty.b" to "Example"), emptyMap(), emptySet())
        assertThat(idx.match(keyOf("Example"))).isNull()
    }

    @Test
    fun `populated differing ids remain ambiguous with empty candidate`() {
        val idx = EpgMatcher.buildIndex(
            listOf("live.a" to "Example", "live.b" to "Example", "empty" to "Example"),
            emptyMap(), setOf("live.a", "live.b"),
        )
        assertThat(idx.match(keyOf("Example"))).isNull()
    }

    @Test
    fun `unknown channels match nothing`() {
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(keyOf("UK| SOME SHOP CHANNEL"))).isNull()
    }

    @Test
    fun `keys too short to mean anything never match`() {
        val idx = index("e.uk" to "E")

        assertThat(idx.match("e")).isNull()
        // But two characters is a real channel name in Britain.
        val e4 = index("e4.uk" to "E4")
        assertThat(e4.match(keyOf("UK| E4 HD"))).isEqualTo("e4.uk")
    }

    @Test
    fun `end to end - the exact names from the reporting provider`() {
        // The names that started all this, verbatim from the screen.
        val idx = index(
            "bbc1.uk" to "BBC One",
            "bbc2.uk" to "BBC Two",
            "channel4.uk" to "Channel 4",
            "skyatlantic.uk" to "Sky Atlantic",
        )

        assertThat(idx.match(keyOf("UK| BBC ONE HD/RAW"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK| BBC TWO FHD"))).isEqualTo("bbc2.uk")
        assertThat(idx.match(keyOf("UK| CHANNEL 4 hevc"))).isEqualTo("channel4.uk")
        assertThat(idx.match(keyOf("UK| SKY ATLANTIC ᴴᴰ"))).isEqualTo("skyatlantic.uk")
    }
}
