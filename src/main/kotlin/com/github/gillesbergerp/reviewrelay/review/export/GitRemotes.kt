package com.github.gillesbergerp.reviewrelay.review.export

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Remote URLs as the Git plugin has already resolved them, `insteadOf` rewrites and all.
 *
 * Nothing here may be touched without `gitAvailable()`: the classloader has no git4idea to give.
 */
internal object GitRemotes {

    /** Null while git knows of no repository here, which is not a repository known to have no remote. */
    fun urlsOf(project: Project): List<String>? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) return null
        return repositories.flatMap { repository -> repository.remotes.flatMap { it.urls + it.pushUrls } }
    }
}
