package com.github.gillesbergerp.reviewrelay.ui.editor

import com.github.gillesbergerp.reviewrelay.review.model.CommentDraft
import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.ui.CARD_GUTTER
import com.github.gillesbergerp.reviewrelay.ui.CommentPalette
import com.github.gillesbergerp.reviewrelay.ui.CommentStatusUi
import com.github.gillesbergerp.reviewrelay.ui.CommentSurface
import com.github.gillesbergerp.reviewrelay.ui.ProposalAction
import com.github.gillesbergerp.reviewrelay.ui.PROPOSAL_INSET
import com.github.gillesbergerp.reviewrelay.ui.QUOTE_LINES
import com.github.gillesbergerp.reviewrelay.ui.RoundedPanel
import com.github.gillesbergerp.reviewrelay.ui.age
import com.github.gillesbergerp.reviewrelay.ui.cardRow
import com.github.gillesbergerp.reviewrelay.ui.codeBlock
import com.github.gillesbergerp.reviewrelay.ui.iconButton
import com.github.gillesbergerp.reviewrelay.ui.markdown
import com.github.gillesbergerp.reviewrelay.ui.muted
import com.github.gillesbergerp.reviewrelay.ui.quotedCode
import com.github.gillesbergerp.reviewrelay.ui.watchHover
import com.github.gillesbergerp.reviewrelay.ui.whereLabel
import com.github.gillesbergerp.reviewrelay.ui.whereTooltip
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** What a proposal can ask of whatever is keeping it on screen. */
interface ProposalActions {
    fun file()
    fun startEdit(besideTheCode: Boolean)
    fun saveEdit(draft: CommentDraft)
    fun cancelEdit()
    fun dismiss()
    fun toggleExpanded()
}

/**
 * A proposal, drawn beside the code and in the list like the comment it is asking to become.
 *
 * Deliberately quieter than a comment: an inset surface and a greyed type, so a column of these
 * reads as an inbox rather than as work you have already decided to do.
 */
class ProposalComponent(
    private val surface: CommentSurface,
    private val proposal: Proposal,
    /** True while the reviewer is rewording it, which is the only state a proposal has. */
    private val editing: Boolean = false,
    /** Which surface the open box was asked for from, so only that one takes the caret. */
    private val openedBesideTheCode: Boolean = false,
    private val draftText: String? = null,
    private val collapsed: Boolean = false,
    private val reviewedCode: String? = null,
    /** Whether the file has since moved on from them, which is the only reason to date them. */
    private val reviewedCodeMoved: Boolean = false,
    /** Where it is. Worth saying away from the code, pointless beside it. */
    private val showLocation: Boolean = false,
    private val actions: ProposalActions,
) : RoundedPanel(
    BorderLayout(0, JBUI.scale(3)),
    CommentPalette.shade(surface.background, PROPOSAL_INSET),
    JBColor.border(),
) {

    var onHover: (Boolean) -> Unit = {}

    init {
        border = JBUI.Borders.empty(8, 10)
        watchHover(this) { onHover(it) }

        add(header(), BorderLayout.NORTH)
        add(if (collapsed) gist() else body(), BorderLayout.CENTER)
        if (!collapsed && !editing) add(footer(), BorderLayout.SOUTH)
    }

    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(
            cardRow {
                add(
                    iconButton(
                        if (collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown,
                        if (collapsed) "Show this proposal" else "Collapse this proposal",
                    ) { actions.toggleExpanded() }
                )
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(
                    JBLabel(CommentStatusUi.PROPOSAL).apply {
                        toolTipText = "Proposed, not part of the review until you add it"
                        accessibleContext.accessibleName = "Proposal"
                    }
                )
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(
                    JBLabel(proposal.type.toString()).apply {
                        // Greyed the way a closed comment is: neither is work the review is carrying.
                        foreground = UIUtil.getContextHelpForeground()
                        font = JBFont.small().asBold()
                    }
                )
            },
            BorderLayout.WEST,
        )
        if (showLocation) {
            add(
                JBLabel(whereLabel(proposal)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = whereTooltip(proposal, "proposed by ${proposal.origin.label}")
                },
                BorderLayout.CENTER,
            )
        }
        add(
            cardRow {
                ProposalAction.entries.forEachIndexed { index, action ->
                    if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(iconButton(action.icon, action.label) { run(action) })
                }
            },
            BorderLayout.EAST,
        )
    }

    private fun run(action: ProposalAction) = when (action) {
        ProposalAction.FILE -> actions.file()
        ProposalAction.EDIT -> actions.startEdit(surface.besideTheCode)
        ProposalAction.DISMISS -> actions.dismiss()
    }

    private fun body(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        if (editing) {
            add(editBox())
            return@apply
        }
        val (prose, suggestion) = Suggestion.parse(proposal.text)
        if (prose.isNotEmpty()) add(indented(markdown(surface, prose)))
        reviewedCode?.let {
            add(
                quotedCode(
                    surface = surface,
                    item = proposal,
                    code = it,
                    moved = reviewedCodeMoved,
                    cap = QUOTE_LINES.takeIf { showLocation && !reviewedCodeMoved },
                )
            )
        }
        // The replacement an agent proposes, shown as code rather than as a diff: there is nothing
        // to apply until the comment carrying it has been added to the review.
        suggestion?.let { add(indented(codeBlock(surface, proposal, it))) }
    }

    /** The box the proposal is reworded in, which saving turns straight into the comment. */
    private fun editBox(): JComponent = InlineCommentEditorPanel(
        project = surface.project,
        initialText = draftText ?: proposal.text,
        initialType = proposal.type,
        initialBase = proposal.reviewedCode?.text,
        suggestionSeed = proposal.reviewedCode?.text,
        saveLabel = "Add to review",
        embeddedOn = CommentPalette.shade(surface.background, PROPOSAL_INSET),
        // Only the surface it was opened from: the card is drawn in both, and each asked for focus.
        focusOnShow = openedBesideTheCode == surface.besideTheCode,
        onSave = { actions.saveEdit(it) },
    ).apply { onCancelRequested = { actions.cancelEdit() } }

    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        val said = listOfNotNull("proposed by ${proposal.origin.label}", age(proposal.madeAt))
        add(muted(said.joinToString(" · ")), BorderLayout.WEST)
    }

    /** Enough of the opening line to recognise it without opening it. */
    private fun gist(): JComponent {
        val limit = 90
        val opening = proposal.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return muted(if (opening.length > limit) opening.take(limit).trimEnd() + "..." else opening)
    }

    /** To the gutter the quote below it uses, so the two read as one block rather than two columns. */
    private fun indented(component: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyLeft(CARD_GUTTER)
        add(component, BorderLayout.CENTER)
    }
}
