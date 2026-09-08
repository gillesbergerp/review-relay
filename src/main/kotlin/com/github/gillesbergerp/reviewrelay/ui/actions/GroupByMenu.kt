package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ReviewToolWindowFactory
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ThreadGrouping
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware

/** What the column puts a heading between. The sort is unchanged; this only names its runs. */
class GroupByMenu : DefaultActionGroup(), DumbAware {

    // The choices are built here rather than declared: one per ThreadGrouping, always.
    init {
        ThreadGrouping.entries.forEach { add(Choice(it)) }
    }

    override fun update(e: AnActionEvent) {
        val grouping = e.project?.let { ReviewToolWindowFactory.activePanel(it) }?.grouping
            ?: ThreadGrouping.NONE
        e.presentation.text = if (grouping == ThreadGrouping.NONE) "Group By" else "Group By: ${grouping.label}"
        Toggleable.setSelected(e.presentation, grouping != ThreadGrouping.NONE)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private class Choice(private val grouping: ThreadGrouping) :
        ToggleAction(grouping.label, grouping.hint, null), DumbAware {

        override fun isSelected(e: AnActionEvent): Boolean =
            e.project?.let { ReviewToolWindowFactory.activePanel(it) }?.grouping == grouping

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            e.project?.let { ReviewToolWindowFactory.activePanel(it)?.grouping = grouping }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
