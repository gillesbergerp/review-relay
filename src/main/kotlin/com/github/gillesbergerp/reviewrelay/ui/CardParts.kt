package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.review.model.DiffLine
import com.github.gillesbergerp.reviewrelay.review.model.DiffLineKind
import com.github.gillesbergerp.reviewrelay.review.model.ReviewItem
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * The pieces a comment and a proposal are both built from.
 *
 * Shared rather than copied because the two are meant to read as the same kind of thing standing at
 * different distances from the review: a difference in weight, not in shape.
 */

internal val CARD_RULE: Int get() = JBUI.scale(2)
internal val CARD_GUTTER: Int get() = JBUI.scale(10)

/** Enough to recognise which code this is. The file itself says the rest. */
internal const val QUOTE_LINES = 3

/** How far a proposal sits under a comment: an inset surface, the one weight that is not a colour. */
internal const val PROPOSAL_INSET = 0.04f

internal fun muted(text: String): JBLabel = JBLabel(text).apply {
    foreground = UIUtil.getContextHelpForeground()
    font = JBFont.small()
}

internal fun markdown(surface: CommentSurface, prose: String): JComponent = MarkdownPane(
    markdown = prose,
    font = UIUtil.getLabelFont(),
    foreground = surface.foreground,
    codeBackground = CommentPalette.shade(surface.background, 0.06f),
).apply { alignmentX = JComponent.LEFT_ALIGNMENT }

/** Code to read as code: boxed, in the file's own language, with nothing to apply. */
internal fun codeBlock(surface: CommentSurface, item: ReviewItem, code: String): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(JBUI.scale(4))
        add(
            SuggestionDiffView(
                project = surface.project,
                fileType = FileTypeManager.getInstance().getFileTypeByFileName(item.file.name),
                rows = code.lines().map { DiffLine(DiffLineKind.CONTEXT, it) },
            ).apply { border = JBUI.Borders.customLine(JBColor.border(), 1) },
            BorderLayout.CENTER,
        )
    }

/**
 * The code something was written against, quoted rather than boxed: it is there to read, not to act
 * on. Said to be from then only when the file no longer agrees.
 *
 * [cap] cuts a long quote down where the code is not on screen; beside the code the quote is only
 * ever shown because the file has moved on, and then the whole of it is the point.
 */
internal fun quotedCode(
    surface: CommentSurface,
    item: ReviewItem,
    code: String,
    moved: Boolean,
    cap: Int?,
): JComponent = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
    isOpaque = false
    alignmentX = JComponent.LEFT_ALIGNMENT
    // Indented to the gutter every message shares, so the rule sits under the prose it belongs to
    // rather than out on its own left edge.
    border = JBUI.Borders.compound(
        JBUI.Borders.empty(3, CARD_GUTTER, 0, 0),
        JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, CARD_RULE, 0, 0),
            JBUI.Borders.empty(1, CARD_GUTTER - CARD_RULE, 3, 0),
        ),
    )
    if (moved) add(muted("When commented:"), BorderLayout.NORTH)
    val all = code.lines()
    val shown = if (cap != null) all.take(cap) else all
    add(
        SuggestionDiffView(
            project = surface.project,
            fileType = FileTypeManager.getInstance().getFileTypeByFileName(item.file.name),
            rows = shown.map { DiffLine(DiffLineKind.CONTEXT, it) },
        ),
        BorderLayout.CENTER,
    )
    if (shown.size < all.size) add(muted("+${all.size - shown.size} more lines"), BorderLayout.SOUTH)
}

internal fun cardRow(fill: JPanel.() -> Unit): JComponent = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    fill()
}

/** Focusable and keyed: an InplaceButton answers the mouse alone, and these are a card's only actions. */
internal fun iconButton(icon: Icon, tooltip: String, action: () -> Unit): JComponent =
    InplaceButton(tooltip, icon) { action() }.clickable().apply {
        isFocusable = true
        accessibleContext.accessibleName = tooltip
        val press = "press"
        listOf(KeyEvent.VK_SPACE, KeyEvent.VK_ENTER).forEach {
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(it, 0), press)
        }
        actionMap.put(press, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

/**
 * The file, not the path to it.
 *
 * The directories above it are the same on every card in a review and are only ever read to confirm
 * a guess, which is what the tooltip is for.
 */
internal fun whereLabel(item: ReviewItem): String =
    item.lineLabel().takeIf { it.isNotEmpty() }
        ?.let { "${item.file.name}:$it" }
        ?: "${item.file.name} · whole file"

/** What the header shortened away, and whatever names the item to whoever has to talk about it. */
internal fun whereTooltip(item: ReviewItem, footer: String?): String = buildString {
    append("<html>").append(item.file.path)
    item.lineLabel().takeIf { it.isNotEmpty() }?.let { append(':').append(it) }
    footer?.let { append("<br>").append(it) }
    append("</html>")
}

/** Blank for something written before this was recorded, rather than a date in 1970. */
internal fun age(at: Instant?): String? = at
    ?.takeIf { it.isAfter(Instant.EPOCH) }
    ?.let { DateFormatUtil.formatPrettyDateTime(it.toEpochMilli()).lowercase() }

/**
 * A card is a tree of labels, and Swing delivers the pointer to the deepest of them, so every child
 * has to report. Leaving a child for its neighbour is not leaving the card.
 */
internal fun watchHover(card: JComponent, onHover: (Boolean) -> Unit) {
    fun watch(component: Component) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = onHover(true)

            override fun mouseExited(e: MouseEvent) {
                val over = SwingUtilities.convertPoint(e.component, e.point, card)
                if (!card.contains(over)) onHover(false)
            }
        })
        if (component is Container) component.components.forEach { watch(it) }
    }
    watch(card)
}
