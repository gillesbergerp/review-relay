package com.github.gillesbergerp.reviewrelay.ui.decoration

import com.github.gillesbergerp.reviewrelay.ui.editor.relativePath
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import java.awt.Component
import java.awt.Container
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

/** Shows how many review comments a changed file carries, next to its name in the Commit view. */
class CommentCountDecorator(
    private val delegate: TreeCellRenderer,
    private val project: Project
) : TreeCellRenderer {

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        val component = delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        if (value !is ChangesBrowserNode<*>) return component
        val file = resolveFile(value) ?: return component
        val service = ReviewSessionService.getInstance(project)
        // Open ones only: a badge in a commit view is read as what still needs looking at, and a
        // file whose comments are all resolved needs none.
        val count = service.currentSession.threads.count { it.file == file && it.isOpen }
        if (count == 0) return component

        findSimpleColoredComponent(component)?.let { insertCountAfterFilename(it, count) }
        return component
    }

    /** The delegate has already painted, so the fragments have to be read back out and replayed. */
    private fun insertCountAfterFilename(component: SimpleColoredComponent, count: Int) {
        val fragments = mutableListOf<Triple<String, SimpleTextAttributes, Any?>>()
        val it = component.iterator()
        while (it.hasNext()) {
            fragments.add(Triple(it.next(), it.textAttributes, it.tag))
        }
        // clear() drops these along with the text, and a decorated row was left without the file
        // type icon its undecorated neighbours keep.
        val icon = component.icon
        val gap = component.iconTextGap

        component.clear()
        component.icon = icon
        component.iconTextGap = gap
        fragments.forEachIndexed { index, (text, attributes, tag) ->
            component.append(text, attributes, tag)
            if (index == 0) component.append(" [$count]", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
        }
        if (fragments.isEmpty()) component.append(" [$count]", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
    }

    private fun resolveFile(node: ChangesBrowserNode<*>): ReviewedFile? {
        val change = node.userObject as? Change ?: return null
        val file = change.afterRevision?.file ?: change.beforeRevision?.file ?: return null
        return relativePath(project, file.path)
    }

    private fun findSimpleColoredComponent(component: Component): SimpleColoredComponent? {
        if (component is SimpleColoredComponent) return component
        if (component is Container) {
            for (child in component.components) {
                findSimpleColoredComponent(child)?.let { return it }
            }
        }
        return null
    }
}
