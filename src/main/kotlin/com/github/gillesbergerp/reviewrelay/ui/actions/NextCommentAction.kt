package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.editor.ReviewNavigator
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class NextCommentAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewNavigator.step(e.project ?: return, forward = true)
    }

    override fun update(e: AnActionEvent) = enableWhenThereAreComments(e)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
