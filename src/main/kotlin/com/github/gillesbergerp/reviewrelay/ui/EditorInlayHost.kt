package com.github.gillesbergerp.reviewrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Puts a Swing component into an editor as a block inlay.
 *
 * Embedded components do not follow the editor's width on their own, so each one is wrapped in a
 * panel that reports the current text width and a single listener on the viewport re-lays them out
 * when the editor is resized. Without that the components keep their first width and clip.
 */
object EditorInlayHost {

    private val WATCHER = Key.create<WidthWatcher>("reviewrelay.inlayWidthWatcher")

    /** The editors an inlay here has to be matched in, which is the other side of a diff. */
    private val TWINS = Key.create<List<Editor>>("reviewrelay.inlayTwins")

    /**
     * Says a comment on [commented] has to be answered by empty space in [others].
     *
     * Only where one side carries the comments: when both do, both grow by the same amount at the
     * same line and are already aligned.
     */
    fun matchHeightsAcross(commented: Editor, others: List<Editor>) {
        commented.putUserData(TWINS, others.takeIf { it.isNotEmpty() })
    }

    /** The inlay plus the panel holding it, so its contents can be swapped without a new inlay. */
    class Handle(val inlay: Inlay<*>, private val wrapper: WidthConstrainedPanel) {

        val editor: Editor get() = inlay.editor
        val isValid: Boolean get() = inlay.isValid

        /** Replaces what is shown. Rebuilding the inlay instead made the editor flash. */
        fun show(component: JComponent) {
            wrapper.removeAll()
            wrapper.add(component)
            wrapper.revalidate()
            inlay.update()
            wrapper.matchSpacers()
            wrapper.repaint()
        }
    }

    /**
     * The offset a card at [line] hangs from.
     *
     * A document with no lines at all - the other side of an added file, an empty file - has no line
     * to take the end of, and `coerceIn(0, -1)` throws rather than clamping.
     */
    fun endOf(document: Document, line: Int): Int =
        if (document.lineCount == 0) 0 else document.getLineEndOffset(line.coerceIn(0, document.lineCount - 1))

    fun add(editor: Editor, offset: Int, component: JComponent): Handle? {
        val editorEx = editor as? EditorEx ?: return null
        val watcher = watcherFor(editorEx)
        val wrapper = WidthConstrainedPanel(component, watcher)

        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx,
            wrapper,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                true,
                false,
                0,
                offset,
            ),
        )
        if (inlay == null) {
            watcher.forget(wrapper)
            return null
        }
        watcher.remember(wrapper)
        wrapper.spacers = spacersFor(editorEx, inlay, offset)
        return Handle(inlay, wrapper)
    }

    /**
     * Empty space of the same height at the same line on the other side of a diff.
     *
     * A block inlay makes its own side taller, and the two panes are read against each other line
     * by line: with a comment on one side and nothing on the other, everything below it sat
     * opposite the wrong code.
     */
    private fun spacersFor(editor: Editor, source: Inlay<*>, offset: Int): List<Inlay<*>> {
        val twins = editor.getUserData(TWINS).orEmpty().filterNot { it.isDisposed }
        if (twins.isEmpty()) return emptyList()
        val line = editor.document.getLineNumber(offset)
        return twins.mapNotNull { twin ->
            val at = endOf(twin.document, line)
            twin.inlayModel.addBlockElement(
                at,
                true,
                false,
                0,
                object : EditorCustomElementRenderer {
                    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 0

                    /** Read live, so the space follows the card as it is written in and folded. */
                    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
                        if (source.isValid) source.heightInPixels else 0
                },
            )?.also { spacer ->
                // Guarded rather than registered directly: the spacer belongs to the other editor's
                // inlay model, which disposes it when that viewer closes, and disposing it a second
                // time through this parent is an error.
                Disposer.register(source, Disposable { if (spacer.isValid) Disposer.dispose(spacer) })
            }
        }
    }

    private fun watcherFor(editor: EditorEx): WidthWatcher {
        editor.getUserData(WATCHER)?.let { return it }
        val watcher = WidthWatcher(editor)
        editor.putUserData(WATCHER, watcher)
        editor.scrollPane.viewport.addComponentListener(watcher)
        return watcher
    }

    internal class WidthWatcher(private val editor: EditorEx) : ComponentAdapter() {

        private val panels: MutableSet<WidthConstrainedPanel> =
            Collections.newSetFromMap(WeakHashMap())

        fun remember(panel: WidthConstrainedPanel) {
            panels.add(panel)
        }

        fun forget(panel: WidthConstrainedPanel) {
            panels.remove(panel)
        }

        fun textWidth(): Int {
            val scrollPane = editor.scrollPane
            val visible = scrollPane.viewport.width
            val verticalBar = if (scrollPane.verticalScrollBarPolicy == ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS) {
                scrollPane.verticalScrollBar.width
            } else {
                0
            }
            return (visible - verticalBar - editor.gutterComponentEx.width - JBUI.scale(16)).coerceAtLeast(JBUI.scale(200))
        }

        override fun componentResized(e: ComponentEvent) {
            panels.forEach { it.refreshWidth() }
        }
    }

    private val INSET: Int get() = JBUI.scale(6)

    class WidthConstrainedPanel internal constructor(
        content: JComponent,
        private val watcher: WidthWatcher,
    ) : JPanel(null) {

        init {
            isOpaque = false
            add(content)
        }

        /** The empty space standing in for this on the other side of a diff, if there is one. */
        internal var spacers: List<Inlay<*>> = emptyList()

        private val content: JComponent get() = getComponent(0) as JComponent

        /** The height is read from the source inlay, so the space only moves when it is asked to. */
        internal fun matchSpacers() = spacers.filter { it.isValid }.forEach { it.update() }

        fun refreshWidth() {
            revalidate()
            repaint()
            matchSpacers()
        }

        private var measuredAt = -1
        private var measured = -1

        /** Swing asks for a preferred size far more often than the answer changes. */
        override fun invalidate() {
            measuredAt = -1
            super.invalidate()
        }

        override fun getPreferredSize(): Dimension {
            val width = (watcher.textWidth() - INSET * 2).coerceAtLeast(JBUI.scale(120))
            if (width != measuredAt) {
                content.setSize(width, Short.MAX_VALUE.toInt())
                // Laid out before it is measured: setSize alone leaves every child at whatever width
                // it last had, so wrapping prose answered with the height it needed at that width -
                // on a first draw, none. Once per width: the card holds real editors, and laying
                // them all out on every question cost a second per keystroke.
                layOut(content)
                measuredAt = width
                measured = content.preferredSize.height
            }
            return Dimension(width + INSET * 2, measured)
        }

        private fun layOut(component: Component) {
            if (component !is Container) return
            component.doLayout()
            component.components.forEach { layOut(it) }
        }

        /** Held off the edges: a comment is a note on the code, not another pane of it. */
        override fun doLayout() {
            content.bounds = Rectangle(INSET, 0, width - INSET * 2, height)
        }
    }
}
