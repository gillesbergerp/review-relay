package com.github.gillesbergerp.reviewrelay.review.model

import com.github.gillesbergerp.reviewrelay.util.PathMatch

/**
 * A file a comment is about, as a path relative to the project.
 *
 * Not a value class, for the reason [com.github.gillesbergerp.reviewrelay.util.Directory] is not:
 * the same file reaches us from the VFS, from a change in the log and from a stored review, which
 * disagree about separators and about case, so equality has to be [PathMatch]'s rather than the
 * string's. Two places used to compare these with `==` and were correct only because both sides
 * happened to come from the same function.
 */
class ReviewedFile(val path: String) : Comparable<ReviewedFile> {

    init {
        require(path.isNotBlank()) { "A comment is about a file" }
    }

    /** The file's own name, for showing a comment without the directories above it. */
    val name: String get() = PathMatch.normalize(path).substringAfterLast('/').ifEmpty { path }

    /** Where the file sits on disk, for the agent and for anything that has to open it. */
    fun absoluteIn(base: String): String = "${PathMatch.normalize(base)}/${PathMatch.normalize(path)}"

    /** Groups a review's comments by file the way equality groups them. */
    override fun compareTo(other: ReviewedFile): Int = PathMatch.compare(path, other.path)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ReviewedFile && PathMatch.same(path, other.path))

    override fun hashCode(): Int = PathMatch.normalize(path).lowercase().hashCode()

    override fun toString(): String = path

    companion object {
        /** Null and empty mean the same thing: no file was named. */
        fun of(path: String?): ReviewedFile? = path?.takeIf { it.isNotBlank() }?.let { ReviewedFile(it) }
    }
}
