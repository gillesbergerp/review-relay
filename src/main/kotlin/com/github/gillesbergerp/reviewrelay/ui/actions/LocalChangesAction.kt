package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ReviewToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * Review the working tree instead of what the log has selected.
 *
 * A toggle rather than a row in the pane: the row it replaces could be entered but not left, and a
 * pressed button says which of the two you are looking at without having to read the file list.
 */
class LocalChangesAction : ToggleAction(), DumbAware {

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return ReviewToolWindowFactory.activeLog(project)?.showingLocal == true
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        ReviewToolWindowFactory.activeLog(project)?.showLocal(state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.project?.let { ReviewToolWindowFactory.activeLog(it) } != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
