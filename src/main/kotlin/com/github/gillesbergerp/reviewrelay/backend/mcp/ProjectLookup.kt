package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.util.GitDirs
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/** Which open project an agent means, given the directory it is working in. */
object ProjectLookup {

    sealed interface Result {
        data class Found(val project: Project) : Result
        data object NoneOpen : Result
        data class Ambiguous(val open: List<String>) : Result
    }

    fun find(directory: String?): Result {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        if (open.isEmpty()) return Result.NoneOpen
        directory?.let { match(open, it) }?.let { return Result.Found(it) }
        val only = open.singleOrNull() ?: return Result.Ambiguous(open.mapNotNull { it.basePath })
        return Result.Found(only)
    }

    /** In the order they are worth trusting: the same directory, one inside it, one of its worktrees. */
    private fun match(open: List<Project>, directory: String): Project? =
        exact(open, directory) ?: below(open, directory) ?: sameRepository(open, directory)

    private fun exact(open: List<Project>, directory: String) =
        open.firstOrNull { PathMatch.same(it.basePath, directory) }

    private fun below(open: List<Project>, directory: String) =
        open.firstOrNull { PathMatch.below(it.basePath, directory) }

    /**
     * A worktree sits beside its checkout rather than inside it, so neither path contains the other
     * and only the repository they share identifies them as the same code.
     */
    private fun sameRepository(open: List<Project>, directory: String): Project? {
        val repository = GitDirs.repositoryOf(directory) ?: return null
        return open.firstOrNull { PathMatch.same(GitDirs.repositoryOf(it.basePath), repository) }
    }
}
