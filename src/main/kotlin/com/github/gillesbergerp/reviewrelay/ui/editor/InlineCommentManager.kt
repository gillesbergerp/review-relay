package com.github.gillesbergerp.reviewrelay.ui.editor

import com.github.gillesbergerp.reviewrelay.review.model.CommentDraft
import com.github.gillesbergerp.reviewrelay.review.model.CodeAnchor
import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.ItemId
import com.github.gillesbergerp.reviewrelay.review.model.Origin
import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.ProposalId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewItem
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.review.model.asMessage
import com.github.gillesbergerp.reviewrelay.review.service.ReviewPublisher
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.ReviewThreadListener
import com.github.gillesbergerp.reviewrelay.ui.CommentPalette
import com.github.gillesbergerp.reviewrelay.ui.CommentStatusUi
import com.github.gillesbergerp.reviewrelay.ui.CommentSurface
import com.github.gillesbergerp.reviewrelay.ui.EditorInlayHost
import com.github.gillesbergerp.reviewrelay.review.model.LineRange
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.github.gillesbergerp.reviewrelay.review.service.notifyReview
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.util.messages.Topic
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.Icon
import javax.swing.JComponent

/** Told when a comment opens or closes a box, which is UI state the session knows nothing about. */
interface CommentBoxListener {
    fun boxChanged(itemId: ItemId)

    companion object {
        val TOPIC: Topic<CommentBoxListener> =
            Topic.create("ReviewRelayCommentBox", CommentBoxListener::class.java)
    }
}

/**
 * The review comments shown inside this project's editors.
 *
 * Per project rather than global: an inlay belongs to one editor of one project, and holding them
 * in shared state outlived the projects that opened them.
 */
@Service(Service.Level.PROJECT)
class InlineCommentManager(private val project: Project) : Disposable {

    /** A comment shows in every editor of its file at once: a tab and a diff are two of them. */
    private val inlays = mutableMapOf<ItemId, MutableList<EditorInlayHost.Handle>>()

    /** Keeps a comment pinned to its code while the agent edits the file underneath it. */
    private val anchors = mutableMapOf<ItemId, RangeMarker>()

    /** Which message of a thread is open for editing, so a redraw does not close the box. */
    private val editing = mutableMapOf<ThreadId, String>()

    /** Threads showing an open reply box rather than the link that opens it. */
    /** Which threads have a reply box open, and which surface asked for it. */
    private val replying = mutableMapOf<ThreadId, Boolean>()

    /** Closed threads the reviewer has opened again; closed ones are one line until they are. */
    private val expanded = mutableSetOf<ThreadId>()

    /** Open threads the reviewer has folded away, which closed ones do not need to be told. */
    private val collapsed = mutableSetOf<ThreadId>()

    /**
     * What is typed into an open box but not saved. A thread shows one box at a time and is
     * redrawn on any change to any comment, which would otherwise throw away a half-written reply.
     */
    private val drafts = mutableMapOf<ThreadId, String>()

    /** Proposals open for rewording, against the surface the box was asked for from. */
    private val editingProposals = mutableMapOf<ProposalId, Boolean>()
    private val foldedProposals = mutableSetOf<ProposalId>()
    private val proposalDrafts = mutableMapOf<ProposalId, String>()

    fun editingIn(threadId: ThreadId): String? = editing[threadId]

    fun isReplyingIn(threadId: ThreadId): Boolean = threadId in replying

    fun draftIn(threadId: ThreadId): String? = drafts[threadId]

    fun isEditing(id: ProposalId): Boolean = id in editingProposals

    fun isEditingBesideTheCode(id: ProposalId): Boolean = editingProposals[id] == true

    fun draftIn(id: ProposalId): String? = proposalDrafts[id]

    fun isCollapsed(proposal: Proposal): Boolean = proposal.id in foldedProposals

    /**
     * Closed threads start folded; any thread can be folded by hand.
     *
     * A pending thread carrying a long answer is exactly the one filling the editor, and it was the
     * one that could not be folded at all.
     */
    fun isCollapsed(thread: ReviewThread): Boolean =
        if (thread.status.open) thread.id in collapsed else thread.id !in expanded

    /** What a card shows of the code a comment was written against, and whether it still holds. */
    class Quoted(val text: String, val moved: Boolean)

    /**
     * Beside the code, the original is worth showing only once the code has moved on from it; away
     * from the code there is nothing else to read it against.
     */
    fun reviewedCodeFor(item: ReviewItem, besideTheCode: Boolean): Quoted? {
        val original = item.reviewedCode?.takeIf { !it.isBlank } ?: return null
        // Unreadable is not moved: on a first draw there is no anchor yet, and the fall back to disk
        // answers for the working tree even when the comment was written against a revision.
        val now = currentText(item)
        val moved = now != null && !sameCode(original, now)
        if (besideTheCode && !moved) return null
        return Quoted(original.text, moved)
    }

    /** Trailing whitespace differs between reading a marker and reading the lines; nothing else. */
    private fun sameCode(a: Snippet, b: Snippet): Boolean = tidy(a.text) == tidy(b.text)

    private fun tidy(code: String): List<String> = code.lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }

    /** The reviewed lines, marked in the editor the way a selection is. */
    private val highlights = mutableMapOf<ItemId, MutableList<RangeHighlighter>>()

    /** The gutter and scrollbar marks that say a line carries a comment at all. */
    private val marks = mutableMapOf<ItemId, MutableList<RangeHighlighter>>()

    /** The box for a comment that has no thread yet, shown as an inlay like every other one. */
    private var draft: EditorInlayHost.Handle? = null

    /** What is in the open box, so opening another one knows whether it would be throwing work away. */
    private var draftText: String = ""

    private val service: ReviewSessionService get() = ReviewSessionService.getInstance(project)

    init {
        // The cards beside the code read the same threads the pane does, and only this class knew
        // when to redraw them: a comment sent or answered from anywhere else left them as they were.
        project.messageBus.connect(this).subscribe(
            ReviewThreadListener.TOPIC,
            object : ReviewThreadListener {
                override fun commentsChanged() = redrawShown()
            },
        )
    }

    /** What each card was last drawn from, so a change to one thread does not repaint the rest. */
    private val drawnFrom = mutableMapOf<ItemId, ReviewItem>()

    /**
     * Brings the cards beside the code back in line with the review on screen.
     *
     * Switching tabs changes which review that is, and a comment belonging to another one has no
     * business staying in the editor - it is about the same file, so nothing else would remove it.
     */
    private fun redrawShown() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val current = service.currentSession.items.associateBy { it.id }

            inlays.keys.filterNot { it in current }.toList().forEach {
                removeInlineComment(it)
                drawnFrom.remove(it)
            }
            current.values.forEach { item ->
                if (item.id in inlays && drawnFrom.put(item.id, item) != item) redraw(item.id)
            }
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { editor -> commentPath(editor)?.let { reapplyInlineComments(editor, it) } }
        }
    }

    /** Opens the box for a comment on [line1Based], widened to the selection when it covers it. */
    fun newComment(editor: Editor, line1Based: Int) {
        val path = commentPath(editor) ?: return
        val (lineStart, lineEnd) = rangeForLine(editor, line1Based)
        // The comment records the file's lines; the box and the snippet are the editor's own, which
        // in a unified diff count the removed lines too.
        val bridge = commentLines(editor)
        val fileStart = bridge.fileLine(lineStart - 1)?.plus(1) ?: return notifyReview(
            project,
            "That line was removed by this change, so there is nothing there to comment on.",
            NotificationType.WARNING,
        )
        // A range whose far end is a removed line has no end in the file, so it is the start alone.
        val fileEnd = lineEnd?.let { bridge.fileLine(it - 1)?.plus(1) }?.takeIf { it >= fileStart }
        val target = CommentTarget.Line(path, LineRange(fileStart, fileEnd ?: fileStart), commentRevision(editor))
        val written = Snippet.of(reviewedText(editor, fileStart, fileEnd ?: fileStart))
        openDraft(
            editor = editor,
            lineStart = lineStart,
            lineEnd = lineEnd,
            onPropose = { draft ->
                service.addProposal(
                    service.activeReviewId,
                    Proposal(
                        origin = Origin.Reviewer,
                        type = draft.type,
                        target = target,
                        text = draft.text,
                        reviewedCode = written,
                    ),
                )
            },
        ) { draft ->
            val comment = ReviewThread(
                type = draft.type,
                target = target,
                reviewedCode = written,
                messages = listOf(draft.asMessage()),
            )
            service.addThread(comment)
            showEverywhere(editor.document, comment)
        }
    }

    /** Every editor of this document in this project. */
    private fun editorsOf(document: Document): List<Editor> =
        EditorFactory.getInstance().getEditors(document, project).toList()

    private fun showEverywhere(document: Document, comment: ReviewThread) =
        editorsOf(document).forEach { addInlineItem(it, comment) }

    fun addInlineItem(editor: Editor, item: ReviewItem, at: Int? = null) {
        val lines = item.lines ?: return
        val first = at ?: lines.start
        // [at] is the file's line, and this editor may not number its lines that way.
        val bridge = commentLines(editor)
        val lineStart = bridge.editorLine(first - 1) ?: return
        val document = editor.document
        // The document is measured under a read action, as everywhere else here: the EDT holds none
        // of its own, and this runs from a redraw.
        val offset = runReadAction {
            if (lineStart < 0 || lineStart >= document.lineCount) return@runReadAction null
            // The far end through the same mapping: a removed line inside the range makes the card's
            // own span longer than the file's.
            val lineEnd = (bridge.editorLine(first - 1 + lines.span) ?: lineStart)
                .coerceIn(lineStart, document.lineCount - 1)
            lineEnd to document.getLineEndOffset(lineEnd)
        }
        if (offset == null) {
            LOG.debug("Skipping comment ${item.id}: line ${lineStart + 1} is outside the document")
            return
        }
        val (lineEnd, endOffset) = offset

        val handle = EditorInlayHost.add(editor, endOffset, cardFor(editor, item))
        if (handle == null) {
            LOG.warn("Could not add an inlay for comment ${item.id}")
            return
        }
        inlays.getOrPut(item.id) { mutableListOf() }.add(handle)
        anchor(document, item, lineStart, lineEnd)
        mark(editor, item, lineStart)
    }

    /**
     * The card for an item as it is drawn beside the code, with the pointer wired to its lines.
     *
     * A proposal is drawn here on the same terms as a comment: it is about a place in this file,
     * and reading it in the column while the code it is about is on screen is the harder way round.
     */
    private fun cardFor(editor: Editor, item: ReviewItem): JComponent {
        val quoted = reviewedCodeFor(item, besideTheCode = true)
        return when (item) {
            is ReviewThread -> InlineCommentComponent(
                surface = CommentSurface.of(editor),
                thread = item,
                editing = editing[item.id],
                replying = item.id in replying,
                openedBesideTheCode = replying[item.id] == true,
                draftText = drafts[item.id],
                collapsed = isCollapsed(item),
                reviewedCode = quoted?.text,
                reviewedCodeMoved = quoted?.moved == true,
                currentCode = currentText(item),
                canApply = editor.document.isWritable,
                actions = actionsFor(item),
            ).also { card -> card.onHover = { markOnHover(editor, item, it) } }

            is Proposal -> ProposalComponent(
                surface = CommentSurface.of(editor),
                proposal = item,
                editing = isEditing(item.id),
                openedBesideTheCode = isEditingBesideTheCode(item.id),
                draftText = draftIn(item.id),
                collapsed = isCollapsed(item),
                reviewedCode = quoted?.text,
                reviewedCodeMoved = quoted?.moved == true,
                actions = actionsFor(item),
            ).also { card -> card.onHover = { markOnHover(editor, item, it) } }
        }
    }

    /**
     * A mark in the gutter and on the scrollbar for a line that carries a comment.
     *
     * The card itself is the only sign there is one, and it scrolls away with the code: a file could
     * hold six comments and say so nowhere the reviewer could see at a glance.
     */
    private fun mark(editor: Editor, item: ReviewItem, line: Int) {
        val stripe = TextAttributes().apply { errorStripeColor = JBUI.CurrentTheme.Focus.focusColor() }
        val marker = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ERROR - 1, stripe)
        marker.gutterIconRenderer = CommentGutterIcon(item)
        marker.errorStripeTooltip = "${item.type}: ${item.text.lineSequence().firstOrNull().orEmpty()}"
        marks.getOrPut(item.id) { mutableListOf() }.add(marker)
    }

    private fun clearMarks(commentId: ItemId) {
        marks.remove(commentId)?.forEach { marker ->
            runCatching { marker.dispose() }
        }
    }

    private fun markOnHover(editor: Editor, item: ReviewItem, on: Boolean) {
        val range = markedRange(editor, item)
        if (on && range != null) highlight(editor, item, range.first, range.last)
        else clearHighlights(item.id)
    }

    /** Where the comment sits in this editor now: the anchor if it has one here, else its lines. */
    private fun markedRange(editor: Editor, comment: ReviewItem): IntRange? = runReadAction {
        val document = editor.document
        anchors[comment.id]?.takeIf { it.isValid && it.document == document }?.let {
            return@runReadAction document.getLineNumber(it.startOffset)..document.getLineNumber(it.endOffset)
        }
        val lines = comment.lines ?: return@runReadAction null
        val bridge = commentLines(editor)
        val start = bridge.editorLine(lines.start - 1) ?: return@runReadAction null
        if (start < 0 || start >= document.lineCount) return@runReadAction null
        val end = (bridge.editorLine(lines.start - 1 + lines.span) ?: start)
            .coerceIn(start, document.lineCount - 1)
        start..end
    }

    /** The same actions wherever the comment is shown; none of them need the editor. */
    fun actionsFor(comment: ReviewThread): CommentActions = object : CommentActions {

        override fun edit(message: ReviewMessage) {
            editing[comment.id] = message.id.value
            drafts.remove(comment.id)
            redraw(comment.id)
        }

        override fun saveEdit(message: ReviewMessage, draft: CommentDraft) {
            editing.remove(comment.id)
            drafts.remove(comment.id)
            if (message.id == comment.opening?.id) service.setType(comment.id, draft.type)
            service.editMessage(comment.id, message.id, draft.text, draft.suggestionBase)
            redraw(comment.id)
        }

        override fun cancelEdit() {
            editing.remove(comment.id)
            drafts.remove(comment.id)
            redraw(comment.id)
        }

        override fun draftChanged(text: String) {
            drafts[comment.id] = text
        }

        override fun discard(message: ReviewMessage) {
            service.removeMessage(comment.id, message.id)
            redraw(comment.id)
        }

        override fun startReply(besideTheCode: Boolean) {
            replying[comment.id] = besideTheCode
            drafts.remove(comment.id)
            redraw(comment.id)
        }

        override fun toggleExpanded() {
            val set = if (comment.status.open) collapsed else expanded
            if (!set.remove(comment.id)) set.add(comment.id)
            redraw(comment.id)
        }

        override fun cancelReply() {
            replying.remove(comment.id)
            drafts.remove(comment.id)
            redraw(comment.id)
        }

        override fun reply(draft: CommentDraft) {
            replying.remove(comment.id)
            drafts.remove(comment.id)
            service.addMessage(comment.id, draft.asMessage())
            redraw(comment.id)
        }

        override fun applySuggestion(replacement: String) = applySuggestion(comment, replacement)

        override fun send() = ReviewPublisher.publish(project, comment.id)

        override fun delete() = deleteComment(comment)

        override fun close(resolved: Boolean) = closeComment(comment, resolved)
    }

    /**
     * Deciding about a proposal, wherever it is shown.
     *
     * Filing and dismissing both change the review, so both are announced by the service and need
     * no redraw here; rewording is this class's own state and does.
     */
    fun actionsFor(proposal: Proposal): ProposalActions = object : ProposalActions {

        override fun file() {
            forget()
            catchUpLines(proposal)
            service.fileProposal(proposal.id)
        }

        override fun startEdit(besideTheCode: Boolean) {
            editingProposals[proposal.id] = besideTheCode
            proposalDrafts.remove(proposal.id)
            redraw(proposal.id)
        }

        override fun saveEdit(draft: CommentDraft) {
            forget()
            catchUpLines(proposal)
            service.fileProposal(proposal.id, draft)
        }

        override fun cancelEdit() {
            forget()
            redraw(proposal.id)
        }

        override fun dismiss() {
            forget()
            service.dismissProposal(proposal.id)
        }

        override fun toggleExpanded() {
            if (!foldedProposals.remove(proposal.id)) foldedProposals.add(proposal.id)
            redraw(proposal.id)
        }

        private fun forget() {
            editingProposals.remove(proposal.id)
            proposalDrafts.remove(proposal.id)
        }
    }

    /** Says which lines the comment is about without the reviewer having to count them off. */
    private fun highlight(editor: Editor, comment: ReviewItem, lineStart: Int, lineEnd: Int) {
        val document = editor.document
        clearHighlights(comment.id)
        // A band rather than a box per line: BOXED follows each line's own text extent, so a range
        // came out as a stack of ragged rectangles instead of one region.
        val band = CommentPalette.shade(editor.colorsScheme.defaultBackground, 0.10f)
        val marker = editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(lineStart),
            document.getLineEndOffset(lineEnd),
            HighlighterLayer.SELECTION - 2,
            TextAttributes(null, band, null, null, Font.PLAIN),
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        highlights.getOrPut(comment.id) { mutableListOf() }.add(marker)
    }

    /**
     * Marks a comment's lines in whatever editor already has the file open, for the list to call.
     *
     * Opens nothing: a pointer crossing the column must not take over the editor.
     */
    fun markLines(comment: ReviewItem, on: Boolean) {
        if (!on) return clearHighlights(comment.id)
        runReadAction {
            val file = reviewedFile(project, comment.file) ?: return@runReadAction
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction
            editorsOf(document).forEach { editor ->
                markedRange(editor, comment)?.let { highlight(editor, comment, it.first, it.last) }
            }
        }
    }

    private fun clearHighlights(commentId: ItemId) {
        highlights.remove(commentId)?.forEach { marker ->
            runCatching { marker.dispose() }
        }
    }

    /**
     * Follows the comment's lines in the file itself, and only there.
     *
     * A thread has one anchor, and every editor drawing its card used to claim it - so opening a
     * commit's diff moved a working-tree comment's anchor into that commit's text, where the lines
     * a send reports are the commit's own and the code can never be seen to change again.
     */
    private fun anchor(document: Document, comment: ReviewItem, lineStart: Int, lineEnd: Int) {
        runReadAction {
            if (!isLiveFileOf(comment, document)) return@runReadAction
            anchors.remove(comment.id)?.dispose()
            // Surviving an external change: the agent rewriting the whole function invalidates a
            // plain marker inside it, and nothing here would ever have made another.
            anchors[comment.id] = document.createRangeMarker(
                document.getLineStartOffset(lineStart),
                document.getLineEndOffset(lineEnd),
                true,
            )
        }
    }

    /** Whether [document] is the comment's own file, as against a revision's or a diff's own. */
    private fun isLiveFileOf(comment: ReviewItem, document: Document): Boolean = runReadAction {
        val file = reviewedFile(project, comment.file) ?: return@runReadAction false
        FileDocumentManager.getInstance().getDocument(file) === document
    }

    fun removeInlineComment(commentId: ItemId) {
        inlays.remove(commentId)?.forEach { Disposer.dispose(it.inlay) }
        clearHighlights(commentId)
        clearMarks(commentId)
        anchors.remove(commentId)?.dispose()
    }

    fun clearAllInlineComments() {
        inlays.values.flatten().forEach { Disposer.dispose(it.inlay) }
        inlays.clear()
        highlights.keys.toList().forEach { clearHighlights(it) }
        marks.keys.toList().forEach { clearMarks(it) }
        anchors.values.forEach { it.dispose() }
        anchors.clear()
        editing.clear()
        replying.clear()
        drafts.clear()
    }

    /** Idempotent: safe to call again for an editor that already carries the comments. */
    fun reapplyInlineComments(editor: Editor, file: ReviewedFile) {
        val revision = commentRevision(editor)
        service.currentSession.items
            .filter { it.file == file && it.lines != null }
            .forEach { item ->
                val shown = inlays[item.id]
                shown?.removeAll { !it.isValid }
                if (shown != null && shown.any { it.editor == editor }) {
                    // Drawn here already; only a lost anchor is worth the search.
                    if (anchors[item.id]?.isValid != true) reanchor(editor, item, revision)
                    return@forEach
                }
                val at = placeOf(editor, item, revision) ?: return@forEach
                addInlineItem(editor, item, at)
            }
    }

    /** Puts an anchor back after a rewrite took the one it had, so the card can be applied to again. */
    private fun reanchor(editor: Editor, comment: ReviewItem, revision: Revision?) {
        val at = placeOf(editor, comment, revision) ?: return
        val lineStart = at - 1
        if (lineStart < 0 || lineStart >= editor.document.lineCount) return
        anchor(editor.document, comment, lineStart, (lineStart + (comment.lines?.span ?: 0))
            .coerceIn(lineStart, editor.document.lineCount - 1))
    }

    /**
     * The line this comment belongs on in [document], or null when nothing places it.
     *
     * The code is searched for first even in the revision it was read in: an agent edits the
     * working tree with the IDE's markers often not watching, so the stored number is a fallback
     * rather than the answer.
     */
    /**
     * The lines the comment is about, as the file has them.
     *
     * Read line by line where this editor numbers its own: a selection reaching across a removal
     * would otherwise record text the file does not hold, which nothing could place it by later.
     */
    private fun reviewedText(editor: Editor, fileStart: Int, fileEnd: Int): String? {
        val bridge = commentLines(editor)
        if (bridge === SameLines) return reviewedLines(editor.document, fileStart, fileEnd)
        return (fileStart..fileEnd)
            .mapNotNull { bridge.editorLine(it - 1) }
            .mapNotNull { reviewedLines(editor.document, it + 1, it + 1) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * The file line to draw this comment at in [editor], or null when nothing places it there.
     *
     * A unified diff's document holds both sides, so searching its text for the reviewed code can
     * as easily find the removed copy of it: there the stored number is the only sound answer, and
     * only for a comment written against the very revision that side is showing.
     */
    private fun placeOf(editor: Editor, comment: ReviewItem, revision: Revision?): Int? {
        if (commentLines(editor) === SameLines) return lineOf(editor.document, comment, revision)
        return comment.lines?.start?.takeIf { comment.revision == revision }
    }

    private fun lineOf(document: Document, comment: ReviewItem, revision: Revision?): Int? {
        val code = comment.reviewedCode?.takeIf { !it.isBlank }?.text
        // Only in the revision it was read in does the stored number still count the same lines.
        val stored = comment.lines?.start?.takeIf { comment.revision == revision }
        return CodeAnchor.placeOf(document.text, code, stored)
    }

    /**
     * Writes anchor positions back into the stored comments. Called before a review is sent so the
     * agent gets the line numbers as they are now, not as they were when the comment was written.
     */
    fun syncCommentLines() {
        val moved = runReadActionBlocking {
            service.currentSession.items.mapNotNull { item -> linesNow(item)?.let { item.id to it } }
        }
        moved.forEach { (id, lines) -> service.updateLines(id, lines) }
    }

    /** Where the item's lines have got to, read off the anchor following them. */
    private fun linesNow(item: ReviewItem): LineRange? {
        // A commit's line numbers are its own and never move; only the working tree's do.
        if (item.revision != null) return null
        val marker = anchors[item.id]?.takeIf { it.isValid } ?: return null
        val document = marker.document
        val start = document.getLineNumber(marker.startOffset) + 1
        val end = document.getLineNumber(marker.endOffset) + 1
        return LineRange.of(start, end.takeIf { it != start })
    }

    /** Filing copies the target as it stands, so the lines have to be caught up before it does. */
    private fun catchUpLines(proposal: Proposal) {
        val moved = runReadActionBlocking { linesNow(proposal) } ?: return
        service.updateLines(proposal.id, moved)
    }

    /** The reviewed lines as they read right now: from the live anchor, or the file if it is closed. */
    fun currentText(item: ReviewItem): Snippet? = runReadActionBlocking {
        anchors[item.id]?.takeIf { it.isValid }?.let { marker ->
            return@runReadActionBlocking Snippet.of(marker.document.getText(TextRange(marker.startOffset, marker.endOffset)))
        }

        // The working tree holds other code at a commit's line numbers, and reading it there is how
        // an untouched comment came to be reported as moved and its code as changed.
        if (item.revision != null) return@runReadActionBlocking null
        val file = reviewedFile(project, item.file) ?: return@runReadActionBlocking null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadActionBlocking null
        val lines = item.lines ?: return@runReadActionBlocking null
        Snippet.of(reviewedLines(document, lines.start, lines.end))
    }

    /**
     * Records whether the reviewed lines moved while the agent worked. An observation, not a
     * verdict: the reviewer still decides whether a comment is dealt with.
     *
     * [review] is the one the agent was given, which is not always the one on screen: a reviewer
     * who moved to another tab while it worked must not have that tab's threads stamped instead.
     */
    fun observeSentComments(review: ReviewSession) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            review.threads
                .filter { it.status == ThreadStatus.SENT && it.sentBase != null }
                .forEach { comment ->
                    val current = currentText(comment) ?: return@forEach
                    val changed = current != comment.sentBase
                    if (comment.codeChanged != changed) {
                        service.recordObservation(review.id, comment.id, changed)
                        redraw(comment.id)
                    }
                }
        }
    }

    /** Redraws in place, so the card does not blink out of the editor and back on every action. */
    fun redraw(itemId: ItemId) {
        project.messageBus.syncPublisher(CommentBoxListener.TOPIC).boxChanged(itemId)
        val item = item(itemId) ?: return
        val shown = inlays[itemId] ?: return
        shown.removeAll { !it.isValid }
        // The component about to be replaced can no longer be told the pointer has left it, so a
        // mark made under it would outlive every card that could clear it.
        clearHighlights(itemId)
        // The mark carries the status too, and was written only where a card was first added: a
        // comment sent or resolved kept its pending icon until the file was opened again.
        clearMarks(itemId)
        shown.forEach { handle ->
            markedRange(handle.editor, item)?.let { mark(handle.editor, item, it.first) }
            handle.show(cardFor(handle.editor, item))
        }
    }

    private fun item(id: ItemId): ReviewItem? = when (id) {
        is ThreadId -> service.thread(id)
        is ProposalId -> service.proposal(id)
    }

    /**
     * Writes a suggestion over the lines it was written against, as one undoable edit.
     *
     * The anchor rather than the stored line numbers, so a suggestion still lands correctly after
     * the agent has moved the code underneath it.
     */
    fun applySuggestion(comment: ReviewThread, replacement: String) {
        // Both of these used to be a silent no-op, which reads as a broken link rather than as a
        // suggestion that no longer has lines to replace.
        val marker = anchors[comment.id]?.takeIf { it.isValid } ?: return notifyReview(
            project,
            "The lines this suggestion was written against are no longer open. Open ${comment.file.name} and try again.",
            NotificationType.WARNING,
        )
        val document = marker.document
        if (!document.isWritable) return notifyReview(
            project,
            "${comment.file.name} cannot be edited here, so the suggestion has nowhere to go.",
            NotificationType.WARNING,
        )
        WriteCommandAction.runWriteCommandAction(project, "Apply Review Suggestion", null, {
            document.replaceString(marker.startOffset, marker.endOffset, replacement)
        })
    }

    fun closeComment(comment: ReviewThread, resolved: Boolean) {
        // Closing drops the unsent messages, which may be the one open for editing.
        editing.remove(comment.id)
        drafts.remove(comment.id)
        // Closing always folds it away, whatever it was showing before.
        expanded.remove(comment.id)
        if (resolved) service.resolve(comment.id) else service.wontFix(comment.id)
        redraw(comment.id)
    }

    fun deleteComment(comment: ReviewThread) {
        if (!confirmedDelete(comment)) return
        editing.remove(comment.id)
        replying.remove(comment.id)
        drafts.remove(comment.id)
        expanded.remove(comment.id)
        removeInlineComment(comment.id)
        service.removeThread(comment.id)
    }

    /**
     * A comment nobody has seen is cheap to write again; one with a conversation in it is not.
     *
     * Delete is a keystroke and sits beside Resolve on every card, and unlike everything else this
     * plugin deletes it used to go without asking.
     */
    private fun confirmedDelete(comment: ReviewThread): Boolean {
        val replies = comment.messages.size - 1
        if (replies <= 0 && comment.status == ThreadStatus.PENDING) return true
        val what = if (replies > 0) "this comment and the $replies replies to it" else "this comment"
        return Messages.showYesNoDialog(
            project,
            "Delete $what? The agent has already been given it.",
            "Delete Comment",
            "Delete",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    /** Sits in the code the way a thread does, so the lines move apart to make room for it. */
    private fun openDraft(
        editor: Editor,
        lineStart: Int,
        lineEnd: Int?,
        onPropose: ((CommentDraft) -> Unit)? = null,
        onSave: (CommentDraft) -> Unit,
    ) {
        // There is one box, so a second comment closes the first. Half a comment is still work -
        // but only while its box is still there: closing the file leaves the text behind.
        if (draft?.isValid == true && draftText.isNotBlank()) {
            val discard = Messages.showYesNoDialog(
                project,
                "Discard the comment you are writing?",
                "Discard Comment",
                "Discard",
                "Keep Writing",
                Messages.getWarningIcon(),
            )
            if (discard != Messages.YES) return
        }
        closeDraft()
        val document = editor.document
        val at = EditorInlayHost.endOf(document, (lineEnd ?: lineStart) - 1)

        val panel = InlineCommentEditorPanel(
            project = editor.project,
            suggestionSeed = reviewedLines(document, lineStart, lineEnd ?: lineStart),
            embeddedOn = editor.colorsScheme.defaultBackground,
            focusOnShow = true,
        ) { written ->
            closeDraft()
            onSave(written)
        }
        panel.onCancelRequested = { closeDraft() }
        panel.onTextChanged = { draftText = it }
        onPropose?.let { propose ->
            panel.onPropose = { written ->
                closeDraft()
                propose(written)
            }
        }

        draft = EditorInlayHost.add(editor, at, panel)
    }

    private fun closeDraft() {
        // Back to the code it was written against: focus went nowhere, so after Escape or Save the
        // reviewer had to click into the editor before any key did anything.
        val editor = draft?.editor
        draft?.let { Disposer.dispose(it.inlay) }
        draft = null
        draftText = ""
        editor?.contentComponent?.requestFocusInWindow()
    }

    override fun dispose() {
        closeDraft()
        clearAllInlineComments()
    }

    companion object {

        private val LOG = Logger.getInstance(InlineCommentManager::class.java)

        fun getInstance(project: Project): InlineCommentManager =
            project.getService(InlineCommentManager::class.java)
    }
}

/** Says a line carries a comment, from the one place that is visible with the card scrolled away. */
private class CommentGutterIcon(private val item: ReviewItem) : GutterIconRenderer() {

    private val icon: Icon = when (item) {
        is ReviewThread -> CommentStatusUi.icon(item.status)
        is Proposal -> CommentStatusUi.PROPOSAL
    }

    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String =
        "${item.type}: ${item.text.lineSequence().firstOrNull().orEmpty()}"

    // On the icon rather than the standing behind it: filing a proposal replaces it with a thread,
    // and everything else that moves the icon is a status.
    override fun equals(other: Any?): Boolean =
        other is CommentGutterIcon && other.item.id == item.id && other.icon == icon

    override fun hashCode(): Int = item.id.hashCode()
}
