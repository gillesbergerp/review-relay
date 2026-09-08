package com.github.gillesbergerp.reviewrelay.util

import java.io.File

/** Finding the repository a directory belongs to, so a worktree and its checkout compare equal. */
object GitDirs {

    /** The main repository's git directory, reached from a checkout or from any worktree of it. */
    fun repositoryOf(directory: String?): String? {
        var dir: File? = directory?.let { File(PathMatch.normalize(it)) } ?: return null
        while (dir != null) {
            val marker = File(dir, ".git")
            when {
                marker.isDirectory -> return PathMatch.normalize(marker.path)
                marker.isFile -> return mainGitDir(marker)
            }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * The directory `.git` sits in, which is what a repository-relative path is relative to.
     *
     * Not [repositoryOf], which answers with the git directory itself and, from a worktree, with
     * the main checkout's rather than this one's - neither is somewhere git commands can be run.
     */
    fun worktreeRootOf(directory: String?): String? {
        var dir: File? = directory?.let { File(PathMatch.normalize(it)) } ?: return null
        while (dir != null) {
            if (File(dir, ".git").exists()) return PathMatch.normalize(dir.path)
            dir = dir.parentFile
        }
        return null
    }

    /** A worktree's .git is a file pointing into `<repository>/.git/worktrees/<name>`. */
    private fun mainGitDir(marker: File): String? = runCatching {
        val pointer = marker.readLines()
            .firstOrNull { it.startsWith(GITDIR) }
            ?.removePrefix(GITDIR)
            ?.trim()
            ?: return null
        val resolved = File(pointer).takeIf { it.isAbsolute } ?: File(marker.parentFile, pointer)
        val path = PathMatch.normalize(resolved.toPath().normalize().toString())
        val worktrees = path.indexOf(WORKTREES)
        if (worktrees >= 0) path.substring(0, worktrees) else path
    }.getOrNull()

    private const val GITDIR = "gitdir:"
    private const val WORKTREES = "/worktrees/"
}
