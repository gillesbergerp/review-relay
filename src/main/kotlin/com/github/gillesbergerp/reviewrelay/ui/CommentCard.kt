package com.github.gillesbergerp.reviewrelay.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import javax.swing.JPanel

/** The rounded surfaces the review comment is built from, painted rather than bordered. */
object CommentCard {

    val RADIUS: Int get() = JBUI.scale(8)
}

/**
 * A panel with rounded corners and a hairline.
 *
 * Swing borders are rectangles, so the corner has to be painted: the background is filled as a
 * round rectangle and the line drawn on the same shape.
 */
open class RoundedPanel(
    layout: LayoutManager,
    fill: Color,
    line: Color? = null,
    private val radius: Int = CommentCard.RADIUS,
) : JPanel(layout) {

    /** Settable for the same reason as [line]: a card answers the pointer without being rebuilt. */
    var fill: Color = fill
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /**
     * Settable, so a list can mark the card it has selected without building a new one: replacing
     * the component under the pointer is what made acting on a card take two clicks.
     */
    var line: Color? = line
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        isOpaque = false
        // These sit inside an editor, whose cursor is a caret, and a component with no cursor of its
        // own inherits its parent's - so every control on a card claimed to be text you could type
        // in. The field inside the box sets its own caret back; links and buttons their hand.
        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            line?.let {
                g2.color = it
                g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * An answer nobody has looked at yet, as a state rather than another word in a crowded header.
 *
 * A panel rather than a bare JComponent: only the subclasses that make one answer
 * getAccessibleContext() with anything but null, and this dot is the whole unread signal, so it has
 * to be able to carry a name.
 */
class Dot(private val color: Color) : JPanel(null) {

    private val size: Int get() = JBUI.scale(6)

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(size, size)

    override fun getMaximumSize(): Dimension = preferredSize

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(0, 0, size, size)
        } finally {
            g2.dispose()
        }
    }
}
