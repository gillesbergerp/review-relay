package com.github.gillesbergerp.reviewrelay.ui

import java.awt.Component
import java.awt.Cursor

/**
 * The hand, for the painted things that have no other way of saying they can be pressed.
 *
 * Not for buttons or combo boxes: nothing else in the IDE shows a hand over one, so the send button
 * read as a hyperlink. They say it by looking like themselves.
 */
internal fun <T : Component> T.clickable(): T = apply {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
}
