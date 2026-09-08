package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.review.changes.ReviewedChangesService
import com.github.gillesbergerp.reviewrelay.review.changes.gitAvailable
import com.github.gillesbergerp.reviewrelay.review.export.HostingListener
import com.github.gillesbergerp.reviewrelay.ui.CommentAction
import com.github.gillesbergerp.reviewrelay.ui.editor.CommentBoxListener
import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentEditorPanel
import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.ui.editor.ReviewNavigator
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendStateListener
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.review.service.ReviewPublisher
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ThreadFilter
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ThreadGrouping
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.reviewOrder
import com.github.gillesbergerp.reviewrelay.review.model.ItemId
import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.ReviewItem
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.ReviewThreadListener
import com.github.gillesbergerp.reviewrelay.ui.clickable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// DumbAware: a review is comments on lines, which needs no index, and indexing is exactly when a
// reviewer opens a project and starts reading.
class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabs = ReviewTabs(project, toolWindow) { id -> buildReviewUi(project, toolWindow, id) }
        Disposer.register(toolWindow.disposable, tabs)
        Disposer.register(toolWindow.disposable) { panels.remove(project) }
        panels[project] = tabs
    }

    /** One review's pane. Built per tab, because a component belongs to one container. */
    private fun buildReviewUi(project: Project, toolWindow: ToolWindow, reviewId: ReviewId): ReviewTabs.ReviewUi {
        // Everything below belongs to this tab, and a closed one must stop listening and let its log
        // go: a second log under the same id is refused, so reopening showed no log at all.
        val closing = Disposer.newDisposable("Review Relay tab")
        Disposer.register(toolWindow.disposable, closing)
        val panel = ReviewPanel(project)
        val sessionBar = AgentSessionBar(project, reviewId)
        Disposer.register(closing, sessionBar)

        // Built first so the header can be aimed at the whole review: its buttons no longer act on
        // the comment pane, which heads itself now.
        val wrapper = JPanel(BorderLayout())

        // One row: the session belongs with the buttons that act on it, not on a line of its own.
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        val toolbar = toolbarOf("ReviewRelay.ToolWindowToolbar", HEADER_PLACE, wrapper)
        toolbar?.let { header.add(it.component, BorderLayout.WEST) }
        header.add(sessionBar, BorderLayout.CENTER)

        val bus = project.messageBus.connect(closing)
        // The toolbar polls on its own schedule, which is too slow to see a button go grey.
        bus.subscribe(
            BackendStateListener.TOPIC,
            object : BackendStateListener {
                override fun backendStateChanged() {
                    panel.refresh()
                    toolbar?.updateActionsAsync()
                }
            },
        )
        bus.subscribe(
            CommentBoxListener.TOPIC,
            object : CommentBoxListener {
                override fun boxChanged(itemId: ItemId) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) panel.refreshThread(itemId)
                    }
                }
            },
        )
        bus.subscribe(
            ReviewThreadListener.TOPIC,
            object : ReviewThreadListener {
                override fun commentsChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) panel.refresh()
                    }
                }
            },
        )
        bus.subscribe(
            HostingListener.TOPIC,
            HostingListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) panel.refreshActions()
                }
            },
        )

        wrapper.add(header, BorderLayout.NORTH)
        val log = source(project, closing, reviewId)
        wrapper.add(log?.beside(panel) ?: panel, BorderLayout.CENTER)
        return ReviewTabs.ReviewUi(wrapper, panel, log, closing)
    }

    /** Null without the Git plugin: the review loop is worth having on its own. */
    private fun source(project: Project, closing: Disposable, reviewId: ReviewId): ReviewLogPane? {
        if (!gitAvailable()) return null
        return runCatching {
            ReviewLogPane(
                project = project,
                reviewId = reviewId,
                onSelection = { describedAs(project, reviewId, describeSelection(it)) },
                onDescribed = { describedAs(project, reviewId, it) },
            )
        }
            .onSuccess { Disposer.register(closing, it) }
            .onFailure { Logger.getInstance(ReviewToolWindowFactory::class.java).warn("No source pane", it) }
            .getOrNull()
    }

    /** What the review is of, kept where a new review can be named after it. */
    private fun describedAs(project: Project, reviewId: ReviewId, label: String) {
        ReviewedChangesService.getInstance(project).compareWith(reviewId, label)
    }

    private fun JComponent.beside(other: JComponent): JComponent =
        OnePixelSplitter(false, "ReviewRelay.filesSplit", 0.3f).apply {
            firstComponent = this@beside
            secondComponent = other
        }

    companion object {
        private val panels = mutableMapOf<Project, ReviewTabs>()

        /** The log of whichever review is on screen, for actions that act on what is displayed. */
        fun activeLog(project: Project): ReviewLogPane? = panels[project]?.activeLog()

        fun select(project: Project, threadId: ThreadId) {
            panels[project]?.activePanel()?.select(threadId)
        }

        /** The pane of the review on screen, for a toolbar action about what it is showing. */
        fun activePanel(project: Project): ReviewPanel? = panels[project]?.activePanel()
    }
}

/** Named apart so a platform warning names the row it came from, now that there are two. */
private const val HEADER_PLACE = "ReviewRelay.Header"
private const val COMMENTS_PLACE = "ReviewRelay.Comments"

private const val GROUPING_KEY = "ReviewRelay.grouping"

/** What the summary box opens to once it is being written in, and how far it will grow. */
private const val SUMMARY_OPEN = 3
private const val SUMMARY_CAP = 8

private fun storedGrouping(project: Project): ThreadGrouping =
    PropertiesComponent.getInstance(project).getValue(GROUPING_KEY)
        ?.let { name -> ThreadGrouping.entries.firstOrNull { it.name == name } }
        ?: ThreadGrouping.NONE

/** Null when the group is missing, which is a broken install rather than a row to draw empty. */
private fun toolbarOf(groupId: String, place: String, target: JComponent): ActionToolbar? {
    val actions = ActionManager.getInstance().getAction(groupId) as? ActionGroup ?: return null
    return ActionManager.getInstance()
        .createActionToolbar(place, actions, true)
        .apply { targetComponent = target }
}

class ReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val threads = ReviewThreadList(project)

    /** The list's own actions, at its head: the row above the window acts on the review, not on this. */
    private val commentToolbar = toolbarOf("ReviewRelay.CommentToolbar", COMMENTS_PLACE, this)

    fun refreshActions() {
        commentToolbar?.updateActionsAsync()
    }

    /**
     * One row until it has something to hold.
     *
     * The rows are a fixed height rather than a minimum, and in a tool window anchored to the bottom
     * three of them were taken from the comment list at all times - on most reviews, for a summary
     * nobody wrote.
     */
    private val summaryArea = JBTextArea(1, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        emptyText.text = "Review summary, sent ahead of the comments"
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = sizeSummary()
            override fun focusLost(e: FocusEvent) = sizeSummary()
        })
    }

    /** Grows with what is written, to a cap: past that the box scrolls rather than eating the list. */
    private fun sizeSummary() {
        val wanted = if (summaryArea.hasFocus() || summaryArea.text.isNotBlank()) {
            summaryArea.lineCount.coerceIn(SUMMARY_OPEN, SUMMARY_CAP)
        } else {
            1
        }
        if (summaryArea.rows == wanted) return
        summaryArea.rows = wanted
        revalidate()
    }
    private val pendingLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBFont.small()
    }
    private val sendButton = object : JButton("Send Review", AllIcons.Vcs.Push) {
        /** Held at the width of the longest thing it says, so it does not resize mid-send. */
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val delivery = BackendService.getInstance(project).activeBackend.delivery
            val metrics = getFontMetrics(font)
            val widest = maxOf(metrics.stringWidth(delivery.verb), metrics.stringWidth(delivery.gerund))
            return Dimension(size.width + (widest - metrics.stringWidth(text)).coerceAtLeast(0), size.height)
        }
    }.apply {
        addActionListener { ReviewPublisher.publish(project) }
    }

    /** The button already knows when a send makes sense, so the shortcut asks it. */
    private val send = object : DumbAwareAction("Send Review") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = sendButton.isEnabled
        }

        override fun actionPerformed(e: AnActionEvent) = ReviewPublisher.publish(project)
    }
    private var updatingSummary = false

    var filter: ThreadFilter = ThreadFilter.ALL
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    /** Kept, unlike the filter: how a reviewer wants a review laid out outlives one sitting. */
    var grouping: ThreadGrouping = storedGrouping(project)
        set(value) {
            if (field == value) return
            field = value
            PropertiesComponent.getInstance(project).setValue(GROUPING_KEY, value.name)
            refresh()
        }

    private val inline = InlineCommentManager.getInstance(project)

    private val jump = onItem("Jump to Source") { ReviewNavigator.open(project, it) }
    private val showDiff = onItem("Show Diff") { ReviewNavigator.diff(project, it) }
    private val remove = action("Delete") { inline.deleteComment(it) }

    init {
        PopupHandler.installPopupMenu(threads, contextMenu(), ActionPlaces.POPUP)

        stepAction("ReviewRelay.NextComment", forward = true)
        stepAction("ReviewRelay.PreviousComment", forward = false)
        jump.registerCustomShortcutSet(
            CustomShortcutSet(*CommonShortcuts.getEditSource().shortcuts, *CommonShortcuts.ENTER.shortcuts),
            threads,
        )
        showDiff.registerCustomShortcutSet(CommonShortcuts.getDiff(), threads)
        remove.registerCustomShortcutSet(CommonShortcuts.getDelete(), threads)

        threads.onSelected = { item ->
            // A proposal is not a comment the agent is working, so it focuses nothing.
            ReviewSessionService.getInstance(project).focusedThreadId = (item as? ReviewThread)?.id
        }
        threads.onRead = { item ->
            (item as? ReviewThread)?.let { ReviewSessionService.getInstance(project).markRead(it.id) }
        }
        threads.onActivated = { ReviewNavigator.open(project, it) }

        summaryArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSummaryChanged()
            override fun removeUpdate(e: DocumentEvent) = onSummaryChanged()
            override fun changedUpdate(e: DocumentEvent) = onSummaryChanged()
        })

        commentToolbar?.component?.let { bar ->
            // Merged rather than assigned: the toolbar's own border carries the theme's padding, and
            // replacing it puts the buttons flush against the edge of the pane.
            bar.border = JBUI.Borders.merge(
                bar.border,
                JBUI.Borders.customLineBottom(JBColor.border()),
                true,
            )
            add(bar, BorderLayout.NORTH)
        }
        add(threads, BorderLayout.CENTER)
        // On the composer rather than the whole pane: the cards hold reply boxes, where Ctrl+Enter
        // means "save this reply" and sending the entire review instead cannot be taken back.
        add(
            composer().also { send.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), it) },
            BorderLayout.SOUTH,
        )
        // After the toolbar is in a container: one not yet in the hierarchy logs a stack trace
        // rather than updating.
        refresh()
    }

    /** The summary is the preamble of the next review, so sending belongs with it rather than up in the toolbar. */
    private fun composer(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLineTop(JBColor.border())
        add(JBScrollPane(summaryArea).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(pendingLabel, BorderLayout.WEST)
                add(sendButton, BorderLayout.EAST)
            },
            BorderLayout.SOUTH,
        )
    }

    fun refresh() {
        val session = ReviewSessionService.getInstance(project).currentSession
        val listable: List<ReviewItem> = session.threads + session.openProposals
        val shown = listable.sortedWith(reviewOrder).filter { filter.accepts(it) }
        threads.emptyText = emptyText(listable.size - shown.size)
        threads.show(grouping.rows(shown, ::moduleOf))

        updatingSummary = true
        try {
            if (summaryArea.text != session.summary) summaryArea.text = session.summary
        } finally {
            updatingSummary = false
        }

        syncComposer(session)
        // Every path that changes the list comes through here, and the toolbar's own poll is too
        // slow to see Clear Closed go grey as the last closed comment goes.
        commentToolbar?.updateActionsAsync()
    }

    /** Null for a file no module claims, which is a group of its own rather than a thread dropped. */
    private fun moduleOf(file: ReviewedFile): String? {
        val base = project.basePath ?: return null
        val onDisk = LocalFileSystem.getInstance().findFileByPath(file.absoluteIn(base)) ?: return null
        return ProjectFileIndex.getInstance(project).getModuleForFile(onDisk)?.name
    }

    private fun syncComposer(session: ReviewSession) {
        val pending = session.threads.count { it.isPending }
        val summary = session.summary.isNotBlank()
        // The button counts the summary, so a label counting only comments said "Nothing pending"
        // beside a live Send.
        pendingLabel.text = when {
            pending == 0 && summary -> "Summary only"
            pending == 0 -> "Nothing pending"
            pending == 1 && summary -> "1 pending comment and the summary"
            pending == 1 -> "1 pending comment"
            summary -> "$pending pending comments and the summary"
            else -> "$pending pending comments"
        }

        val backend = BackendService.getInstance(project).activeBackend
        val backends = BackendService.getInstance(project)
        val sending = backends.isRunning(BackendTask.PUBLISH, backends.activeConversation)
        sendButton.text = if (sending) backend.delivery.gerund else backend.delivery.verb
        sendButton.isEnabled = !sending && (pending > 0 || summary)
        // Named from the backend and the keymap: it used to say OpenCode and Ctrl+Enter whichever
        // agent was chosen and whatever the shortcut had been rebound to.
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(send).takeIf { it.isNotBlank() }
        sendButton.toolTipText = listOfNotNull(
            "${backend.delivery.verb} to ${backend.displayName}",
            shortcut?.let { "($it from the summary box)" },
        ).joinToString(" ")
    }

    /** Says the filter is what is hiding the work, rather than leaving the pane looking broken. */
    private fun emptyText(hidden: Int): String = when {
        // Read from the keymap: the shortcut is the user's to change, and the default already moved
        // once. Unbound it comes back empty, which left the sentence hanging after "press".
        hidden == 0 -> KeymapUtil
            .getFirstKeyboardShortcutText(ActionManager.getInstance().getAction("ReviewRelay.AddLineComment"))
            .takeIf { it.isNotBlank() }
            ?.let { "No review comments yet. Select lines and press $it" }
            ?: "No review comments yet. Select lines in the editor and add one from the context menu"
        filter == ThreadFilter.PROPOSALS -> "Nothing proposed; $hidden comments hidden"
        filter == ThreadFilter.NEEDS_YOU -> "Nothing needs you; $hidden hidden"
        else -> "Nothing open; $hidden hidden"
    }

    private fun selectedComment(): ReviewThread? = threads.selectedThread

    /**
     * Whether the caret is in a comment box rather than on the list.
     *
     * These actions are registered on the whole column, boxes included, and an ancestor's shortcut
     * beats the text field: Delete took the comment being written rather than a character of it,
     * and Enter jumped to the source rather than starting a line.
     */
    private fun writingAComment(): Boolean {
        val focused = IdeFocusManager.getInstance(project).focusOwner ?: return false
        return UIUtil.getParentOfType(InlineCommentEditorPanel::class.java, focused) != null
    }

    /** Going to a thread the filter is hiding shows it, rather than quietly doing nothing. */
    fun select(threadId: ThreadId) {
        val thread = ReviewSessionService.getInstance(project).thread(threadId)
        if (thread != null && !filter.accepts(thread)) filter = ThreadFilter.ALL
        threads.select(threadId)
    }

    /** One card, for a box opening or closing rather than the review itself changing. */
    fun refreshThread(itemId: ItemId) = threads.refresh(itemId)

    private fun onSummaryChanged() {
        sizeSummary()
        if (updatingSummary) return
        val service = ReviewSessionService.getInstance(project)
        service.updateSummary(summaryArea.text)
        syncComposer(service.currentSession)
    }

    /** Follows whatever the keymap has for the registered action, without taking it over. */
    private fun stepAction(id: String, forward: Boolean) {
        val shortcut = ActionManager.getInstance().getAction(id)?.shortcutSet ?: return
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = ReviewNavigator.step(project, forward)
        }.registerCustomShortcutSet(shortcut, this)
    }

    /**
     * The card's own buttons, for a reader who is not using the mouse.
     *
     * Send, Reply and the fold used to live only as icons on the card, so they could not be reached
     * by any key at all.
     */
    private fun contextMenu(): ActionGroup = DefaultActionGroup(
        jump,
        showDiff,
        Separator.getInstance(),
        onProposal("Add to the Review") { inline.actionsFor(it).file() },
        onProposal("Reword and Add...") { inline.actionsFor(it).startEdit(besideTheCode = false) },
        onProposal("Dismiss") { inline.actionsFor(it).dismiss() },
        action("Send This Comment", { it.isPending }) { ReviewPublisher.publish(project, it.id) },
        action("Reply...", { it.status != ThreadStatus.PENDING && it.status != ThreadStatus.SENT }) {
            inline.actionsFor(it).startReply(besideTheCode = false)
        },
        action("Collapse", { !it.status.open }) { inline.actionsFor(it).toggleExpanded() },
        Separator.getInstance(),
        // The card's own rule, so the menu and the buttons on the card cannot disagree.
        action("Resolve", { CommentAction.enabledFor(CommentAction.RESOLVE, it.status) }) {
            inline.closeComment(it, resolved = true)
        },
        action("Won't fix", { CommentAction.enabledFor(CommentAction.WONT_FIX, it.status) }) {
            inline.closeComment(it, resolved = false)
        },
        Separator.getInstance(),
        remove,
    )

    /** Shown only on a proposal, so a comment's menu does not carry three items it can never run. */
    private fun onProposal(text: String, perform: (Proposal) -> Unit): AnAction = object : DumbAwareAction(text) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = threads.selected is Proposal
        }

        override fun actionPerformed(e: AnActionEvent) {
            (threads.selected as? Proposal)?.let(perform)
        }
    }

    /** Going to the code needs only a place in it, which is the one thing a proposal has too. */
    private fun onItem(text: String, perform: (ReviewItem) -> Unit): AnAction = object : DumbAwareAction(text) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = threads.selected != null
            e.presentation.isEnabled = threads.selected != null && !writingAComment()
        }

        override fun actionPerformed(e: AnActionEvent) {
            threads.selected?.let(perform)
        }
    }

    private fun action(
        text: String,
        enabled: (ReviewThread) -> Boolean = { true },
        perform: (ReviewThread) -> Unit,
    ): AnAction = object : DumbAwareAction(text) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // Hidden with no comment selected, so right-clicking the empty column below the last
            // card does not open a menu of nothing but greyed items.
            val comment = selectedComment()
            e.presentation.isVisible = comment != null
            e.presentation.isEnabled = comment?.let(enabled) == true && !writingAComment()
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedComment()?.let(perform)
        }
    }
}
