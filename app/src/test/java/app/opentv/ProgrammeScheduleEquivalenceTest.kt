/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.ProgrammeScheduleEquivalence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgrammeScheduleEquivalenceTest {
    private fun entry(title: String, start: Long = 1, end: Long = 2) =
        ProgrammeScheduleEquivalence.Entry(title, start, end)

    @Test fun `identical schedules are equivalent`() {
        val rows = listOf(entry("News"), entry("Film", 3, 4), entry("Sport", 5, 6))
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(rows, rows)).isTrue()
    }

    @Test fun `row order does not matter`() {
        val a = listOf(entry("News"), entry("Film", 3, 4), entry("Sport", 5, 6))
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(a, a.reversed())).isTrue()
    }

    @Test fun `different title is not equivalent`() = assertDifferent(entry("News"), entry("News!"))

    @Test fun `different start or end is not equivalent`() {
        assertDifferent(entry("News", 1, 2), entry("News", 2, 3))
        assertDifferent(entry("News", 1, 2), entry("News", 1, 3))
    }

    @Test fun `different programme count is not equivalent`() {
        val a = listOf(entry("A"), entry("B"), entry("C"))
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(a, a + entry("D"))).isFalse()
    }

    @Test fun `duplicate multiplicity is significant`() {
        val row = entry("A")
        val left = listOf(row, row, entry("B", 3, 4))
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(left, listOf(row, entry("B", 3, 4), entry("C", 5, 6)))).isFalse()
    }

    @Test fun `empty schedules are not equivalent`() {
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(emptyList(), emptyList())).isFalse()
    }

    @Test fun `samples below the configurable minimum are not equivalent`() {
        val two = listOf(entry("A"), entry("B", 3, 4))
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(two, two)).isFalse()
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(two, two, minimumProgrammes = 2)).isTrue()
    }

    private fun assertDifferent(leftRow: ProgrammeScheduleEquivalence.Entry, rightRow: ProgrammeScheduleEquivalence.Entry) {
        val left = listOf(leftRow, entry("B", 3, 4), entry("C", 5, 6))
        val right = listOf(rightRow, entry("B", 3, 4), entry("C", 5, 6))
        assertThat(ProgrammeScheduleEquivalence.areEquivalent(left, right)).isFalse()
    }
}
