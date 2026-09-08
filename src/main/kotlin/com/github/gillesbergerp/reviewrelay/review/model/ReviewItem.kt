package com.github.gillesbergerp.reviewrelay.review.model

/**
 * Something said about a place in the code, whether or not it is part of the review yet.
 *
 * A proposal and a comment differ in their standing, not in what they point at, so anything that
 * only has to draw one or find its lines takes this rather than the two kinds apart.
 */
sealed interface ReviewItem {

    val id: ItemId
    val type: CommentType
    val target: CommentTarget
    val text: String

    /** The lines as they read when it was written, so the point survives the rewrite. */
    val reviewedCode: Snippet?

    val file: ReviewedFile get() = target.file

    val revision: Revision? get() = target.revision

    /** Absent on a whole-file comment, which is the one reader that still has two cases to serve. */
    val lines: LineRange? get() = (target as? CommentTarget.Line)?.lines

    fun lineLabel(): String = lines?.label().orEmpty()
}

/**
 * Who wrote something, as against [MessageAuthor], which says which side of a conversation it is on.
 *
 * The agent is named rather than counted: more than one can be pointed at the same IDE, and a
 * proposal is worth as much as the reader's opinion of where it came from.
 */
sealed interface Origin {

    data object Reviewer : Origin

    data class Agent(val name: String) : Origin

    val label: String
        get() = when (this) {
            is Reviewer -> "you"
            is Agent -> name
        }
}
