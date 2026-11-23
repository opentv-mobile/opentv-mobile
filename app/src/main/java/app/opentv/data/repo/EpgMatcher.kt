/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.parser.ChannelNameNormalizer

/**
 * Joins provider channels to guide channels when nobody gave us a join key.
 *
 * The provider says `UK| BBC ONE FHD`. The guide says `BBC One` (id `bbc1.uk`). The only
 * bridge between them is the name, so both sides are pushed through
 * [ChannelNameNormalizer.groupKeyOf] and matched on the result.
 *
 * ## The design rule: prefer no match to a wrong match
 *
 * A missing guide entry reads as "provider doesn't do EPG" and can be fixed with a manual
 * override. A *wrong* one shows EastEnders against a sports channel, and the user reasonably
 * concludes the whole guide is broken. So the fuzzy tier only accepts a prefix match when it
 * is **unambiguous** — exactly one guide channel fits. `bbcone` will claim `BBC One` when
 * that is the only candidate, but if the guide carries `BBC One London` *and* `BBC One
 * Wales` and nothing plain, we refuse to guess between regions.
 */
object EpgMatcher {

    /** One guide channel, pre-normalised. */
    data class Alias(
        val epgId: String,
        /** Normalised display-name or id: `bbcone`, `bbc1uk`. */
        val key: String,
    )

    class Index internal constructor(
        private val exact: Map<String, String>,
        private val sortedKeys: List<Alias>,
    ) {
        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            exact[groupKey]?.let { return it }

            // Prefix tier: the provider name extends the guide name or vice versa
            // ("skysportsmainevent" vs "skysportsmainevt"; "bbcone" vs "bbconelondon").
            // Only accepted when every candidate agrees on a single epg id.
            var found: String? = null
            for (alias in sortedKeys) {
                val hit = (alias.key.length >= MIN_KEY_LENGTH) &&
                    (alias.key.startsWith(groupKey) || groupKey.startsWith(alias.key))
                if (!hit) continue
                if (found == null) {
                    found = alias.epgId
                } else if (found != alias.epgId) {
                    return null // ambiguous — refuse to guess
                }
            }
            return found
        }
    }

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     */
    fun buildIndex(aliases: Iterable<Pair<String, String>>): Index {
        return buildIndex(aliases, emptyMap())
    }

    /** Verified exact-key equivalence groups, represented as id -> deterministic canonical id. */
    fun buildIndex(
        aliases: Iterable<Pair<String, String>>,
        equivalentCanonicalIds: Map<String, String>,
        idsWithProgrammes: Set<String>? = null,
    ): Index {
        val exact = HashMap<String, String>()
        val exactIds = HashMap<String, MutableSet<String>>()
        val all = ArrayList<Alias>()

        for ((epgId, name) in aliases) {
            for (candidate in listOf(name, epgId)) {
                // Full normalise, not bare groupKeyOf: free guides append 'HD' to display
                // names ('BBC One East HD'), which would otherwise never key-match a
                // provider's 'BBC ONE EAST'. normalize() strips the quality token.
                val key = ChannelNameNormalizer.normalize(candidate).groupKey
                if (key.length < MIN_KEY_LENGTH) continue
                all += Alias(epgId, key)
                exactIds.getOrPut(key) { linkedSetOf() }.add(epgId)
            }
        }
        for ((key, ids) in exactIds) {
            if (ids.size == 1) exact[key] = ids.first()
            else {
                val populatedIds = idsWithProgrammes?.let { available -> ids.filter { it in available }.toSet() }
                if (populatedIds?.size == 1) {
                    exact[key] = populatedIds.first()
                } else {
                    val candidates = populatedIds ?: ids
                    val canonicals = candidates.map { equivalentCanonicalIds[it] ?: it }.toSet()
                    if (candidates.isNotEmpty() && canonicals.size == 1 && candidates.all { it in equivalentCanonicalIds }) {
                        exact[key] = canonicals.first()
                    }
                }
            }
        }

        return Index(exact, all)
    }

    /** IDs grouped by normalized exact keys; prefixes are deliberately excluded. */
    fun exactCollisionGroups(aliases: Iterable<Pair<String, String>>): List<Set<String>> {
        val idsByKey = HashMap<String, MutableSet<String>>()
        for ((id, name) in aliases) for (candidate in listOf(name, id)) {
            val key = ChannelNameNormalizer.normalize(candidate).groupKey
            if (key.length >= MIN_KEY_LENGTH) idsByKey.getOrPut(key) { linkedSetOf() }.add(id)
        }
        return idsByKey.values.filter { it.size > 1 }.map { it.toSet() }
    }

    /**
     * Below this, keys are too generic to mean anything — `e4` is real, but `tv`, `hd` and
     * single letters match everything. 2 keeps `e4`/`5usa`-style names alive.
     */
    private const val MIN_KEY_LENGTH = 2
}
