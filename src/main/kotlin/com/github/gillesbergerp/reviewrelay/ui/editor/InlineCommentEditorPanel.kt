package com.github.gillesbergerp.reviewrelay.ui.editor

import com.github.gillesbergerp.reviewrelay.review.model.CommentDraft
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.model.TypePrefix
import com.github.gillesbergerp.reviewrelay.ui.CommentCard
import com.github.gillesbergerp.reviewrelay.ui.CommentTypeKeys
import com.github.gillesbergerp.reviewrelay.ui.RoundedPanel
import com.github.gillesbergerp.reviewrelay.ui.clickable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The comment input, laid out like a review comment box: the text first, the type and the actions
 * on one line under it.
 */
class InlineCommentEditorPanel(
    private val project: Project?,
    initialText: String = "",
    initialType: CommentType = CommentType.FIX,
    private val initialBase: String? = null,
    private val suggestionSeed: String?,
    private val showType: Boolean = true,
    private val saveLabel: String = "Save",
    placeholder: String? = null,
    /** The surface the box sits on, which is the card's rather than the panel background's. */
    private val embeddedOn: Color,
    private val focusOnShow: Boolean = false,
    private val onSave: (CommentDraft) -> Unit,
) : RoundedPanel(
    BorderLayout(0, JBUI.scale(4)),
    embeddedOn,
    JBColor.border(),
    CommentCard.RADIUS,
) {

    private val typeCombo = ComboBox(CommentType.entries.toTypedArray()).apply {
        selectedItem = initialType
        accessibleContext.accessibleName = "Comment type"
        toolTipText = CommentTypeKeys.hint(withPrefixes = true)
    }

    private val type: CommentType get() = typeCombo.selectedItem as CommentType

    /** A real IDE editor, so the box behaves like every other text input in the IDE. */
    val editorField = EditorTextField(initialText, project, commentFileType()).apply {
        setOneLineMode(false)
        setPlaceholder(placeholder ?: "What should change here? Markdown; type ``` to suggest an edit.")
        addSettingsProvider { editor ->
            // The text and its placeholder start hard against the frame otherwise.
            editor.setBorder(JBUI.Borders.empty(3, 5))
            editor.settings.isUseSoftWraps = true
            editor.settings.isCaretRowShown = false
            editor.settings.additionalLinesCount = 0
            editor.settings.additionalColumnsCount = 0
        }
        // A minimum rather than the size: a ten-line comment used to be written through a two-line
        // slot that scrolled, and the fixed width was wider than a narrow inlay is allowed to be.
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(56))
        preferredSize = null
    }

    /** Grows with what is typed, to a cap, so the box is the size of the comment being written. */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val lines = (editorField.document.lineCount + 1).coerceIn(BOX_LINES, BOX_CAP)
        val line = editorField.editor?.lineHeight ?: JBUI.scale(18)
        return Dimension(size.width, maxOf(size.height, line * lines + JBUI.scale(12)))
    }

    private companion object {
        /** What the box opens at, and how far it will grow before it starts scrolling instead. */
        const val BOX_LINES = 3
        const val BOX_CAP = 12
    }

    /** Set by the popup hosting this panel: Cancel only has to close it. */
    var onCancelRequested: (() -> Unit)? = null

    /** Told what is in the box as it is typed, so a host that rebuilds itself can put it back. */
    var onTextChanged: ((String) -> Unit)? = null

    private val saveButton = JButton(saveLabel).apply {
        // Read from the keymap: it is Cmd+Enter on macOS, and rebindable everywhere.
        toolTipText = KeymapUtil.getShortcutsText(CommonShortcuts.getCtrlEnter().shortcuts)
        isEnabled = initialText.isNotBlank()
        addActionListener { save() }
    }

    /**
     * Offered beside Save where a note can be parked instead of written into the review.
     *
     * Null everywhere else: replying to an agent or rewording a proposal have nothing to park.
     */
    var onPropose: ((CommentDraft) -> Unit)? = null
        set(value) {
            field = value
            proposeButton.isVisible = value != null
        }

    private val proposeButton = JButton("Propose").apply {
        toolTipText = "Keep it as a proposal - it goes nowhere until you add it to the review"
        isVisible = false
        isEnabled = initialText.isNotBlank()
        addActionListener { save(propose = true) }
    }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.emptyTop(JBUI.scale(4)), JBUI.Borders.empty(5, 6))

        add(editorField, BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)

        editorField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                saveButton.isEnabled = editorField.text.isNotBlank()
                proposeButton.isEnabled = saveButton.isEnabled
                onTextChanged?.invoke(editorField.text)
                // A name typed at the head still decides the type at save, so the combo has to
                // follow it - including when an undo puts one back after a key dropped it.
                if (showType) TypePrefix.of(editorField.text)?.let { typeCombo.selectedItem = it.type }
                // The box's height is its line count, and the host measures once per invalidation:
                // without saying so here, a new line would not be asked about until something else
                // moved.
                if (event.newFragment.contains('\n') || event.oldFragment.contains('\n')) revalidate()
                if (event.newFragment.contains('`')) fillOpenedFence()
            }
        })
        DumbAwareAction.create { save() }.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), editorField)
        if (showType) CommentTypeKeys.install(this, { type }, ::pick)
        // Taken here, or Escape reaches the diff window and closes the whole thing.
        DumbAwareAction.create { cancel() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, editorField)
    }

    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                if (showType) add(typeCombo)
            },
            BorderLayout.WEST,
        )
        add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(proposeButton)
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(saveButton)
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(JButton("Cancel").apply { addActionListener { cancel() } })
            },
            BorderLayout.EAST,
        )
    }

    /**
     * Completes a fence the moment it is opened, filling it with the lines under review.
     *
     * The caret stays on the fence line so a language tag can still be typed, and the body is
     * inserted below it ready to edit.
     */
    private fun fillOpenedFence() {
        val seed = suggestionSeed ?: return
        val editor = editorField.editor ?: return
        val document = editor.document
        if (document.text.lineSequence().count { it.trimStart().startsWith("```") } != 1) return

        val line = document.getLineNumber(editor.caretModel.offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        if (document.getText(TextRange(lineStart, lineEnd)).trim() != "```") return

        // The document cannot be changed from inside its own change notification.
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || lineEnd > document.textLength) return@invokeLater
            CommandProcessor.getInstance().executeCommand(
                project,
                {
                    ApplicationManager.getApplication().runWriteAction {
                        document.insertString(lineEnd, buildString {
                            appendLine()
                            appendLine(seed.trimEnd())
                            append("```")
                        })
                        // Text inserted at the caret drags it along, so put it back on the fence.
                        editor.caretModel.moveToOffset(lineEnd)
                    }
                },
                "Fill Suggestion",
                null,
            )
        }
    }

    private fun pick(picked: CommentType) {
        typeCombo.selectedItem = picked
        dropTypePrefix()
    }

    /** A name left in the text would go on deciding the type at save, over the one just pressed. */
    private fun dropTypePrefix() {
        val document = editorField.document
        val prefix = TypePrefix.of(document.text) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Drop Type Prefix", null, {
            document.deleteString(prefix.from, prefix.until)
        })
    }

    private fun save(propose: Boolean = false) {
        val raw = editorField.text
        val prefix = TypePrefix.of(raw)
        val text = (prefix?.let { raw.removeRange(it.from, it.until) } ?: raw).trim()
        if (text.isEmpty()) return
        val base = if (Suggestion.isPresent(text)) Snippet.of(initialBase ?: suggestionSeed) else null
        val draft = CommentDraft(prefix?.type ?: type, text, base)
        if (propose) onPropose?.invoke(draft) else onSave(draft)
    }

    private fun cancel() {
        onCancelRequested?.invoke()
    }

    override fun addNotify() {
        super.addNotify()
        if (!focusOnShow) return
        // The editor is only wired up once the field is showing, so the caret has to wait for it.
        ApplicationManager.getApplication().invokeLater {
            if (!isShowing) return@invokeLater
            editorField.requestFocusInWindow()
            editorField.editor?.caretModel?.moveToOffset(editorField.text.length)
        }
    }
}

/** Markdown when the bundled plugin is on, plain text otherwise. */
private fun commentFileType(): FileType {
    val markdown = FileTypeManager.getInstance().getFileTypeByExtension("md")
    return if (markdown == UnknownFileType.INSTANCE) FileTypes.PLAIN_TEXT else markdown
}
