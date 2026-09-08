package com.github.gillesbergerp.reviewrelay.util

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Paths

/** Comparing two directory paths for the same place, whatever separators and case they arrived in. */
object PathMatch {

    /**
     * Agents report Windows paths with backslashes; IntelliJ basePath uses forward slashes.
     *
     * The backslash is folded whatever this machine runs, not File.separatorChar: on Linux that
     * character is already the slash, so the call did nothing and a path an agent had written with
     * backslashes matched no file at all. A backslash is a legal name on Linux, which this trades
     * away - a repository path carrying one is rarer than an agent reporting a Windows path.
     */
    fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')

    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return normalize(a).equals(normalize(b), ignoreCase = ignoringCase)
    }

    /**
     * Whether [directory] sits inside [parent], asked the way [same] asks it.
     *
     * A plain startsWith answers case-sensitively, so on Windows a drive letter in the other case
     * put a directory outside a project that [same] would have called the project itself.
     */
    fun below(parent: String?, directory: String?): Boolean {
        if (parent == null || directory == null) return false
        return normalize(directory).startsWith(normalize(parent) + "/", ignoreCase = ignoringCase)
    }

    /**
     * [path] resolved against [base], or null when it does not stay inside it.
     *
     * A relative path is joined and a `..` in either is collapsed before the answer, because
     * everything downstream resolves them - so `../secret` reaches a real file outside the project.
     */
    fun within(base: String, path: String): String? {
        val resolved = runCatching { Paths.get(base).resolve(path).normalize().toString() }.getOrNull()
            ?: return null
        return normalize(resolved).takeIf { below(base, it) }
    }

    /** Orders paths the way [same] compares them, so equal paths never sort apart. */
    fun compare(a: String, b: String): Int =
        normalize(a).compareTo(normalize(b), ignoreCase = ignoringCase)

    private val ignoringCase: Boolean get() = !SystemInfo.isFileSystemCaseSensitive
}
