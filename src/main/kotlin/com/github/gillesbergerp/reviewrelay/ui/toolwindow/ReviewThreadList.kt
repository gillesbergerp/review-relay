package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentComponent
import com.github.gillesbergerp.reviewrelay.ui.editor.ProposalComponent
import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.review.model.ItemId
import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.ReviewItem
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.ui.CommentPalette
import com.github.gillesbergerp.reviewrelay.ui.MarkdownPane
import com.github.gillesbergerp.reviewrelay.ui.PROPOSAL_INSET
import com.github.gillesbergerp.reviewrelay.ui.RoundedPanel
import com.github.gillesbergerp.reviewrelay.ui.CommentSurface
import com.github.gillesbergerp.reviewrelay.ui.clickable
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.border.Border
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * The review, as the same cards the editor shows.
 *
 * A list cell renderer is a stamp rather than a component, so it cannot hold a reply box or a
 * working button. These are real components in a column, which is what lets one comment behave the
 * same whether it is read here or beside the code.
 */
class ReviewThreadList(private val project: Project) : JPanel(BorderLayout()) {

    var onSelected: (ReviewItem?) -> Unit = {}

    /**
     * Read is separate from selected, and a click reports it on release.
     *
     * Marking it read redraws the card, and a press whose component is gone by the time the release
     * lands is never delivered as a click - so the first press on a card with an answer on it was
     * spent on the dot rather than the button underneath the pointer.
     */
    var onRead: (ReviewItem?) -> Unit = {}

    /** A double click goes to the code, the way it did when these were list rows. */
    var onActivated: (ReviewItem) -> Unit = {}

    private val surface = CommentSurface.of(project)

    /**
     * The column is told the width it gets rather than asking for one.
     *
     * A viewport hands a view that does not track it the view's preferred width, which here is the
     * widest card's; with no horizontal scrollbar everything past the pane - the badge, the four
     * buttons - was laid out where it could not be reached.
     */
    private val column = object : JBPanel<JBPanel<*>>(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = surface.background
    }
    private val empty = JBLabel("No review comments yet").apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(12)
    }

    /** Says why the column is empty, which a filter can make true of a review that is not. */
    var emptyText: String
        get() = empty.text
        set(value) {
            empty.text = value
        }

    private val scroll = JBScrollPane(column).apply {
        border = JBUI.Borders.empty()
        viewport.background = surface.background
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    private var listed: List<ThreadRow> = emptyList()
    private var built = false

    /**
     * Keyed by [ItemId] rather than by thread: this map is also the keyboard order, so a proposal
     * that is not in it cannot be reached by the arrows at all.
     */
    private val rows = mutableMapOf<ItemId, Row>()

    /** Headings the reader has folded away, by label. */
    private val folded = mutableSetOf<String>()

    var selectedId: ItemId? = null
        private set

    init {
        add(scroll, BorderLayout.CENTER)

        // The column is cards rather than list rows, and it kept the mouse behaviour of a list
        // without the keyboard one: Enter, Ctrl+D and Delete were bound here to a panel that could
        // never hold focus, so none of them could ever fire.
        isFocusable = true
        // getAccessibleContext(), not the property: inside a Swing subclass the name
        // resolves to JComponent's own protected field, which is null until the getter
        // fills it - so this threw and the tool window came up with no tabs at all.
        getAccessibleContext().accessibleName = "Review comments"
        moveWith(KeyEvent.VK_DOWN, "nextComment") { step(1) }
        moveWith(KeyEvent.VK_UP, "previousComment") { step(-1) }
        moveWith(KeyEvent.VK_HOME, "firstComment") { rows.keys.firstOrNull()?.let(::select) }
        moveWith(KeyEvent.VK_END, "lastComment") { rows.keys.lastOrNull()?.let(::select) }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (selectedId == null) rows.keys.firstOrNull()?.let(::select)
            }
        })
    }

    private fun moveWith(key: Int, name: String, move: () -> Unit) {
        // Ancestor rather than focused: a card holds buttons of its own, and the arrows still belong
        // to the column while one of them has the focus.
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, 0), name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = move()
        })
    }

    private fun step(by: Int) {
        val ids = rows.keys.toList()
        if (ids.isEmpty()) return
        val at = ids.indexOf(selectedId)
        select(ids[if (at < 0) 0 else (at + by).coerceIn(0, ids.lastIndex)])
    }

    private val shown: List<ReviewItem>
        get() = listed.mapNotNull {
            when (it) {
                is ThreadRow.Group -> null
                is ThreadRow.Card -> it.thread
                is ThreadRow.Proposed -> it.proposal
            }
        }

    val selected: ReviewItem? get() = shown.firstOrNull { it.id == selectedId }

    /** What the actions that only ever act on a comment ask for. */
    val selectedThread: ReviewThread? get() = selected as? ReviewThread

    /**
     * Rebuilds the column, which every backend event would otherwise do for nothing: the agent
     * going busy and idle says nothing about the review, and a rebuild costs the scroll position.
     */
    fun show(now: List<ThreadRow>) {
        // An empty column is rebuilt regardless: it costs one label, and its text counts what the
        // filter is hiding, which changes while the list itself stays empty.
        if (built && now.isNotEmpty() && now == listed) return
        if (built && sameColumn(now)) return updateInPlace(now)
        listed = now
        build()
    }

    private fun build() {
        val at = scroll.viewport.viewPosition
        built = true
        column.removeAll()
        rows.clear()

        if (listed.isEmpty()) {
            column.add(empty)
        } else {
            var hidden = false
            listed.forEach { row ->
                when (row) {
                    is ThreadRow.Group -> {
                        hidden = row.label in folded
                        column.add(GroupHeading(row, hidden))
                    }

                    is ThreadRow.Card -> if (!hidden) {
                        val card = Row(row.thread)
                        rows[row.thread.id] = card
                        column.add(card)
                    }

                    is ThreadRow.Proposed -> if (!hidden) {
                        val card = Row(row.proposal)
                        rows[row.proposal.id] = card
                        column.add(card)
                    }
                }
            }
        }
        // Otherwise the last card stretches to fill whatever height is left.
        column.add(Box.createVerticalGlue())

        if (selectedId !in rows.keys) selectedId = rows.keys.firstOrNull()
        paintSelection()
        column.revalidate()
        column.repaint()
        // After the layout, or the viewport is put back against the height the column used to have.
        SwingUtilities.invokeLater { scroll.viewport.viewPosition = at }
    }

    /** Same rows in the same order, so only the cards that changed need redrawing. */
    private fun sameColumn(now: List<ThreadRow>): Boolean =
        now.size == listed.size && now.indices.all { identity(now[it]) == identity(listed[it]) }

    /** A heading is its words and its count; a card is what it is about, whatever that now says. */
    private fun identity(row: ThreadRow): Any = when (row) {
        is ThreadRow.Group -> row
        is ThreadRow.Card -> row.thread.id
        is ThreadRow.Proposed -> row.proposal.id
    }

    /**
     * Redraws the cards that moved and leaves the rest alone.
     *
     * A click whose press landed on a component that has since been replaced is never delivered.
     */
    private fun updateInPlace(now: List<ThreadRow>) {
        val before = shown.associateBy { it.id }
        now.forEach { row ->
            val item = when (row) {
                is ThreadRow.Group -> null
                is ThreadRow.Card -> row.thread
                is ThreadRow.Proposed -> row.proposal
            } ?: return@forEach
            if (item != before[item.id]) rows[item.id]?.update(item)
        }
        listed = now
    }

    private fun fold(label: String) {
        if (!folded.add(label)) folded.remove(label)
        build()
    }

    /** The heading a card sits under: the last one listed before it. */
    private fun groupOf(itemId: ItemId): String? = listed
        .take(listed.indexOfFirst { identity(it) == itemId }.takeIf { it >= 0 } ?: 0)
        .filterIsInstance<ThreadRow.Group>()
        .lastOrNull()
        ?.label

    /** Rebuilds one card, for a box opening or closing rather than the review changing. */
    fun refresh(itemId: ItemId) {
        rows[itemId]?.rebuild()
    }

    fun select(threadId: ItemId, read: Boolean = true) {
        // Going to a comment a fold is hiding opens the fold, rather than quietly doing nothing.
        if (threadId !in rows) {
            groupOf(threadId)?.takeIf { it in folded }?.let { fold(it) } ?: return
        }
        if (threadId !in rows) return
        selectedId = threadId
        paintSelection()
        rows[threadId]?.let { row ->
            // Only when it is out of sight: a card taller than the viewport can never be shown
            // whole, and asking would scroll to its top under whoever just clicked its foot.
            if (!scroll.viewport.viewRect.intersects(row.bounds)) {
                row.scrollRectToVisible(Rectangle(0, 0, row.width, row.height))
            }
        }
        onSelected(selected)
        if (read) onRead(selected)
    }

    private fun paintSelection() {
        rows.forEach { (id, row) -> row.setSelected(id == selectedId) }
    }

    /** Says once what every card under it would otherwise repeat, and folds them away when clicked. */
    private inner class GroupHeading(row: ThreadRow.Group, hidden: Boolean) : JPanel(BorderLayout()) {

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(10, 6, 2, 8)
            add(
                JBLabel(
                    row.label,
                    if (hidden) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown,
                    SwingConstants.LEFT,
                ).apply {
                    font = JBFont.small().asBold()
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.WEST,
            )
            add(
                JBLabel(row.count.toString()).apply {
                    font = JBFont.small()
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.EAST,
            )
            clickable()
            foldOnClick(this, row.label)

            // A heading is a control, not a caption: with a group folded it is the only way back to
            // the comments underneath it.
            isFocusable = true
            getAccessibleContext().accessibleName =
                "${row.label}, ${row.count} comments, ${if (hidden) "folded" else "showing"}"
            val toggle = "foldGroup"
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), toggle)
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), toggle)
            actionMap.put(toggle, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = fold(row.label)
            })
        }

        /** A heading is a panel of labels, so the click has to be offered to all of them. */
        private fun foldOnClick(component: Component, label: String) {
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = fold(label)
            })
            if (component is Container) component.components.forEach { foldOnClick(it, label) }
        }

        // The column's own furniture: BoxLayout hands the slack to whatever has no maximum, and a
        // plain panel would take the height the glue at the foot is there to absorb.
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private inner class Row(private var item: ReviewItem) : JPanel(BorderLayout()) {

        /** What the card was last drawn from, so a signal that changes none of it costs no layout. */
        private var painted: List<Any?>? = null

        private var card: RoundedPanel? = null
        private var selected = false
        private var hovered = false

        /** What this row's card is filled with at rest, which a proposal insets and a comment does not. */
        private var rest: Color = surface.background

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = frame(false)
            rebuild()
        }

        /** The same row showing something that has moved on, rather than a new row in its place. */
        fun update(now: ReviewItem) {
            item = now
            painted = null
            rebuild()
        }

        fun rebuild() = when (val current = item) {
            is ReviewThread -> rebuildComment(current)
            is Proposal -> rebuildProposal(current)
        }

        private fun rebuildComment(thread: ReviewThread) {
            val inline = InlineCommentManager.getInstance(project)
            val quoted = inline.reviewedCodeFor(thread, besideTheCode = false)
            val editing = inline.editingIn(thread.id)
            val replying = inline.isReplyingIn(thread.id)
            val draft = inline.draftIn(thread.id)
            val collapsed = inline.isCollapsed(thread)

            val state = listOf(editing, replying, draft, collapsed, quoted?.text, quoted?.moved)
            if (state == painted) return
            painted = state

            removeAll()
            val card = InlineCommentComponent(
                surface = CommentSurface.of(project),
                thread = thread,
                editing = editing,
                replying = replying,
                draftText = draft,
                collapsed = collapsed,
                reviewedCode = quoted?.text,
                reviewedCodeMoved = quoted?.moved == true,
                currentCode = inline.currentText(thread),
                showLocation = true,
                actions = inline.actionsFor(thread),
            )
            // The card has always told whoever holds it about the pointer; in the column nothing was
            // listening, so a card gave no sign it could be clicked at all.
            rest = surface.background
            card.onHover = { over ->
                hovered = over
                fillCard()
                // And in the editor, if the file is already open there. Nothing is opened for it.
                inline.markLines(thread, over)
            }
            settle(card)
        }

        private fun rebuildProposal(proposal: Proposal) {
            val inline = InlineCommentManager.getInstance(project)
            val quoted = inline.reviewedCodeFor(proposal, besideTheCode = false)
            val editing = inline.isEditing(proposal.id)
            val besideTheCode = inline.isEditingBesideTheCode(proposal.id)
            val draft = inline.draftIn(proposal.id)
            val collapsed = inline.isCollapsed(proposal)

            val state = listOf(editing, besideTheCode, draft, collapsed, quoted?.text, quoted?.moved)
            if (state == painted) return
            painted = state

            removeAll()
            val card = ProposalComponent(
                surface = CommentSurface.of(project),
                proposal = proposal,
                editing = editing,
                openedBesideTheCode = besideTheCode,
                draftText = draft,
                collapsed = collapsed,
                reviewedCode = quoted?.text,
                reviewedCodeMoved = quoted?.moved == true,
                showLocation = true,
                actions = inline.actionsFor(proposal),
            )
            rest = CommentPalette.shade(surface.background, PROPOSAL_INSET)
            card.onHover = { over ->
                hovered = over
                fillCard()
                inline.markLines(proposal, over)
            }
            settle(card)
        }

        private fun settle(card: RoundedPanel) {
            add(card, BorderLayout.CENTER)
            selectOnClick(card)
            this.card = card
            // A fresh card knows nothing of the selection, and this also runs from refresh(id).
            setSelected(selected)
            revalidate()
            repaint()
        }

        fun setSelected(on: Boolean) {
            selected = on
            border = frame(on)
            // Set on the card rather than built into it: rebuilding on a mouse press replaces the
            // component under the pointer, which is what made acting on a card take two clicks.
            card?.line = if (on) SELECTION else JBColor.border()
            fillCard()
            repaint()
        }

        /**
         * The fill answers to selection as well as the pointer.
         *
         * Only hover set it before, so stepping through the comments from the keyboard left the
         * shading on whatever the mouse happened to be resting on while the selection moved away.
         */
        private fun fillCard() {
            card?.fill = if (selected || hovered) CommentPalette.shade(rest, HOVER) else rest
        }

        /**
         * A stripe rather than a wash: the card paints its own surface, so filling the row behind it
         * only ever showed as a frame around the outside.
         */
        private fun frame(on: Boolean): Border? = JBUI.Borders.compound(
            if (on) {
                JBUI.Borders.customLine(SELECTION, 0, STRIPE, 0, 0)
            } else {
                JBUI.Borders.emptyLeft(STRIPE)
            },
            JBUI.Borders.empty(3, 6),
        )

        /** A card is a tree of labels, so the click has to be offered to all of them, not the top. */
        private fun selectOnClick(component: Component) {
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    // Focus follows the click, so the keys bound to this column work from here on -
                    // except off prose, which has just taken focus to put a caret where the drag
                    // starts, and would otherwise lose it again before the selection began.
                    if (e.component !is MarkdownPane) this@ReviewThreadList.requestFocusInWindow()
                    select(item.id, read = false)
                    if (e.clickCount == 2) onActivated(item)
                }

                override fun mouseReleased(e: MouseEvent) {
                    // A click on prose that selected nothing was aimed at the comment rather than
                    // at its text, so the column takes the focus back. It has to: the keys that
                    // step through the comments are bound on the column, and a focused pane sits
                    // inside the scroll pane, which claims the arrows first and merely scrolls.
                    // A drag that did select something keeps focus, so it can still be copied.
                    val prose = e.component as? MarkdownPane
                    if (prose != null && prose.selectionStart == prose.selectionEnd) {
                        this@ReviewThreadList.requestFocusInWindow()
                    }
                    onRead(item)
                }
            })
            if (component is Container) component.components.forEach { selectOnClick(it) }
        }

        // A card wants all the width it can get, and none of the height it is not using.
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private companion object {
        val STRIPE: Int get() = JBUI.scale(2)

        /** One colour, one meaning: the card you are on. Unread is the dot in the header. */
        val SELECTION: JBColor get() = JBColor.namedColor("Focus.borderColor", JBColor.blue)

        /** Enough to see the pointer is on a card. Three percent was at the edge of perception. */
        const val HOVER = 0.07f
    }

    /** The column scrolls, so it must never claim the width of its widest card as a minimum. */
    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(120), super.getMinimumSize().height)
}
