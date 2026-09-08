package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ThreadFilter
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ReviewToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware

/** Which of a review's comments the pane lists. A long review is mostly work already dealt with. */
class ThreadFilterMenu : DefaultActionGroup(), DumbAware {

    // The choices are built here rather than declared: one per ThreadFilter, always.
    init {
        ThreadFilter.entries.forEach { add(Choice(it)) }
    }

    /** A filtered list looks exactly like a short one, so the button holds itself down while it is. */
    override fun update(e: AnActionEvent) {
        val filter = e.project?.let { ReviewToolWindowFactory.activePanel(it) }?.filter ?: ThreadFilter.ALL
        e.presentation.text = if (filter == ThreadFilter.ALL) "Show" else "Show: ${filter.label}"
        Toggleable.setSelected(e.presentation, filter != ThreadFilter.ALL)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private class Choice(private val filter: ThreadFilter) :
        ToggleAction(filter.label, filter.hint, null), DumbAware {

        override fun isSelected(e: AnActionEvent): Boolean =
            e.project?.let { ReviewToolWindowFactory.activePanel(it) }?.filter == filter

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            e.project?.let { ReviewToolWindowFactory.activePanel(it)?.filter = filter }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
