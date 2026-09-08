package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.review.export.Availability
import com.github.gillesbergerp.reviewrelay.review.export.ClipboardDestination
import com.github.gillesbergerp.reviewrelay.review.export.Gh
import com.github.gillesbergerp.reviewrelay.review.export.GitHubDestination
import com.github.gillesbergerp.reviewrelay.review.export.GitHubHosting
import com.github.gillesbergerp.reviewrelay.review.export.ReviewDestination
import com.github.gillesbergerp.reviewrelay.review.export.Sent
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.notifyReview
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Sending the review somewhere that is not the agent working it.
 *
 * Only filed comments are ever offered: [ReviewDestination.selects] takes the review and answers in
 * threads, so there is nowhere for a proposal to be passed.
 */
abstract class ExportAction(protected val destination: ReviewDestination) : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (review, comments) = offered(project)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sending the review to ${destination.displayName}", false) {
                private var outcome: Sent? = null

                override fun run(indicator: ProgressIndicator) {
                    outcome = when (val ready = destination.availability(project)) {
                        is Availability.Unavailable -> Sent.Failed(ready.reason)
                        Availability.Ready -> destination.deliver(project, review, comments)
                    }
                }

                override fun onSuccess() = finish(project, review, outcome)

                override fun onThrowable(error: Throwable) = broke(project, error)
            },
        )
    }

    /**
     * On the EDT. The review, and what this destination would take of it.
     *
     * The anchors are written back first, as a send does: a comment the reviewer has since typed
     * above otherwise leaves for a pull request at the line it was written at rather than its own.
     */
    protected fun offered(project: Project): Pair<ReviewSession, List<ReviewThread>> {
        InlineCommentManager.getInstance(project).syncCommentLines()
        val review = ReviewSessionService.getInstance(project).currentSession
        return review to destination.selects(review)
    }

    /** On the EDT. What was sent is recorded, then said. */
    protected fun finish(project: Project, review: ReviewSession, sent: Sent?) = when (sent) {
        is Sent.Ok -> {
            ReviewSessionService.getInstance(project).recordDeliveries(review.id, destination.id, sent.recorded)
            notifyReview(project, sent.said, NotificationType.INFORMATION)
        }
        is Sent.Failed -> notifyReview(project, sent.reason, NotificationType.WARNING)
        null -> Unit
    }

    protected fun broke(project: Project, error: Throwable) = notifyReview(
        project,
        "Could not send to ${destination.displayName}: ${reason(error)}",
        NotificationType.ERROR,
    )

    override fun update(e: AnActionEvent) {
        val project = e.project
        val review = project?.let { ReviewSessionService.getInstance(it).currentSession }
        e.presentation.isVisible = project != null && destination.offeredIn(project)
        // Availability is not asked here: it shells out, and this runs on every toolbar refresh.
        e.presentation.isEnabled = review != null &&
            (destination.selects(review).isNotEmpty() || review.summary.isNotBlank())
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * The review as one pull request review, once the reviewer has seen which pull request.
 *
 * Its own three steps rather than [ExportAction]'s one, because the middle one is a dialog: reading
 * the pull requests belongs off the EDT, asking belongs on it, and posting belongs off it again.
 */
class PostToPullRequestAction(
    private val github: GitHubDestination = GitHubDestination(),
) : ExportAction(github) {

    private class Ready(
        val where: String,
        val choices: List<GitHubDestination.PullRequest>,
        val branch: Int?,
        val plan: GitHubDestination.Plan,
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = BackendService.getInstance(project)
        val (review, comments) = offered(project)
        // Claimed for the whole of it, dialog included: reading a plan takes seconds of gh calls,
        // and a second click through them posted the same comments to the pull request twice.
        if (!service.begin(BackendTask.EXPORT)) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Preparing the pull request review", true) {
                private var ready: Ready? = null
                private var refused: String? = null
                private var posting = false

                override fun run(indicator: ProgressIndicator) {
                    val where = github.repositoryOf(project)
                    val available = github.availability(project)
                    if (where == null || available is Availability.Unavailable) {
                        refused = (available as? Availability.Unavailable)?.reason
                            ?: GitHubDestination.NO_CHECKOUT
                        // The cache is what decided this action was worth offering at all, and gh has
                        // just disagreed with it.
                        GitHubHosting.getInstance(project).forget()
                        return
                    }
                    indicator.checkCanceled()
                    try {
                        val choices = github.choices(where)
                        if (choices.isEmpty()) {
                            refused = "There is no open pull request to post to"
                            return
                        }
                        indicator.checkCanceled()
                        val branch = github.branchPullRequest(where)?.number
                        val first = choices.firstOrNull { it.number == branch } ?: choices.first()
                        // Off its own branch nothing vouches for the line numbers, so start on the file.
                        val fileOnly = first.number != branch
                        ready = Ready(where, choices, branch, github.plan(project, where, comments, first, fileOnly))
                    } catch (e: Gh.Failed) {
                        refused = e.said
                    }
                }

                override fun onSuccess() {
                    if (project.isDisposed) return
                    refused?.let { return notifyReview(project, it, NotificationType.WARNING) }
                    val prepared = ready ?: return
                    val dialog = PostToPullRequestDialog(
                        project = project,
                        where = prepared.where,
                        github = github,
                        threads = comments,
                        summary = review.summary,
                        choices = prepared.choices,
                        branchPullRequest = prepared.branch,
                        initial = prepared.plan,
                    )
                    if (!dialog.showAndGet()) return
                    posting = true
                    send(project, review, prepared.where, dialog.plan)
                }

                override fun onThrowable(error: Throwable) = broke(project, error)

                /** The post holds the claim from here, and ends it when it is done. */
                override fun onFinished() {
                    if (!posting) service.end(BackendTask.EXPORT)
                }
            },
        )
    }

    private fun send(
        project: Project,
        review: ReviewSession,
        where: String,
        plan: GitHubDestination.Plan,
    ) {
        ProgressManager.getInstance().run(
            // Not cancellable: a post cannot be taken back once it has left.
            object : Task.Backgroundable(project, "Posting the review to ${plan.at}", false) {
                private var outcome: Sent? = null

                override fun run(indicator: ProgressIndicator) {
                    outcome = try {
                        github.post(where, review, plan)
                    } catch (e: Gh.Failed) {
                        Sent.Failed(e.said)
                    }
                }

                override fun onSuccess() = finish(project, review, outcome)

                override fun onThrowable(error: Throwable) = broke(project, error)

                override fun onFinished() = BackendService.getInstance(project).end(BackendTask.EXPORT)
            },
        )
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val posting = e.project?.let { BackendService.getInstance(it).isRunning(BackendTask.EXPORT) } == true
        if (posting) e.presentation.isEnabled = false
    }
}

class ExportToClipboardAction : ExportAction(ClipboardDestination())
