package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.ui.decoration.CommentCountDecorator
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.ReviewThreadListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.impl.CommonUiProperties
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.MainVcsLogUiProperties
import com.intellij.vcs.log.impl.VcsLogManager
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties
import com.intellij.vcs.log.impl.VcsLogUiProperties
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.ui.VcsLogPanel
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesBrowser
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * What is being reviewed, chosen in the IDE's own log.
 *
 * Branch tree, commit graph and the log's own changes browser, which already shows the files of a
 * selection — so this replaces the comparison controls and the file list at once. The log is a
 * project-wide service that comes up on its own schedule, so the pane starts empty and fills in.
 */
class ReviewLogPane(
    private val project: Project,
    private val reviewId: ReviewId,
    private val onSelection: (List<Revision>) -> Unit,
    private val onDescribed: (String) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val logHost = JPanel(BorderLayout())

    private var logUi: MainVcsLogUi? = null
    private var current: Disposable? = null

    @Volatile
    private var gone = false

    /** Set while the remembered commits are being put back, when an empty selection is the model's. */
    private var restoring = false

    /** The working tree, which is the one thing a log has no row for. */
    private val local = SimpleAsyncChangesBrowser(project, false, false).apply {
        hideViewerBorder()
        viewer.cellRenderer = CommentCountDecorator(viewer.cellRenderer, project)
    }

    private val cards = JPanel(CardLayout()).apply {
        add(logHost, LOG)
        add(local, LOCAL)
    }

    var showingLocal: Boolean = false
        private set

    init {
        border = JBUI.Borders.customLineRight(JBColor.border())
        logHost.add(waiting("Loading the log", loading = true), BorderLayout.CENTER)
        add(cards, BorderLayout.CENTER)
        build()

        project.messageBus.connect(this).subscribe(
            VcsProjectLog.VCS_PROJECT_LOG_CHANGED,
            object : VcsProjectLog.ProjectLogListener {
                // The manager is recreated when the index is invalidated or a VCS mapping changes.
                override fun logCreated(manager: VcsLogManager) = rebuild()
                override fun logDisposed(manager: VcsLogManager) = clear("The log was closed. Reopen the project to bring it back.")
            },
        )
        // Only the counts move when a comment changes; the tree keeps its rows.
        project.messageBus.connect(this).subscribe(
            ReviewThreadListener.TOPIC,
            object : ReviewThreadListener {
                override fun commentsChanged() = ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        logUi?.changesBrowser?.viewer?.repaint()
                        local.viewer.repaint()
                    }
                }
            },
        )
        // This pane asks git for the working tree itself rather than reading a change list, so
        // nothing else would tell it a file has stopped being changed.
        project.messageBus.connect(this).subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    if (showingLocal) refreshLocal()
                }
            },
        )
    }

    private fun build() {
        if (!VcsProjectLog.isAvailable(project)) {
            clear("No git repository in this project.")
            return
        }
        VcsProjectLog.runWhenLogIsReady(project) { manager ->
            if (project.isDisposed || gone) return@runWhenLogIsReady
            runCatching { install(manager) }
                .onFailure {
                    LOGGER.warn("Could not embed the log", it)
                    clear("Could not open the log: " + reason(it))
                }
        }
    }

    private fun install(manager: VcsLogManager) {
        // One per review tab: a second UI under the same id is refused outright.
        val ui = manager.createLogUi(logId(reviewId), VcsLogFilterObject.EMPTY_COLLECTION)
        val owner = Disposer.newDisposable("ReviewRelay log")
        Disposer.register(this, owner)
        Disposer.register(owner, ui)
        current = owner
        logUi = ui

        configure(ui)
        restore(ui)
        watch(ui)
        badge(ui)
        hideWhatAReviewerCannotUse(ui)

        logHost.removeAll()
        logHost.add(VcsLogPanel(manager, ui), BorderLayout.CENTER)
        logHost.revalidate()
        logHost.repaint()
    }

    /** A property this build does not know is a hard error, not a no-op, so ask before setting. */
    private fun <T : Any> set(ui: MainVcsLogUi, property: VcsLogUiProperties.VcsLogUiProperty<T>, value: T) {
        runCatching { if (ui.properties.exists(property)) ui.properties.set(property, value) }
    }

    private fun configure(ui: MainVcsLogUi) {
        // By name rather than by importing git4idea's own property: that would make Git a hard
        // dependency, and the panel reads this at construction, which is when it matters.
        set(ui, SHOW_BRANCHES, true)
        set(ui, CommonUiProperties.SHOW_DETAILS, false)
        // Diffs belong in an editor tab, which is where the comments are.
        set(ui, CommonUiProperties.SHOW_DIFF_PREVIEW, false)
        // Merge commits rendered from their parents lose the key our comments resolve a path from.
        set(ui, MainVcsLogUiProperties.SHOW_CHANGES_FROM_PARENTS, false)
    }

    /**
      * Filtered to the remembered commits rather than hunted for in the whole pack: it shows exactly
      * what the review is of, and the log's own filter row is how you get back to everything.
      */
    private fun restore(ui: MainVcsLogUi) {
        val remembered = ReviewSessionService.getInstance(project).review(reviewId)?.selection.orEmpty()
        if (remembered.isEmpty()) return
        // Filtering refreshes the model, which clears the selection on the way: that empty must not
        // be written back over the commits being restored, or reopening loses them for good.
        restoring = true
        val roots = GitRepositoryManager.getInstance(project).repositories.map { it.root }
        if (roots.isEmpty()) return
        runCatching {
            // Every root: a hash belongs to one of them and the log matches on the pair, so a commit
            // remembered in the second repository of a project never came back.
            val commits = remembered.flatMap { hash -> roots.map { CommitId(HashImpl.build(hash.hash), it) } }
            ui.filterUi.setFilters(VcsLogFilterObject.collection(VcsLogFilterObject.fromCommits(commits)))
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && !gone) selectAll(ui)
            }
        }.onFailure { LOGGER.info("Could not restore the reviewed commits", it) }
    }

    private fun selectAll(ui: MainVcsLogUi) {
        val table = ui.table as? javax.swing.JTable ?: return
        if (table.rowCount > 0) table.setRowSelectionInterval(0, table.rowCount - 1)
        restoring = false
    }

    private fun watch(ui: MainVcsLogUi) {
        ui.table.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            val picked = runCatching { ui.table.selection.commits.map { Revision(it.hash.asString()) } }
                .getOrDefault(emptyList())
            if (restoring && picked.isEmpty()) return@addListSelectionListener
            ReviewSessionService.getInstance(project).rememberSelection(reviewId, picked)
            if (!showingLocal) describeSelection(ui)
        }
    }

    /** What the log has selected, in the words a new review is named after. */
    private fun describeSelection(ui: MainVcsLogUi) {
        val picked = runCatching { ui.table.selection.commits.map { Revision(it.hash.asString()) } }
            .getOrDefault(emptyList())
        // Newest first out of the log, so the pair reads oldest-then-newest like a range.
        onSelection(if (picked.size > 1) listOf(picked.last(), picked.first()) else picked)
    }

    /** The same badge the Commit view carries, on the tree the log already gives us. */
    private fun badge(ui: MainVcsLogUi) {
        val tree: ChangesTree = ui.changesBrowser.viewer
        wrap(tree)
        tree.addPropertyChangeListener("cellRenderer", object : PropertyChangeListener {
            override fun propertyChange(event: java.beans.PropertyChangeEvent) {
                if (tree.cellRenderer is CommentCountDecorator) return
                tree.removePropertyChangeListener("cellRenderer", this)
                wrap(tree)
                tree.addPropertyChangeListener("cellRenderer", this)
            }
        })
    }

    private fun wrap(tree: ChangesTree) {
        if (tree.cellRenderer is CommentCountDecorator) return
        tree.cellRenderer = CommentCountDecorator(tree.cellRenderer, project)
    }

    /**
     * Cherry-pick, reset and friends have no place in reading a review. The groups resolve by id from
     * the global ActionManager, so there is nothing to configure — the toolbars are hidden instead.
     */
    private fun hideWhatAReviewerCannotUse(ui: MainVcsLogUi) {
        // The right-hand toolbar is where Dvcs.Log.Toolbar puts Cherry-Pick, and it is the last one
        // in the row — found rather than indexed, since the row is a MigLayout we do not own.
        runCatching { toolbars(ui.toolbar).lastOrNull()?.isVisible = false }
        // The file list keeps its own row: Group By, Expand All, Collapse All and Show Diff are what
        // every other changes tree in the IDE has, and hiding it took them along with the cherry-pick.
    }

    private fun toolbars(root: Component): List<Component> = buildList {
        fun walk(component: Component) {
            if (component is ActionToolbar) add(component.component)
            if (component is Container) component.components.forEach { walk(it) }
        }
        walk(root)
    }

    private fun rebuild() = ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed || gone) return@invokeLater
        current?.let { Disposer.dispose(it) }
        current = null
        logUi = null
        build()
    }

    private fun clear(message: String) = ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        current?.let { Disposer.dispose(it) }
        current = null
        logUi = null
        logHost.removeAll()
        logHost.add(waiting(message), BorderLayout.CENTER)
        logHost.revalidate()
        logHost.repaint()
    }

    /**
     * Still coming, as against never arriving.
     *
     * Loading, no repository, torn down and failed all read as the same centred grey sentence, and
     * the log takes seconds to come up - so the ambiguous state was the common one.
     */
    private fun waiting(message: String, loading: Boolean = false): JComponent =
        JBLabel(message, if (loading) AnimatedIcon.Default.INSTANCE else null, JBLabel.CENTER).apply {
            foreground = UIUtil.getContextHelpForeground()
        }

    /** Toggled from the toolbar, so there is always a way back out of it. */
    fun showLocal(on: Boolean) {
        showingLocal = on
        (cards.layout as CardLayout).show(cards, if (on) LOCAL else LOG)
        if (!on) {
            // Back to the commits, and the label with it: it kept saying "uncommitted", so a review
            // started afterwards was named after work this pane is no longer showing.
            logUi?.let { describeSelection(it) }
            return
        }
        onDescribed("uncommitted")
        refreshLocal()
    }

    /**
     * The platform's own change list, not a git diff of our own.
     *
     * `git diff HEAD` can only see the disk, so a file being typed in did not reach this pane until
     * the document was flushed - where the IDE's Local Changes had it at once. This is the list that
     * view is drawn from, over every repository the project holds.
     */
    private fun refreshLocal() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && !gone && showingLocal) {
                local.setChangesToDisplay(ChangeListManager.getInstance(project).allChanges.toList())
            }
        }
    }

    override fun dispose() {
        gone = true
    }

    companion object {

        /** A deleted review leaves its log settings behind, one dead entry per review ever made. */
        fun forget(project: Project, reviewId: ReviewId) {
            runCatching {
                project.getService(VcsLogProjectTabsProperties::class.java)?.removeTab(logId(reviewId))
            }
        }

        private fun logId(reviewId: ReviewId) = "ReviewRelay-${reviewId.value}"

        private const val LOG = "log"
        private const val LOCAL = "local"

        private val LOGGER = Logger.getInstance(ReviewLogPane::class.java)

        /** git4idea's own key, matched by name so nothing here has to name a git4idea type. */
        private val SHOW_BRANCHES = VcsLogProjectTabsProperties.CustomBooleanTabProperty("Show.Git.Branches")
    }
}

/** The label for whatever the log has selected, which is what a review records it was started against. */
fun describeSelection(picked: List<Revision>): String = when {
    picked.isEmpty() -> ""
    picked.size == 1 -> "in " + picked.first().short
    else -> "across " + picked.first().short + ".." + picked.last().short
}
