/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/** Pure multiset comparison for schedules; input order is irrelevant and duplicate rows count. */
internal object ProgrammeScheduleEquivalence {
    internal data class Entry(val title: String, val startUtcMillis: Long, val endUtcMillis: Long)

    fun areEquivalent(
        left: Collection<Entry>,
        right: Collection<Entry>,
        minimumProgrammes: Int = DEFAULT_MINIMUM_PROGRAMMES,
    ): Boolean {
        require(minimumProgrammes >= 1) { "minimumProgrammes must be at least 1" }
        if (left.size < minimumProgrammes || right.size < minimumProgrammes) return false
        if (left.size != right.size) return false

        val counts = HashMap<Entry, Int>(left.size)
        left.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        right.forEach { entry ->
            val count = counts[entry] ?: return false
            if (count == 1) counts.remove(entry) else counts[entry] = count - 1
        }
        return counts.isEmpty()
    }

    const val DEFAULT_MINIMUM_PROGRAMMES = 3
}
