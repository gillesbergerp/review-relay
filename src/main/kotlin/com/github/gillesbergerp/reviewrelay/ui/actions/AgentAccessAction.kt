package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.mcp.AgentClients
import com.github.gillesbergerp.reviewrelay.backend.mcp.McpEndpoint
import com.github.gillesbergerp.reviewrelay.backend.mcp.ReviewAccess
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.text
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class AgentAccessAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = AgentAccessDialog(e.project).show()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        // The one place a reviewer learns that an answer has no way back.
        val hint = ReviewAccess.setupHint
        e.presentation.icon = if (hint == null) AllIcons.General.Web else AllIcons.General.Warning
        e.presentation.description = hint ?: templatePresentation.description
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class AgentAccessDialog(project: Project?) : DialogWrapper(project, false) {

    private val url = McpEndpoint.url()

    /**
     * Held so the client list can be built again in place.
     *
     * The workflow this dialog is for is to leave it open, edit a config file and come back, and it
     * read every client's state once while building — so it always said what was true on opening.
     */
    private val body = JPanel(BorderLayout())

    init {
        title = "Agent Access"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent = body.apply { add(clients(), BorderLayout.CENTER) }

    private fun clients(): JComponent = panel {
        row("Address:") {
            textField()
                .text(url)
                .columns(COLUMNS_LARGE)
                .applyToComponent { isEditable = false }
            copyButton("the address", url)
        }
        row {
            comment(
                "Three tools live here: <code>review_comments</code> reads the published review, " +
                    "<code>review_reply</code> answers one comment, and <code>review_propose</code> " +
                    "puts a comment of the agent's own in front of you to add or dismiss. Restart " +
                    "the agent after configuring it."
            )
        }
        AgentClients.all(url).forEach { client ->
            group(client.name) {
                row {
                    // Iconned rather than three plain sentences in the same grey: which of the three
                    // a client is in is the whole point of the dialog.
                    icon(
                        when (client.state) {
                            AgentClients.State.CONFIGURED -> AllIcons.General.InspectionsOK
                            AgentClients.State.STALE -> AllIcons.General.Warning
                            AgentClients.State.ABSENT -> AllIcons.General.Information
                        }
                    )
                    label(
                        when (client.state) {
                            AgentClients.State.CONFIGURED -> "Configured."
                            AgentClients.State.STALE ->
                                "Points at a different address - replace it. A second IDE took the " +
                                    "usual port, so the address above is a new one."
                            AgentClients.State.ABSENT -> "Not configured."
                        }
                    )
                    copyButton("the ${client.name} snippet", client.snippet)
                }
                row("Config file:") {
                    cell(JBLabel(client.config.path).apply { setCopyable(true) })
                }
                // Shown, not only copied: it is going into the reviewer's own configuration file.
                collapsibleGroup("What gets pasted", indent = false) {
                    row {
                        cell(
                            JBTextArea(client.snippet).apply {
                                isEditable = false
                                font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
                                border = JBUI.Borders.empty(4)
                            }
                        ).align(Align.FILL)
                    }
                }
            }
        }
    }

    /** [what] rather than another "Copy": five of them in one dialog are one word to a screen reader. */
    private fun com.intellij.ui.dsl.builder.Row.copyButton(what: String, text: String) {
        button("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(text)) }
            .applyToComponent { accessibleContext.accessibleName = "Copy $what" }
    }

    private val refresh = object : AbstractAction("Refresh") {
        override fun actionPerformed(e: ActionEvent?) {
            body.removeAll()
            body.add(clients(), BorderLayout.CENTER)
            body.revalidate()
            body.repaint()
            pack()
        }
    }

    /** Close, not OK: nothing here is applied on the way out. */
    override fun createActions(): Array<Action> = arrayOf(refresh, okAction)
}
