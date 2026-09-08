package com.github.gillesbergerp.reviewrelay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitDirsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun checkout(name: String): File =
        temp.newFolder(name).also { File(it, ".git").mkdir() }

    private fun worktree(name: String, of: File, pointer: String? = null): File =
        temp.newFolder(name).also {
            File(it, ".git").writeText(
                "gitdir: " + (pointer ?: File(of, ".git/worktrees/$name").path.replace('\\', '/')) + "\n"
            )
        }

    @Test
    fun `a checkout resolves to its own git directory`() {
        val repo = checkout("app")
        assertEquals(PathMatch.normalize(File(repo, ".git").path), GitDirs.repositoryOf(repo.path))
    }

    @Test
    fun `a subdirectory resolves to the repository above it`() {
        val repo = checkout("app")
        val nested = File(repo, "src/main").also { it.mkdirs() }
        assertEquals(GitDirs.repositoryOf(repo.path), GitDirs.repositoryOf(nested.path))
    }

    @Test
    fun `a worktree resolves to the checkout it belongs to`() {
        val repo = checkout("app")
        val tree = worktree("app-feature", repo)
        assertEquals(GitDirs.repositoryOf(repo.path), GitDirs.repositoryOf(tree.path))
    }

    @Test
    fun `a worktree of another repository does not match`() {
        val app = checkout("app")
        val other = checkout("other")
        assertEquals(GitDirs.repositoryOf(other.path), GitDirs.repositoryOf(worktree("o-wt", other).path))
        assert(GitDirs.repositoryOf(app.path) != GitDirs.repositoryOf(worktree("x", other).path))
    }

    @Test
    fun `a relative gitdir pointer resolves against the worktree`() {
        val repo = checkout("app")
        val tree = worktree("app-rel", repo, pointer = "../app/.git/worktrees/app-rel")
        assertEquals(GitDirs.repositoryOf(repo.path), GitDirs.repositoryOf(tree.path))
    }

    @Test
    fun `a plain gitdir without a worktrees segment is taken as the repository`() {
        val tree = temp.newFolder("linked")
        val elsewhere = temp.newFolder("bare.git")
        File(tree, ".git").writeText("gitdir: " + elsewhere.path.replace('\\', '/'))
        assertEquals(PathMatch.normalize(elsewhere.path), GitDirs.repositoryOf(tree.path))
    }

    @Test
    fun `a directory outside any repository has none`() {
        assertNull(GitDirs.repositoryOf(temp.newFolder("loose").path))
    }

    @Test
    fun `the worktree root is where git commands run, not the git directory`() {
        val repo = checkout("app")
        assertEquals(PathMatch.normalize(repo.path), GitDirs.worktreeRootOf(repo.path))
    }

    @Test
    fun `a subdirectory of a checkout resolves to the checkout`() {
        val repo = checkout("app")
        val nested = File(repo, "backend/src").also { it.mkdirs() }
        assertEquals(PathMatch.normalize(repo.path), GitDirs.worktreeRootOf(nested.path))
    }

    @Test
    fun `a worktree is its own root rather than the checkout it belongs to`() {
        val repo = checkout("app")
        val tree = worktree("app-feature", repo)
        assertEquals(PathMatch.normalize(tree.path), GitDirs.worktreeRootOf(tree.path))
    }

    @Test
    fun `a directory in no repository has no root`() {
        assertNull(GitDirs.worktreeRootOf(temp.newFolder("loose").path))
        assertNull(GitDirs.worktreeRootOf(null))
    }

    @Test
    fun `no directory has no repository`() {
        assertNull(GitDirs.repositoryOf(null))
    }
}
