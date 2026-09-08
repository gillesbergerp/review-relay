package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendStateListener
import com.github.gillesbergerp.reviewrelay.review.changes.ReviewedChangesService
import com.github.gillesbergerp.reviewrelay.review.changes.gitAvailable
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.comments
import com.github.gillesbergerp.reviewrelay.review.service.ReviewThreadListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.EmptyIcon
import javax.swing.JComponent

/**
 * The open reviews, as the tool window's own tabs.
 *
 * The reviews are the truth and the tabs follow them, in both directions: selecting a tab activates
 * its review, and closing one puts the review away rather than deleting it. [syncing] keeps those two
 * directions from chasing each other, since every change here fires the event that redraws the tabs.
 */
class ReviewTabs(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val build: (ReviewId) -> ReviewUi,
) : Disposable {

    /** One review's whole pane, plus the parts other things need to reach. */
    /**
     * [closing] is everything the tab owns. A content only drops its component when it is removed,
     * so anything parented to the tool window instead outlives every tab that ever held it.
     */
    class ReviewUi(
        val component: JComponent,
        val panel: ReviewPanel,
        val log: ReviewLogPane?,
        val closing: Disposable,
    )

    private val service get() = ReviewSessionService.getInstance(project)

    private val tabs = mutableMapOf<ReviewId, Content>()
    private var syncing = false

    init {
        sync()
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {

            override fun selectionChanged(event: ContentManagerEvent) {
                if (syncing || event.operation != ContentManagerEvent.ContentOperation.add) return
                event.content.reviewId?.let { service.activateReview(it) }
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                if (syncing) return
                event.content.reviewId?.let { service.closeReview(it) }
            }
        })
        (toolWindow as? ToolWindowEx)?.setTabActions(NewReview(), ReviewsMenu(project))

        val bus = project.messageBus.connect(this)
        bus.subscribe(
            ReviewThreadListener.TOPIC,
            object : ReviewThreadListener {
                // The count on the tab and the loudest thing about the review both move on this one.
                override fun commentsChanged() = syncLater()
                override fun reviewsChanged() = syncLater()
            },
        )
        // The tab carries whether its own agent is working, and only this says when that changed.
        bus.subscribe(
            BackendStateListener.TOPIC,
            object : BackendStateListener {
                override fun backendStateChanged() = syncLater()
            },
        )
    }

    private fun syncLater() = ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed) sync()
    }

    /** The panel of the review on screen, which is the one navigation and selection belong to. */
    fun activePanel(): ReviewPanel? = tabs[service.activeReviewId]?.panel

    private fun sync() {
        val manager = toolWindow.contentManager
        val open = service.openReviews
        syncing = true
        try {
            tabs.keys.filterNot { id -> open.any { it.id == id } }.forEach { id ->
                tabs.remove(id)?.let { manager.removeContent(it, true) }
                // Its log went with the tab, so what that log was showing is nobody's answer now.
                ReviewedChangesService.getInstance(project).forget(id)
            }
            open.forEach { review ->
                val existing = tabs[review.id]
                if (existing == null) {
                    tabs[review.id] = add(review)
                } else {
                    existing.displayName = review.label()
                    review.showState(existing)
                }
            }
            tabs[service.activeReviewId]?.let { manager.setSelectedContent(it) }
        } finally {
            syncing = false
        }
    }

    private fun add(review: ReviewSession): Content {
        val ui = build(review.id)
        val content = ContentFactory.getInstance().createContent(ui.component, review.label(), false)
        content.isCloseable = true
        // The platform paints a tab's icon only when this is set, whatever the content carries.
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        content.putUserData(REVIEW_ID, review.id)
        content.putUserData(PANEL, ui.panel)
        content.putUserData(LOG_PANE, ui.log)
        content.setDisposer(ui.closing)
        review.showState(content)
        toolWindow.contentManager.addContent(content)
        return content
    }

    /**
     * Where this review stands, on the tab rather than only in the header.
     *
     * A review that is not on screen is exactly the one worth saying this about, and each review is
     * worked by an agent of its own, so the tab can only speak for that one.
     */
    private fun ReviewSession.showState(content: Content) {
        val agent = BackendService.getInstance(project).backendOf(id)
        val activity = agent.activity?.takeIf { it.observed }
        val session = conversation?.sessionId
        val waiting = activity?.waitingFor(session)
        val published = id == service.publishedReviewId
        // A tab has one icon slot, so the loudest state takes it, and an empty one holds the place.
        content.icon = when {
            waiting != null || unread > 0 || proposed > 0 -> AllIcons.General.Balloon
            activity?.isBusy(session) == true -> AnimatedIcon.Default.INSTANCE
            published -> AllIcons.Vcs.Push
            else -> EmptyIcon.ICON_16
        }
        // The icon has no words, and a tab has no other way to explain itself.
        content.description = listOfNotNull(
            name,
            counted(),
            unreadReplies(),
            // Counted apart from the comments: that count is what a send carries, and a proposal
            // is exactly what a send does not.
            proposals(),
            when {
                activity == null -> null
                waiting != null -> "${agent.displayName} is waiting: $waiting"
                activity.isBusy(session) -> "${agent.displayName} is working"
                else -> "${agent.displayName} is idle"
            },
            // The review an agent was given: replies and observations land there, not here.
            "the agent's review".takeIf { published },
        ).joinToString(" · ")
    }

    private fun ReviewSession.label(): String = if (pending > 0) "$name ($pending)" else name

    private val Content.reviewId: ReviewId? get() = getUserData(REVIEW_ID)

    private val Content.panel: ReviewPanel? get() = getUserData(PANEL)

    override fun dispose() = tabs.clear()

    private inner class NewReview : DumbAwareAction("New Review", "Start a review of its own", AllIcons.General.Add) {

        override fun actionPerformed(e: AnActionEvent) {
            // The review on screen, whose log the reviewer is looking at when they press this.
            val against = ReviewedChangesService.getInstance(project).comparedWith(service.activeReviewId)
            val suggested = against.ifBlank { ReviewSessionService.DEFAULT_NAME }
            val name = Messages.showInputDialog(
                project,
                "Name for the new review:",
                "New Review",
                null,
                suggested,
                null,
            )?.trim() ?: return
            service.createReview(name, against)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    /** The log of the review on screen, which is the one a toolbar toggle is about. */
    fun activeLog(): ReviewLogPane? = tabs[service.activeReviewId]?.getUserData(LOG_PANE)

    private companion object {
        val PANEL = Key.create<ReviewPanel>("reviewrelay.tab.panel")
        val LOG_PANE = Key.create<ReviewLogPane>("reviewrelay.tab.log")
    }
}

/** The log keeps settings per tab id, so a deleted review must take its own with it. */
fun deleteReviewAndItsLog(project: Project, reviewId: ReviewId) {
    if (gitAvailable()) ReviewLogPane.forget(project, reviewId)
    ReviewSessionService.getInstance(project).deleteReview(reviewId)
}

/** Which review a tab is showing. Read by the tab's own context menu actions, which live outside. */
val REVIEW_ID: Key<ReviewId> = Key.create("reviewrelay.tab.reviewId")

/** Reopening, offered from the tab strip because a closed review has no tab to right-click. */
class ReviewsMenu(private val project: Project) :
    DefaultActionGroup("Reopen a Review", true) {

    init {
        templatePresentation.icon = AllIcons.General.ChevronDown
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val service = ReviewSessionService.getInstance(project)
        val reopen = service.closedReviews.map { review ->
            object : DumbAwareAction(review.described()) {
                override fun actionPerformed(e: AnActionEvent) = service.reopenReview(review.id)
            }
        }
        return if (reopen.isEmpty()) arrayOf(disabled("Nothing closed")) else reopen.toTypedArray()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun disabled(text: String) = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = Unit
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }
}

private fun ReviewSession.described(): String =
    name + if (threads.isEmpty()) " (empty)" else " (${comments(threads.size)})"

private fun ReviewSession.counted(): String = when {
    threads.isEmpty() -> "no comments"
    pending == 0 -> "${comments(threads.size)}, all sent"
    pending == threads.size -> "${comments(pending)}, none sent"
    else -> "$pending pending, ${threads.size - pending} sent"
}

private fun ReviewSession.unreadReplies(): String? = when (unread) {
    0 -> null
    1 -> "1 unread reply"
    else -> "$unread unread replies"
}

private fun ReviewSession.proposals(): String? = when (proposed) {
    0 -> null
    1 -> "1 proposal"
    else -> "$proposed proposals"
}
