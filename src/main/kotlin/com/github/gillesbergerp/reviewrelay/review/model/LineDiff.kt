package com.github.gillesbergerp.reviewrelay.review.model

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.progress.DumbProgressIndicator

enum class DiffLineKind { CONTEXT, REMOVED, ADDED }

data class DiffLine(val kind: DiffLineKind, val text: String)

/**
 * A line diff of a suggestion against the code it replaces, using the same engine as the IDE's own
 * diff so the result matches what the reviewer sees everywhere else.
 */
object LineDiff {

    fun rows(
        before: String,
        after: String,
        policy: ComparisonPolicy = ComparisonPolicy.DEFAULT,
    ): List<DiffLine> {
        val fragments = try {
            ComparisonManager.getInstance().compareLines(before, after, policy, DumbProgressIndicator.INSTANCE)
        } catch (_: DiffTooBigException) {
            null
        }
        return assemble(before.lines(), after.lines(), fragments)
    }

    /**
     * Turns the platform's fragments into displayable rows. Kept separate from [rows] so it can be
     * exercised without an IDE fixture; [fragments] is null when the differ refused the input.
     */
    fun assemble(before: List<String>, after: List<String>, fragments: List<LineFragment>?): List<DiffLine> {
        if (fragments == null) {
            return before.map { DiffLine(DiffLineKind.REMOVED, it) } + after.map { DiffLine(DiffLineKind.ADDED, it) }
        }

        val rows = mutableListOf<DiffLine>()
        var line = 0
        for (fragment in fragments) {
            val changeStart = fragment.startLine1.coerceIn(line, before.size)
            for (i in line until changeStart) rows.add(DiffLine(DiffLineKind.CONTEXT, before[i]))
            for (i in changeStart until fragment.endLine1.coerceIn(changeStart, before.size)) {
                rows.add(DiffLine(DiffLineKind.REMOVED, before[i]))
            }
            val addedStart = fragment.startLine2.coerceIn(0, after.size)
            for (i in addedStart until fragment.endLine2.coerceIn(addedStart, after.size)) {
                rows.add(DiffLine(DiffLineKind.ADDED, after[i]))
            }
            line = fragment.endLine1.coerceIn(line, before.size)
        }
        for (i in line until before.size) rows.add(DiffLine(DiffLineKind.CONTEXT, before[i]))
        return rows
    }
}
