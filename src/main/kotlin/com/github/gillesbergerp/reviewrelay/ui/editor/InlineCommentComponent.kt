package com.github.gillesbergerp.reviewrelay.ui.editor

import com.intellij.icons.AllIcons
import com.github.gillesbergerp.reviewrelay.review.model.CommentDraft
import com.github.gillesbergerp.reviewrelay.review.model.LineDiff
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.ui.CommentAction
import com.github.gillesbergerp.reviewrelay.ui.Dot
import com.github.gillesbergerp.reviewrelay.ui.CARD_GUTTER
import com.github.gillesbergerp.reviewrelay.ui.CARD_RULE
import com.github.gillesbergerp.reviewrelay.ui.QUOTE_LINES
import com.github.gillesbergerp.reviewrelay.ui.markdown
import com.github.gillesbergerp.reviewrelay.ui.quotedCode
import com.github.gillesbergerp.reviewrelay.ui.age
import com.github.gillesbergerp.reviewrelay.ui.cardRow
import com.github.gillesbergerp.reviewrelay.ui.iconButton
import com.github.gillesbergerp.reviewrelay.ui.muted
import com.github.gillesbergerp.reviewrelay.ui.watchHover
import com.github.gillesbergerp.reviewrelay.ui.whereLabel
import com.github.gillesbergerp.reviewrelay.ui.whereTooltip
import com.github.gillesbergerp.reviewrelay.ui.CommentPalette
import com.github.gillesbergerp.reviewrelay.ui.CommentSurface
import com.github.gillesbergerp.reviewrelay.ui.RoundedPanel
import com.github.gillesbergerp.reviewrelay.ui.CommentStatusUi
import com.github.gillesbergerp.reviewrelay.ui.clickable
import com.github.gillesbergerp.reviewrelay.ui.MarkdownPane
import com.github.gillesbergerp.reviewrelay.review.model.DiffLine
import com.github.gillesbergerp.reviewrelay.review.model.DiffLineKind
import com.github.gillesbergerp.reviewrelay.ui.SuggestionDiffView
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** What an inline comment can ask of whatever is keeping it on screen. */
interface CommentActions {
    fun edit(message: ReviewMessage)
    fun saveEdit(message: ReviewMessage, draft: CommentDraft)
    fun cancelEdit()
    fun discard(message: ReviewMessage)
    fun startReply(besideTheCode: Boolean)
    fun cancelReply()
    fun reply(draft: CommentDraft)
    fun applySuggestion(replacement: String)
    fun draftChanged(text: String)
    fun send()
    fun delete()
    fun close(resolved: Boolean)
    fun toggleExpanded()
}

/**
 * A review thread, shown under the code it belongs to: a header carrying its standing, then the
 * messages in order, the reviewer's and the agent's, then a box to answer in.
 *
 * [editing] names the one message showing a box in place of its text, so a reworded comment is
 * changed where it is rather than in a window over the code it is about.
 */
class InlineCommentComponent(
    private val surface: CommentSurface,
    private val thread: ReviewThread,
    private val editing: String?,
    private val replying: Boolean,
    /** Which surface the open box was asked for from, so only that one takes the caret. */
    private val openedBesideTheCode: Boolean = false,
    /** What is typed into the open box but not saved. A card is rebuilt, never updated in place. */
    private val draftText: String? = null,
    /** Shown as one line: a thread you have finished with should not cost a screen of space. */
    private val collapsed: Boolean = false,
    /** The lines as they read when the comment was written, when they are worth showing. */
    private val reviewedCode: String? = null,
    /** Whether the file has since moved on from them, which is the only reason to date them. */
    private val reviewedCodeMoved: Boolean = false,
    /** Those lines as they read now, which is what a suggestion has to be a replacement for. */
    private val currentCode: Snippet? = null,
    /** Where the comment is. Worth saying away from the code, pointless beside it. */
    private val showLocation: Boolean = false,
    /** False where the lines are shown out of a commit, which no edit can be written back into. */
    private val canApply: Boolean = true,
    private val actions: CommentActions,
) : RoundedPanel(
    BorderLayout(0, JBUI.scale(3)),
    surface.background,
    // The edge is the list's to colour: it marks the card you have selected. Unread is the dot.
    JBColor.border(),
) {

    /** Told when the pointer is over this comment, so its lines can answer. */
    var onHover: (Boolean) -> Unit = {}

    init {
        border = JBUI.Borders.empty(8, 10)
        watchHover(this) { onHover(it) }

        add(header(), BorderLayout.NORTH)
        add(if (collapsed) gist() else conversation(), BorderLayout.CENTER)
        // A collapsed thread is a header and a line of its opening; its icon already says it is closed.
        if (!collapsed) add(footer(), BorderLayout.SOUTH)
    }

    /**
     * What is true of the thread rather than what it says, read after the comment instead of before.
     *
     * The pill this replaces was the loudest thing on the card while being the least actionable, and
     * it shouted what the icon two elements to its left already said.
     */
    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        val standing = CommentStatusUi.standing(thread) + listOfNotNull(
            // Says why the lines may not match the file and why there is no Apply here.
            thread.revision?.let { "at ${it.short}" },
            age(thread.messages.lastOrNull()?.writtenAt),
        )
        add(
            muted(standing.joinToString(" · ")).apply {
                thread.revision?.let { toolTipText = "Commented against ${it.hash}" }
            },
            BorderLayout.WEST,
        )
        if (canReply()) add(replyLink(), BorderLayout.EAST)
    }

    /** Nothing to answer while a round is still out, and a thread holds one draft. */
    private fun canReply(): Boolean = editing == null && !replying &&
        thread.status != ThreadStatus.PENDING && thread.status != ThreadStatus.SENT

    private fun conversation(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        var round = 1
        thread.messages.forEachIndexed { index, message ->
            if (thread.startsRound(index)) add(roundBreak(++round, message.sentAt))
            add(message(message, first = index == 0))
            // Under the comment rather than over it: the lines are what the comment is about, and
            // there is no reason to read them before it.
            if (index == 0) reviewedCode?.let { add(quoted(it)) }
        }
        if (thread.messages.isEmpty()) reviewedCode?.let { add(quoted(it)) }
        if (replying && editing == null) add(replyBox())
    }

    private fun quoted(code: String): JComponent = quotedCode(
        surface = surface,
        item = thread,
        code = code,
        moved = reviewedCodeMoved,
        // In the column the quote is context, and context on a comment about a thirty-line range
        // should not be thirty lines of a narrow column.
        cap = QUOTE_LINES.takeIf { showLocation && !reviewedCodeMoved },
    )

    /** Where the reviewer picked the thread back up, so a long exchange reads as rounds. */
    private fun roundBreak(round: Int, sentAt: Instant?): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.compound(
            JBUI.Borders.emptyTop(JBUI.scale(8)),
            JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.emptyTop(JBUI.scale(3)),
            ),
        )
        add(muted(listOfNotNull("round $round", age(sentAt)).joinToString(" · ")), BorderLayout.WEST)
    }

    /** Enough of the opening line to recognise the thread without opening it. */
    private fun gist(): JComponent {
        val limit = 90
        val opening = thread.opening?.text.orEmpty().lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
        return muted(if (opening.length > limit) opening.take(limit).trimEnd() + "..." else opening)
    }

    // BorderLayout, not a glue: an InplaceButton has no maximum size, so BoxLayout stretches the
    // icons apart to fill the row. The location goes in the centre because west is given whatever
    // width it asks for and runs on under east, while the centre is told what is left and ellipsises.
    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(
            cardRow {
                // On every thread, not only closed ones: a pending thread with a long answer under
                // it is the one filling the editor, and it was the one that could not be folded.
                add(
                    iconButton(
                        if (collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown,
                        if (collapsed) "Show this comment" else "Collapse this comment",
                    ) { actions.toggleExpanded() }
                )
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                if (thread.unread) {
                    add(
                        Dot(JBUI.CurrentTheme.Focus.focusColor()).apply {
                            // Six pixels of colour is the whole signal otherwise, which is nothing
                            // at all to a screen reader or to a reader who cannot see the hue.
                            toolTipText = "The agent has answered since you last looked"
                            accessibleContext.accessibleName = "Unread answer"
                        }
                    )
                    add(Box.createHorizontalStrut(JBUI.scale(4)))
                }
                add(
                    JBLabel(CommentStatusUi.icon(thread.status)).apply {
                        toolTipText = thread.status.label
                        accessibleContext.accessibleName = thread.status.label
                    }
                )
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(
                    JBLabel(thread.type.toString()).apply {
                        foreground = if (thread.status.open) {
                            UIUtil.getLabelForeground()
                        } else {
                            UIUtil.getContextHelpForeground()
                        }
                        font = JBFont.small().asBold()
                    }
                )
            },
            BorderLayout.WEST,
        )
        if (showLocation) {
            add(
                // The one line on the card at full size: it is what tells this card from the next.
                JBLabel(whereLabel(thread)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = whereTooltip(thread, "thread ${thread.id.handle}")
                },
                BorderLayout.CENTER,
            )
        }
        add(
            cardRow {
                CommentAction.forStatus(thread.status).forEachIndexed { index, action ->
                    if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(8)))
                    val on = CommentAction.enabledFor(action, thread.status)
                    add(
                        if (on) {
                            iconButton(action.icon, action.label) { run(action) }
                        } else {
                            // The dim icon is deliberate; losing its name with its colour was not.
                            JBLabel(IconLoader.getDisabledIcon(action.icon)).apply {
                                toolTipText = "${action.label} - not while this comment is ${thread.status.label}"
                            }
                        }
                    )
                }
            },
            BorderLayout.EAST,
        )
    }

    private fun run(action: CommentAction) = when (action) {
        CommentAction.SEND -> actions.send()
        CommentAction.RESOLVE -> actions.close(resolved = true)
        CommentAction.WONT_FIX -> actions.close(resolved = false)
        CommentAction.DELETE -> actions.delete()
    }

    /** Deleting the opening message would leave an empty thread, so only a reply carries a discard. */
    private fun messageActions(message: ReviewMessage): JComponent? {
        if (message.author != MessageAuthor.REVIEWER || message.sentAt != null) return null
        return cardRow {
            add(iconButton(CommentAction.EDIT_ICON, "Edit") { actions.edit(message) })
            if (message.id != thread.opening?.id) {
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(iconButton(CommentAction.DELETE.icon, "Discard this reply") { actions.discard(message) })
            }
        }
    }

    /** Closed until asked for: an empty box under every thread is a lot of weight to carry. */
    private fun replyLink(): JComponent =
        ActionLink("Reply") { actions.startReply(surface.besideTheCode) }.apply { font = JBFont.small() }.clickable()

    private fun replyBox(): JComponent = box(
        initialText = draftText.orEmpty(),
        placeholder = "Reply. Markdown; type ``` to suggest an edit.",
        showType = false,
        saveLabel = "Reply",
        // Only the surface it was opened from: the card is drawn in both, and each asked for focus.
        focusOnShow = openedBesideTheCode == surface.besideTheCode,
        onSave = actions::reply,
    ).apply { onCancelRequested = { actions.cancelReply() } }

    private fun editBox(message: ReviewMessage): JComponent = box(
        initialText = draftText ?: message.text,
        initialBase = message.suggestionBase?.text,
        showType = message.id == thread.opening?.id,
        focusOnShow = true,
        onSave = { actions.saveEdit(message, it) },
    ).apply { onCancelRequested = { actions.cancelEdit() } }

    private fun box(
        initialText: String = "",
        initialBase: String? = null,
        placeholder: String? = null,
        showType: Boolean,
        saveLabel: String = "Save",
        focusOnShow: Boolean = false,
        onSave: (CommentDraft) -> Unit,
    ): InlineCommentEditorPanel {
        val panel = InlineCommentEditorPanel(
            project = surface.project,
            initialText = initialText,
            initialType = thread.type,
            initialBase = initialBase,
            // The anchor's own text, where there is one: the stored numbers are where the comment
            // was written, so a file edited above it seeded the fence with unrelated code.
            suggestionSeed = currentCode?.text ?: surface.document?.let { document ->
                thread.lines?.let { reviewedLines(document, it.start, it.end) }
            },
            showType = showType,
            saveLabel = saveLabel,
            placeholder = placeholder,
            embeddedOn = surface.background,
            focusOnShow = focusOnShow,
            onSave = onSave,
        )
        panel.alignmentX = LEFT_ALIGNMENT
        panel.onTextChanged = actions::draftChanged
        return panel
    }

    private fun message(message: ReviewMessage, first: Boolean): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        // An answer is set off the way a quoted reply is, which reads faster than a name alone.
        border = JBUI.Borders.compound(
            JBUI.Borders.emptyTop(JBUI.scale(if (first) 2 else 8)),
            if (message.author == MessageAuthor.AGENT) {
                JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 0, CARD_RULE, 0, 0),
                    JBUI.Borders.emptyLeft(CARD_GUTTER - CARD_RULE),
                )
            } else {
                // The same inset without the rule, so every message shares one left edge.
                JBUI.Borders.emptyLeft(CARD_GUTTER)
            },
        )

        val underEdit = message.id.value == editing
        // A name and a time on a thread that is one sent comment of your own say nothing the footer
        // does not. The sent term is load-bearing: an unsent message carries Edit and Discard in
        // this same row, and silencing it would take them with it.
        val silent = first && thread.messages.size == 1 &&
            message.author == MessageAuthor.REVIEWER && message.sentAt != null
        if (!silent) {
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(
                        cardRow {
                            add(
                                JBLabel(authorLabel(message)).apply {
                                    foreground = UIUtil.getContextHelpForeground()
                                    font = JBFont.small().asBold()
                                }
                            )
                            age(message.writtenAt)?.let {
                                add(Box.createHorizontalStrut(JBUI.scale(6)))
                                add(muted(it))
                            }
                        },
                        BorderLayout.WEST,
                    )
                    // These belong to the message they change, not to the thread as a whole.
                    if (!underEdit) messageActions(message)?.let { add(it, BorderLayout.EAST) }
                },
                BorderLayout.NORTH,
            )
        }
        add(if (underEdit) editBox(message) else body(message), BorderLayout.CENTER)
    }

    private fun body(message: ReviewMessage): JComponent {
        val (prose, suggestion) = Suggestion.parse(message.text)
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            if (prose.isNotEmpty()) add(markdown(prose))
            // The parse takes the fence out of the prose either way, so an agent's code block went
            // nowhere at all: the answer was rendered with a hole where its code had been.
            if (suggestion != null) {
                if (message.author == MessageAuthor.REVIEWER) add(diff(message, suggestion))
                else add(code(suggestion))
            }
        }
    }

    /** Queued is worth saying: it is what will go out with the next round. */
    private fun authorLabel(message: ReviewMessage): String = when {
        message.author == MessageAuthor.AGENT -> "agent"
        message.sentAt == null -> "you, not sent yet"
        else -> "you"
    }

    private fun markdown(prose: String): JComponent = markdown(surface, prose)

    /** The agent's own code: something to read, so no diff against anything and nothing to apply. */
    private fun code(text: String): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(JBUI.scale(4))
        add(
            SuggestionDiffView(
                project = surface.project,
                fileType = fileType(),
                rows = text.lines().map { DiffLine(DiffLineKind.CONTEXT, it) },
            ).apply { border = JBUI.Borders.customLine(JBColor.border(), 1) },
            BorderLayout.CENTER,
        )
    }

    /** Falls back to showing the suggestion alone when there is nothing recorded to diff against. */
    private fun diff(message: ReviewMessage, suggestion: String): JComponent {
        // The lines the suggestion was written against, not the lines as they stand: a suggestion
        // replaces what the reviewer was looking at, and against anything else the diff shows edits
        // nobody proposed - or, once the reviewed lines have moved, an unrelated part of the file.
        val base = message.suggestionBase ?: currentCode
        val rows = if (base == null) {
            LineDiff.assemble(emptyList(), suggestion.lines(), null)
        } else {
            LineDiff.rows(base.text, suggestion)
        }
        val view = SuggestionDiffView(surface.project, fileType(), rows).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }
        return JPanel(BorderLayout(0, JBUI.scale(3))).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(JBUI.scale(4))
            add(view, BorderLayout.CENTER)
            if (canApply) {
                add(
                    cardRow {
                        // Writing it here beats a round trip for an edit the reviewer has already
                        // written out in full; Ctrl+Z takes it back.
                        add(ActionLink("Apply") { actions.applySuggestion(suggestion) }.apply { font = JBFont.small() })
                    },
                    BorderLayout.SOUTH,
                )
            }
        }
    }

    private fun fileType() = FileTypeManager.getInstance().getFileTypeByFileName(thread.file.name)

}
