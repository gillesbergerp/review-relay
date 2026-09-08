package com.github.gillesbergerp.reviewrelay.review.export

/**
 * Which lines of a pull request a comment can be attached to.
 *
 * GitHub takes an inline comment only on a line the diff shows, and refuses the whole review when
 * one of them is outside it - so this is asked before anything is posted rather than discovered
 * from a 422 with the batch already lost.
 */
object GitHubDiff {

    private val HUNK = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** The new-file line numbers this patch covers, added lines and context alike. */
    fun commentableLines(patch: String?): Set<Int> {
        if (patch.isNullOrBlank()) return emptySet()
        val lines = mutableSetOf<Int>()
        var at = 0
        for (line in patch.lines()) {
            val hunk = HUNK.find(line)
            if (hunk != null) {
                at = hunk.groupValues[1].toInt()
                continue
            }
            if (at == 0) continue
            when (line.firstOrNull()) {
                '+', ' ' -> lines.add(at++)
                // A removed line is on the other side, and the marker GitHub writes between hunks
                // of a truncated patch belongs to neither.
                '-', '\\' -> Unit
                // An empty line in a patch is an unchanged empty line with its leading space lost.
                null -> lines.add(at++)
                else -> Unit
            }
        }
        return lines
    }
}
