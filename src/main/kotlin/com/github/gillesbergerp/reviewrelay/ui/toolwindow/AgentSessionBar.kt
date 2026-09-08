package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.backend.AgentBackend
import com.github.gillesbergerp.reviewrelay.backend.AgentSession
import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendStateListener
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.review.model.AgentConversation
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.ui.clickable
import com.github.gillesbergerp.reviewrelay.util.Directory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.DateFormatUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel

/**
 * Which conversation the review goes to, and how the backend is doing.
 *
 * A backend without sessions has no combo at all rather than an empty one, and one that cannot
 * observe the agent says nothing about it rather than guessing that it is idle.
 */
class AgentSessionBar(
    private val project: Project,
    private val reviewId: ReviewId,
) : JPanel(BorderLayout(8, 0)), Disposable {

    private val service = BackendService.getInstance(project)
    private val reviews = ReviewSessionService.getInstance(project)

    /** This tab's review, never whichever one is in front: each bar drives its own. */
    private val backend: AgentBackend get() = service.backendOf(reviewId)
    private val basePath = project.basePath
    private val model = DefaultComboBoxModel<AgentSession>()
    private val combo = ComboBox(model).apply {
        // The one control here that clips first, and the only one that had nothing to say when it did.
        toolTipText = "The conversation this review is worked in"
        accessibleContext.accessibleName = "Agent session"
    }
    private val backends = ComboBox(service.available.map { it.displayName }.toTypedArray()).apply {
        toolTipText = "Which agent this review goes to"
        isVisible = service.available.size > 1
    }
    private val status = JBLabel()
    private var updating = false

    private companion object {
        /** East takes its preferred width, so an unbounded status eats the session title beside it. */
        const val PROBLEM_CHARS = 48
    }

    init {
        border = JBUI.Borders.empty(2, 4)
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val session = value as? AgentSession
                val text = if (session == null) {
                    emptyLabel()
                } else {
                    // Spaces do not line anything up in a proportional font; the middot is what the
                    // card footer already uses for a list of facts about one thing.
                    val elsewhere = when (session.directory) {
                        null, Directory.of(basePath) -> null
                        else -> "in ${session.directory.name}"
                    }
                    listOfNotNull(
                        session.title,
                        DateFormatUtil.formatPrettyDateTime(session.updatedAt.toEpochMilli()),
                        elsewhere,
                    ).joinToString(" · ")
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        combo.addActionListener {
            if (updating) return@addActionListener
            val picked = (combo.selectedItem as? AgentSession)?.id
            reviews.rememberConversation(reviewId, AgentConversation(backend.id, picked))
            service.stateChanged()
        }
        backends.addActionListener {
            if (updating) return@addActionListener
            val chosen = service.available.firstOrNull { it.displayName == backends.selectedItem } ?: return@addActionListener
            // No session comes with it: the one being left is that backend's and means nothing here.
            reviews.rememberConversation(reviewId, AgentConversation(chosen.id))
            service.stateChanged()
            service.refreshAsync()
        }

        add(backends, BorderLayout.WEST)
        add(combo, BorderLayout.CENTER)
        add(status, BorderLayout.EAST)

        project.messageBus.connect(this).subscribe(
            BackendStateListener.TOPIC,
            object : BackendStateListener {
                override fun backendStateChanged() = refresh()
            }
        )
        service.refreshAsync()
    }

    /** Do not point at a button that is not there, and name it rather than draw it. */
    private fun emptyLabel(): String =
        if (backend.sessions?.creation != null) "No sessions here yet - use New Session Here" else "No sessions"

    fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            updating = true
            try {
                val remembered = reviews.review(reviewId)?.conversation
                backends.selectedItem = backend.displayName
                val sessions = backend.sessions
                combo.isVisible = sessions != null
                if (sessions != null) {
                    // Nothing is pushed into the backend: it holds no selection to overwrite. A
                    // review that has not chosen takes what this backend would offer, and keeps it.
                    val session = remembered?.takeIf { it.backendId == backend.id }?.sessionId
                        ?: sessions.preferred?.also {
                            reviews.rememberConversation(reviewId, AgentConversation(backend.id, it))
                        }

                    val listed = sessions.sessions
                    model.removeAllElements()
                    listed.forEach { model.addElement(it) }
                    combo.selectedItem = listed.firstOrNull { it.id == session }
                }
                showStatus()
            } finally {
                updating = false
            }
        }
    }

    /**
     * A problem is painted as one and never takes the rest of the line with it.
     *
     * It used to return early in the muted help colour, so a connection failure read like a hint,
     * and the moment a reviewer most needs to know which session and where was the one moment the
     * bar stopped saying it.
     */
    private fun showStatus() {
        val problem = backend.status.problem
        status.text = problem?.let { shortened(it) } ?: statusText()
        status.foreground = if (problem != null) JBColor.RED else UIUtil.getContextHelpForeground()
        status.toolTipText = problem ?: statusText().takeIf { it.isNotBlank() }
    }

    /** The bar is the narrowest thing in the window; the whole of it is on the tooltip. */
    private fun shortened(problem: String): String =
        if (problem.length <= PROBLEM_CHARS) problem else problem.take(PROBLEM_CHARS).trimEnd() + "..."

    private fun statusText(): String {
        val agent = backend
        if (service.isRunning(BackendTask.CREATE, service.creating(agent))) return "creating the session..."
        if (service.isRunning(BackendTask.CONNECT) && agent.status.connecting) return "connecting..."

        // Only a backend that has actually observed the agent may say whether it is working.
        val activity = agent.activity
        val session = reviews.review(reviewId)?.conversation?.sessionId
        val state = if (activity != null && activity.observed) {
            activity.waitingFor(session) ?: if (activity.isBusy(session)) "working" else "idle"
        } else {
            null
        }
        val borrowed = agent.sessions?.borrowedFrom(session)?.let { "in ${it.name}" }
        // Only what has something to say, so a backend with no endpoint to name has no stray dash.
        return listOfNotNull(state, agent.status.label.takeIf { it.isNotBlank() }, borrowed)
            .joinToString(" · ")
    }


    override fun dispose() = Unit
}
