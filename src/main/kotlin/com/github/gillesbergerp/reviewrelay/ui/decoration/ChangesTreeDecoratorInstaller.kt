package com.github.gillesbergerp.reviewrelay.ui.decoration

import com.github.gillesbergerp.reviewrelay.review.service.ReviewThreadListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import java.awt.Component
import java.awt.Container
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

private const val COMMIT_TOOL_WINDOW = "Commit"

class ChangesTreeDecoratorInstaller : ProjectActivity {

    override suspend fun execute(project: Project) {
        val bus = project.messageBus.connect()

        bus.subscribe(ReviewThreadListener.TOPIC, object : ReviewThreadListener {
            override fun commentsChanged() {
                onCommitTrees(project) { it.repaint() }
            }
        })

        bus.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow.id == COMMIT_TOOL_WINDOW) install(project)
            }
        })

        install(project)
    }

    private fun install(project: Project) = onCommitTrees(project) { wrapRenderer(it, project) }

    private fun onCommitTrees(project: Project, action: (ChangesTree) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(COMMIT_TOOL_WINDOW)
                ?: return@invokeLater
            forEachChangesTree(toolWindow.contentManager.component, action)
        }
    }

    private fun forEachChangesTree(component: Component, action: (ChangesTree) -> Unit) {
        if (component is ChangesTree) action(component)
        if (component is Container) component.components.forEach { forEachChangesTree(it, action) }
    }

    /** The tree resets its renderer on rebuild, so the wrapper has to be put back each time. */
    private fun wrapRenderer(tree: ChangesTree, project: Project) {
        if (tree.cellRenderer is CommentCountDecorator) return
        tree.cellRenderer = CommentCountDecorator(tree.cellRenderer, project)

        tree.addPropertyChangeListener("cellRenderer", object : PropertyChangeListener {
            override fun propertyChange(evt: PropertyChangeEvent) {
                if (evt.newValue !is CommentCountDecorator) {
                    tree.removePropertyChangeListener("cellRenderer", this)
                    wrapRenderer(tree, project)
                }
            }
        })
    }
}
