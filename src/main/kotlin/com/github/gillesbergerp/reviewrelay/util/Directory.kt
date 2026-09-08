package com.github.gillesbergerp.reviewrelay.util

/**
 * A directory, compared the way this plugin has to compare them.
 *
 * Not a value class: paths arrive from an agent's JSON, from git and from the IDE, which disagree
 * about separators and about case, so equality has to be [PathMatch]'s rather than the string's.
 * The path is kept as it was given, because that is what is shown and what an agent is told.
 */
class Directory(val path: String) {

    /** The last segment, which is what a session is labelled with when it is somewhere else. */
    val name: String get() = PathMatch.normalize(path).substringAfterLast('/').ifEmpty { path }

    val isBlank: Boolean get() = path.isBlank()

    /** True when [other] is this directory or inside it. */
    fun contains(other: Directory): Boolean = this == other || PathMatch.below(path, other.path)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Directory && PathMatch.same(path, other.path))

    override fun hashCode(): Int = PathMatch.normalize(path).lowercase().hashCode()

    override fun toString(): String = path

    companion object {
        /** Null and empty mean the same thing: nowhere was named. */
        fun of(path: String?): Directory? = path?.takeIf { it.isNotBlank() }?.let { Directory(it) }
    }
}
