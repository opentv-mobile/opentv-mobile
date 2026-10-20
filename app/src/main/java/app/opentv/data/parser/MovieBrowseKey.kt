/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

/** The existing browse identity: deliberately not scoped to source/category or TMDB id.
 * Unknown-year names merge; edition tokens unrecognised by the normalizer remain distinct.
 * Keep this algorithm stable: both persisted keys and in-memory grouping use it.
 */
data class MovieBrowseKey(val groupKey: String, val qualityRank: Int, val qualityLabel: String) {
    companion object {
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")

        fun from(name: String, year: Int?): MovieBrowseKey {
            val embeddedYear = YEAR.find(name)?.value?.toIntOrNull()
            val normalized = ChannelNameNormalizer.normalize(YEAR.replace(name, " "))
            return MovieBrowseKey(
                normalized.groupKey + "|" + ((year ?: embeddedYear)?.toString() ?: ""),
                normalized.qualityRank,
                normalized.qualityLabel,
            )
        }
    }
}
