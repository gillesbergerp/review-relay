package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.JComponent

/**
 * Alt and a type's own initial, wherever a comment is written.
 *
 * The box's text field is a multi-line editor, where Tab indents rather than moving focus, so
 * without these the only keyboard route to a type is typing its name into the comment.
 */
object CommentTypeKeys {

    /** Not a type's initial: F, C and Q are taken by the three, and T is for type. */
    const val CYCLE = 'T'

    /**
     * Registers the keys on [on], which should be the whole box rather than its text field: the
     * platform resolves them by walking up from whatever holds focus.
     */
    fun install(on: JComponent, current: () -> CommentType, pick: (CommentType) -> Unit) {
        CommentType.entries.forEach { type ->
            DumbAwareAction.create { pick(type) }.registerCustomShortcutSet(keys(type.label.first()), on)
        }
        DumbAwareAction.create { pick(current().next) }.registerCustomShortcutSet(keys(CYCLE), on)
    }

    /** Only the inline box honours the prefixes, so only it may promise them. */
    fun hint(withPrefixes: Boolean = false): String = listOfNotNull(
        "Fix: change it. Consider: your call. Question: answer it.",
        CommentType.entries.joinToString(", ", postfix = ".") { "${text(it.label.first())} for ${it.label}" },
        "${text(CYCLE)} steps through them.",
        "Or start the comment with fix:, consider: or question: and keep typing.".takeIf { withPrefixes },
    ).joinToString(" ")

    /**
     * The platform's own Alt+letter, which on macOS is Ctrl+Alt as well: plain Option and a letter
     * is a character there rather than a shortcut.
     */
    private fun keys(mnemonic: Char): ShortcutSet =
        KeymapUtil.getShortcutsForMnemonicChar(mnemonic) ?: CustomShortcutSet.EMPTY

    private fun text(mnemonic: Char): String = KeymapUtil.getFirstKeyboardShortcutText(keys(mnemonic))
}
