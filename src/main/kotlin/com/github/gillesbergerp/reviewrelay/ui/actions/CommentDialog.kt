package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.ui.CommentTypeKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * A comment on a whole file, which has no line to sit beside and so needs a dialog.
 *
 * The platform's own panel rather than hand-laid Swing: it is what gives the combo a label a screen
 * reader can read, and rows and columns scale with the IDE font where a pixel size did not.
 */
class CommentDialog(
    project: Project,
    title: String,
    private val file: ReviewedFile,
    initialType: CommentType = CommentType.FIX,
    initialText: String = "",
) : DialogWrapper(project) {

    private var type: CommentType = initialType

    private lateinit var typeCombo: ComboBox<CommentType>

    private val textArea = JBTextArea(initialText, 6, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(3, 5)
    }

    val selectedType: CommentType get() = type
    val commentText: String get() = textArea.text.trim()

    init {
        this.title = title
        setOKButtonText("Add Comment")
        init()
        // The root pane rather than the panel the combo is on: the buttons are in another one.
        // The combo, not [type], is what is current: the binding only writes that field on OK.
        rootPane?.let {
            CommentTypeKeys.install(
                it,
                { typeCombo.selectedItem as CommentType },
                { picked -> typeCombo.selectedItem = picked },
            )
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("File:") {
            // Selectable: it is the one fact in here worth copying out.
            cell(JBLabel(file.path).apply { setCopyable(true) })
        }
        row("Type:") {
            comboBox(CommentType.entries)
                .bindItem({ type }, { type = it ?: CommentType.FIX })
                .applyToComponent {
                    typeCombo = this
                    toolTipText = CommentTypeKeys.hint()
                }
        }
        row {
            scrollCell(textArea).align(Align.FILL)
        }.resizableRow()
    }

    /** An empty comment used to be accepted, and rendered as a card with a blank body. */
    override fun doValidate(): ValidationInfo? =
        if (commentText.isBlank()) ValidationInfo("Write the comment first", textArea) else null

    override fun getPreferredFocusedComponent(): JComponent = textArea
}
