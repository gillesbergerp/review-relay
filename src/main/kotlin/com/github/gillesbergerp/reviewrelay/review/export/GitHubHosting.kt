package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.changes.gitAvailable
import com.github.gillesbergerp.reviewrelay.util.GitDirs
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicBoolean

fun interface HostingListener {

    fun hostingChanged()

    companion object {
        val TOPIC: Topic<HostingListener> = Topic.create("ReviewRelayHosting", HostingListener::class.java)
    }
}

/**
 * Whether this project's repository is one the GitHub CLI is signed into.
 *
 * Asked of `gh` rather than matched against github.com, so an Enterprise host counts exactly and
 * only where the posting would actually work. The answer is cached because the action that needs it
 * is asked on every toolbar refresh, and never written down: a wrong No would hide the button with
 * nothing on screen to say why, and nothing to clear it but a restart.
 */
@Service(Service.Level.PROJECT)
class GitHubHosting(private val project: Project) {

    enum class Verdict { YES, NO, UNKNOWN }

    @Volatile
    private var verdict = Verdict.UNKNOWN

    @Volatile
    private var askedAt = 0L

    private val asking = AtomicBoolean(false)

    /** Never blocks: the answer is whatever is known, and a stale one is renewed behind this call. */
    fun offered(): Boolean {
        renew()
        return verdict != Verdict.NO
    }

    /** After a send failed, when the reason may have been the very thing this caches. */
    fun forget() {
        askedAt = 0L
    }

    private fun renew() {
        if (askedAt != 0L && System.currentTimeMillis() - askedAt < TTL_MILLIS) return
        if (!asking.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val answer = runCatching { ask() }.getOrDefault(Verdict.UNKNOWN)
                val moved = answer != verdict
                verdict = answer
                askedAt = System.currentTimeMillis()
                if (moved && !project.isDisposed) {
                    project.messageBus.syncPublisher(HostingListener.TOPIC).hostingChanged()
                }
            } finally {
                asking.set(false)
            }
        }
    }

    /**
     * Blocking; call off the EDT.
     *
     * [Verdict.NO] only on evidence: remotes that are known, every one of them a host this could
     * read, all of them refused, and a `gh` that answered. An SSH alias out of `~/.ssh/config`, a
     * `gh` off the IDE's PATH and being offline all reach here, and none of them means "not GitHub".
     */
    private fun ask(): Verdict {
        val where = GitDirs.worktreeRootOf(project.basePath) ?: return Verdict.UNKNOWN
        val urls = remotes() ?: return Verdict.UNKNOWN
        if (urls.isEmpty()) return Verdict.NO
        val hosts = urls.map { hostOf(it) }
        for (host in hosts.filterNotNull().toSet()) {
            if (exitCode(where, "auth", "status", "--hostname", host) == 0) return Verdict.YES
        }
        // Asked after the matches, so one remote this cannot read does not bury another that matched.
        if (hosts.any { it == null }) return Verdict.UNKNOWN
        // Signed in but not here is an answer; a gh that cannot say is not one.
        return if (exitCode(where, "auth", "status") == 0) Verdict.NO else Verdict.UNKNOWN
    }

    private fun exitCode(where: String, vararg args: String): Int =
        runCatching { Gh.exitCode(where, *args) }.getOrDefault(-1)

    private fun remotes(): List<String>? =
        if (!gitAvailable()) null else runCatching { GitRemotes.urlsOf(project) }.getOrNull()

    companion object {

        fun getInstance(project: Project): GitHubHosting = project.getService(GitHubHosting::class.java)

        /**
         * The host a remote URL names, or null where this cannot tell.
         *
         * Null rather than a guess, because two shapes are indistinguishable from the URL alone: the
         * scp form and a Windows path both put a colon after their first token, and a bare name is
         * an alias only `ssh` can resolve.
         */
        fun hostOf(url: String): String? {
            val trimmed = url.trim()
            val scheme = trimmed.indexOf("://")
            val rest = if (scheme >= 0) trimmed.substring(scheme + 3) else trimmed
            val host = rest.substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
            val labels = host.split('.')
            return host.takeIf { labels.size > 1 && labels.none { label -> label.isBlank() } }
        }

        /** Long enough that the toolbar is not asking gh, short enough that signing in is noticed. */
        private const val TTL_MILLIS = 10 * 60 * 1000L
    }
}
