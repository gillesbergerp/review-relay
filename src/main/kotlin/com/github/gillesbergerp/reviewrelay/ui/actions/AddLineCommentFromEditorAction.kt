package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.ui.editor.commentPath
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.actionSystem.CommonDataKeys

class AddLineCommentFromEditorAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val caretLine = editor.document.getLineNumber(editor.caretModel.offset) + 1
        InlineCommentManager.getInstance(project).newComment(editor, caretLine)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            project != null && editor != null && commentPath(editor) != null
    }
}
