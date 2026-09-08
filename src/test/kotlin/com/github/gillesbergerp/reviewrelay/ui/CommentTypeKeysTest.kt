package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import java.awt.event.KeyEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommentTypeKeysTest {

    /** A fourth type sharing an initial would silently take a key, or lose one to the step. */
    @Test
    fun `each type has an initial of its own, and none of them is the stepping key`() {
        val initials = CommentType.entries.map { it.label.first().uppercaseChar() }

        assertEquals(CommentType.entries.size, initials.toSet().size)
        assertFalse(CommentTypeKeys.CYCLE.uppercaseChar() in initials)
    }

    /**
     * The platform answers with nothing for a character it has no code for, which would leave the
     * box registered against an empty set and every key silently dead.
     */
    @Test
    fun `every type gets a key of its own on the box, and the step gets one too`() {
        val panel = JPanel()

        CommentTypeKeys.install(panel, { CommentType.FIX }, {})

        val pressed = ActionUtil.getActions(panel).map { action ->
            action.shortcutSet.shortcuts
                .filterIsInstance<KeyboardShortcut>()
                .map { it.firstKeyStroke.keyCode }
                .toSet()
        }
        val expected = (CommentType.entries.map { it.label.first() } + CommentTypeKeys.CYCLE)
            .map { setOf(KeyEvent.getExtendedKeyCodeForChar(it.code)) }

        assertEquals(expected, pressed)
    }
}
