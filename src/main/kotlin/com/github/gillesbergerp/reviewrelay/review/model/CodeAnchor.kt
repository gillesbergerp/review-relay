package com.github.gillesbergerp.reviewrelay.review.model

/**
 * Finding reviewed lines again in text that has moved on from the numbers they were read at.
 *
 * A comment keyed to the commit it was written against would be orphaned the moment an agent
 * amended it, so the code itself is what the comment is placed by everywhere except the revision it
 * came from. Addressing a comment means editing the lines it is about, so the code is looked for as
 * it was written and then as something recognisably descended from it.
 */
object CodeAnchor {

    private val WORD = Regex("[A-Za-z0-9_]+")

    /** Below this, shared words are two lines of the same language rather than the same code. */
    private const val ENOUGH = 0.5

    /** A rival this close names two candidates, which is as good as naming none. */
    private const val MARGIN = 0.1

    /**
     * The 1-based line [code] starts on in [text], or null when it is not there exactly once.
     *
     * Ambiguous is as good as absent: drawing the comment on the first of several matches invents a
     * place for it. The match is exact, so re-indented code reads as gone rather than as moved.
     */
    fun lineOf(text: String, code: String): Int? {
        val needle = code.trimEnd()
        if (needle.isEmpty()) return null
        val at = text.indexOf(needle)
        if (at < 0 || text.indexOf(needle, at + 1) >= 0) return null
        return text.take(at).count { it == '\n' } + 1
    }

    /**
     * The 1-based line the lines most like [code] start on in [text], or null when nothing there is
     * like it enough, or two places are alike.
     *
     * Words rather than characters, so a renamed type or a changed argument still reads as the same
     * code edited, while a line sharing only `val` and `return` with it does not.
     */
    fun nearestLineOf(text: String, code: String): Int? {
        val needle = words(code)
        if (needle.isEmpty()) return null
        val lines = text.lines().map { words(it) }
        val span = code.trimEnd().lines().size
        if (span > lines.size) return null

        val scores = (0..lines.size - span).map { at ->
            similarity(needle, lines.subList(at, at + span).flatten())
        }
        val best = scores.withIndex().maxByOrNull { it.value } ?: return null
        if (best.value < ENOUGH) return null
        // Only somewhere else counts as a rival. The windows overlapping the match share most of
        // their lines with it and would always score close enough to veto it.
        val rival = scores.filterIndexed { at, _ -> at + span <= best.index || at >= best.index + span }
            .maxOrNull() ?: 0.0
        if (best.value - rival < MARGIN) return null
        return best.index + 1
    }

    /**
     * Where a comment on [code] belongs in [text]: where the code still is, where what became of it
     * is, or [line] when neither can be told.
     */
    fun placeOf(text: String, code: String?, line: Int?): Int? {
        val needle = code?.takeIf { it.isNotBlank() } ?: return line
        return lineOf(text, needle) ?: nearestLineOf(text, needle) ?: line
    }

    private fun words(code: String): List<String> = WORD.findAll(code).map { it.value }.toList()

    /** Twice the words the two share over what they have between them, counting repeats once each. */
    private fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val unclaimed = b.toMutableList()
        val shared = a.count { unclaimed.remove(it) }
        return 2.0 * shared / (a.size + b.size)
    }
}
