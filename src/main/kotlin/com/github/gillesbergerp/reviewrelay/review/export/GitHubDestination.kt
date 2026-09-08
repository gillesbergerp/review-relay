package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.service.comments
import com.github.gillesbergerp.reviewrelay.util.GitDirs
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.util.json.bool
import com.github.gillesbergerp.reviewrelay.util.json.get
import com.github.gillesbergerp.reviewrelay.util.json.int
import com.github.gillesbergerp.reviewrelay.util.json.items
import com.github.gillesbergerp.reviewrelay.util.json.string
import com.google.gson.JsonElement
import com.intellij.openapi.project.Project

/**
 * The review as one pull request review, submitted through the GitHub CLI.
 *
 * Every comment goes in a single call, so the reviewer's colleagues get one notification and one
 * conversation rather than a comment at a time.
 */
class GitHubDestination : ReviewDestination {

    override val id = "github"

    override val displayName = "GitHub pull request"

    /** One of the open pull requests, as the picker lists it. */
    data class PullRequest(
        val number: Int,
        val title: String,
        val branch: String,
        val draft: Boolean,
        val url: String,
    )

    /** What a post would do, settled before the reviewer is asked and carried out unchanged after. */
    class Plan internal constructor(
        val pullRequest: PullRequest,
        val slug: String,
        val head: String,
        internal val sending: List<Pair<ReviewThread, Map<String, Any?>>>,
        /** Already on this pull request, and so left out of [sending] rather than posted twice. */
        val alreadyThere: Int,
        /** Carried so the picker can show what it planned with, having offered the choice. */
        val fileOnly: Boolean,
    ) {

        /** How GitHub names the pull request, and how a comment records having gone there. */
        val at: String get() = "$slug#${pullRequest.number}"

        val count: Int get() = sending.size

        val onFile: Int get() = sending.count { it.second["line"] == null }
    }

    override fun offeredIn(project: Project): Boolean = GitHubHosting.getInstance(project).offered()

    override fun availability(project: Project): Availability {
        val where = repositoryOf(project) ?: return Availability.Unavailable(NO_CHECKOUT)
        return try {
            Gh.run(where, "auth", "status")
            Availability.Ready
        } catch (e: Gh.Failed) {
            Availability.Unavailable(e.said)
        } catch (e: Exception) {
            Availability.Unavailable("The GitHub CLI (gh) is not installed")
        }
    }

    /**
     * Everything still open.
     *
     * Not "everything this pull request has not had": which pull request is not settled until one is
     * picked, and this is also the question of whether there is anything to press the button for.
     */
    override fun selects(review: ReviewSession): List<ReviewThread> = review.threads.filter { it.isOpen }

    override fun deliver(project: Project, review: ReviewSession, comments: List<ReviewThread>): Sent {
        val where = repositoryOf(project) ?: return Sent.Failed(NO_CHECKOUT)
        return try {
            val pr = branchPullRequest(where) ?: return Sent.Failed("No open pull request for this branch")
            post(where, review, plan(project, where, comments, pr, fileOnly = false))
        } catch (e: Gh.Failed) {
            Sent.Failed(e.said)
        }
    }

    fun repositoryOf(project: Project): String? = GitDirs.worktreeRootOf(project.basePath)

    /** Blocking; call off the EDT. The open pull requests, the branch's own among them. */
    fun choices(where: String): List<PullRequest> {
        val listed = Json.parse(
            Gh.run(where, "pr", "list", "--state", "open", "--limit", "100", "--json", LIST_FIELDS),
        ).items.mapNotNull { pullRequest(it) }
        // A hundred is a page, not the repository: the branch's own may be past the end of it.
        val branch = branchPullRequest(where) ?: return listed
        return if (listed.any { it.number == branch.number }) listed else listOf(branch) + listed
    }

    /** Blocking; call off the EDT. Null on a detached head, or a branch nobody has opened one for. */
    fun branchPullRequest(where: String): PullRequest? =
        runCatching { Json.parse(Gh.run(where, "pr", "view", "--json", LIST_FIELDS)) }
            .getOrNull()
            ?.let { pullRequest(it) }

    /**
     * Blocking; call off the EDT. Which comments would go where, and which would miss their line.
     *
     * The head commit and the repository are read again for the pull request that was picked rather
     * than carried over from another: commit_id has to be that pull request's head, and its own URL
     * says which repository it is in where gh's base-repo guess in a fork clone does not.
     */
    fun plan(
        project: Project,
        where: String,
        comments: List<ReviewThread>,
        pr: PullRequest,
        fileOnly: Boolean,
    ): Plan {
        val detail = Json.parse(Gh.run(where, "pr", "view", pr.number.toString(), "--json", "headRefOid,url"))
        val head = detail["headRefOid"].string
            ?: throw Gh.Failed("Pull request #${pr.number} names no head commit")
        val slug = slugOf(detail["url"].string ?: pr.url)
            ?: throw Gh.Failed("Could not tell which GitHub repository #${pr.number} is in")
        val reference = "$slug#${pr.number}"
        val (there, sending) = comments.partition { it.deliveredTo(id, reference) }
        val commentable = if (fileOnly) emptyMap() else diffOf(where, slug, pr.number)
        val prefix = prefixIn(project, where)
        return Plan(pr, slug, head, sending.map { it to body(it, prefix, commentable, head) }, there.size, fileOnly)
    }

    /** Blocking; call off the EDT. */
    fun post(where: String, review: ReviewSession, plan: Plan): Sent {
        if (plan.count == 0 && review.summary.isBlank()) {
            return Sent.Failed("Everything still open is already on ${plan.at}")
        }
        val body = mapOf(
            "commit_id" to plan.head,
            // GitHub requires a body for a COMMENT review, so a reviewer who wrote no summary gets
            // the one true thing there is to say rather than a line advertising this plugin.
            "body" to review.summary.ifBlank { comments(plan.count).replaceFirstChar { it.uppercase() } },
            "event" to "COMMENT",
            "comments" to plan.sending.map { it.second },
        )
        val endpoint = "repos/${plan.slug}/pulls/${plan.pullRequest.number}/reviews"
        Gh.run(where, "api", "--method", "POST", endpoint, "--input", "-", input = Json.write(body))
        return Sent.Ok(
            said = said(plan),
            recorded = plan.sending.associate { it.first.id to plan.at },
        )
    }

    /** Paged, so a pull request of more than a page of files is read as more than one document. */
    private fun diffOf(where: String, slug: String, number: Int): Map<String, Set<Int>> =
        Json.parseAll(Gh.run(where, "api", "--paginate", "repos/$slug/pulls/$number/files"))
            .flatMap { it.items }
            .associate { file ->
                // Normalised on both sides: a path an agent proposed can carry the other separator.
                PathMatch.normalize(file["filename"].string.orEmpty()) to
                    GitHubDiff.commentableLines(file["patch"].string)
            }

    private fun pullRequest(json: JsonElement?): PullRequest? {
        val number = json["number"].int ?: return null
        return PullRequest(
            number = number,
            title = json["title"].string.orEmpty(),
            branch = json["headRefName"].string.orEmpty(),
            draft = json["isDraft"].bool ?: false,
            url = json["url"].string.orEmpty(),
        )
    }

    private fun said(plan: Plan): String {
        if (plan.onFile == 0) return "Posted ${comments(plan.count)} to ${plan.at}."
        // Said rather than hidden: a comment that arrives on the file instead of the line has moved,
        // and the reviewer is the only one who can tell whether that matters.
        return "Posted ${comments(plan.count)} to ${plan.at}; ${plan.onFile} landed on the file, " +
            "their lines not being in the diff."
    }

    /**
     * One comment as GitHub takes it.
     *
     * A line outside the diff is refused, and refused comments fail the whole review, so anything
     * that cannot be placed is attached to the file instead of being dropped.
     */
    private fun body(
        thread: ReviewThread,
        prefix: String,
        commentable: Map<String, Set<Int>>,
        head: String,
    ): Map<String, Any?> {
        val path = PathMatch.normalize(prefix + thread.file.path)
        val lines = (thread.target as? CommentTarget.Line)?.lines
        val known = commentable[path].orEmpty()
        val placed = lines != null && numbersHold(thread, head) &&
            lines.start in known && lines.end in known
        return if (placed) {
            mapOf(
                "path" to path,
                "line" to lines.end,
                "start_line" to lines.start.takeIf { it != lines.end },
                "side" to "RIGHT",
                "body" to text(thread),
            )
        } else {
            mapOf("path" to path, "subject_type" to "file", "body" to text(thread))
        }
    }

    /**
     * Whether this comment's line numbers mean anything on the pull request's head.
     *
     * A comment written in a commit's diff numbers its lines in that commit. Those numbers address
     * whatever sits at them on the head, which for anything but the head itself is other code - so
     * the comment goes to its file rather than to a line nobody chose.
     */
    private fun numbersHold(thread: ReviewThread, head: String): Boolean {
        val revision = thread.revision ?: return true
        return head.startsWith(revision.hash) || revision.hash.startsWith(head)
    }

    /** The comment as it reads there: what was written, and nothing this end added to it. */
    private fun text(thread: ReviewThread): String = Suggestion.normalized(thread.text)

    /** A comment's path is relative to the project; GitHub's is relative to the repository. */
    private fun prefixIn(project: Project, repository: String): String {
        val base = project.basePath?.let { PathMatch.normalize(it) } ?: return ""
        val root = PathMatch.normalize(repository)
        if (PathMatch.same(base, root)) return ""
        if (!PathMatch.below(root, base)) return ""
        return base.removePrefix("$root/") + "/"
    }

    companion object {

        const val NO_CHECKOUT = "This project is not in a git checkout"

        /**
         * The repository a pull request URL names, which is the one that pull request is in.
         *
         * Asked of the pull request rather than of `gh repo view`: in a fork clone that has never had
         * `gh repo set-default` run, the base repository is a guess, and a number from one repository
         * posted against another's name is a 404 with the whole review lost.
         */
        internal fun slugOf(url: String): String? {
            val path = url.substringAfter("://", "").substringAfter('/', "").split('/')
            if (path.size < 2 || path[0].isBlank() || path[1].isBlank()) return null
            return "${path[0]}/${path[1]}"
        }

        private const val LIST_FIELDS = "number,title,headRefName,isDraft,url"
    }
}
