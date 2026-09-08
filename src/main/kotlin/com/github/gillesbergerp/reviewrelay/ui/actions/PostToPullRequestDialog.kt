package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.review.export.GitHubDestination
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.service.comments
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * Where the review is about to go, before it goes.
 *
 * The pull request is a choice rather than whichever one gh finds for the checked-out branch: a
 * review is not tied to a branch - the pane may be showing commits from another one - and a branch
 * nobody has opened one for still has somewhere its comments belong.
 */
class PostToPullRequestDialog(
    private val project: Project,
    private val where: String,
    private val github: GitHubDestination,
    private val threads: List<ReviewThread>,
    private val summary: String,
    private val choices: List<GitHubDestination.PullRequest>,
    /** The branch's own, against which a different pick is worth a warning. Null if it has none. */
    private val branchPullRequest: Int?,
    initial: GitHubDestination.Plan,
) : DialogWrapper(project) {

    var plan: GitHubDestination.Plan = initial
        private set

    /** Read once per pull request and per placement, so going back to one is free. */
    private val read = mutableMapOf<Pair<Int, Boolean>, GitHubDestination.Plan>()

    /** Guards the listeners while the picker is being set to match [plan] rather than changed. */
    private var listening = false

    /** Handed back from the pooled thread the progress runs the read on. */
    private var made: GitHubDestination.Plan? = null

    private var broke: Throwable? = null

    private lateinit var picker: ComboBox<GitHubDestination.PullRequest>

    private val fileOnly = JBCheckBox("Attach every comment to its file, not its line")
        .apply { addActionListener { replan() } }

    private val repository = JBLabel().apply { setCopyable(true) }

    private val going = JBLabel()

    private val warning = JBLabel("", AllIcons.General.Warning, SwingConstants.LEADING)

    private val trouble = JBLabel("", AllIcons.General.Error, SwingConstants.LEADING)

    private lateinit var warningRow: Row

    private lateinit var troubleRow: Row

    init {
        title = "Post to the Pull Request"
        setOKButtonText("Post")
        read[initial.pullRequest.number to initial.fileOnly] = initial
        init()
        picker.selectedItem = initial.pullRequest
        fileOnly.isSelected = initial.fileOnly
        describe()
        listening = true
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Repository:") { cell(repository) }
        row("Pull request:") {
            comboBox(choices, textListCellRenderer("") { label(it) })
                .applyToComponent {
                    picker = this
                    addItemListener { if (it.stateChange == ItemEvent.SELECTED) picked() }
                }
        }
        row { cell(going) }
        row { cell(fileOnly) }
        row { cell(warning) }.also { warningRow = it }
        row { cell(trouble) }.also { troubleRow = it }
    }

    /** Nothing to place and nothing to say: gh would take it, and leave a bare summary behind. */
    override fun doValidate(): ValidationInfo? =
        if (plan.count == 0 && summary.isBlank()) {
            ValidationInfo("Everything still open is already on ${plan.at}")
        } else {
            null
        }

    override fun getPreferredFocusedComponent(): JComponent = picker

    /** A different pull request is a fresh decision about placement, so that is re-answered too. */
    private fun picked() {
        val pr = picker.selectedItem as? GitHubDestination.PullRequest ?: return
        withoutListening { fileOnly.isSelected = pr.number != branchPullRequest }
        replan()
    }

    private fun replan() {
        if (!listening) return
        val pr = picker.selectedItem as? GitHubDestination.PullRequest ?: return
        val key = pr.number to fileOnly.isSelected
        read[key]?.let { return adopt(it) }

        made = null
        broke = null
        val ran = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    made = github.plan(project, where, threads, pr, fileOnly.isSelected)
                } catch (e: Exception) {
                    broke = e
                }
            },
            "Reading Pull Request #${pr.number}",
            true,
            project,
        )
        val planned = made.takeIf { ran }
        if (planned == null) {
            // Reverted rather than left standing: the picker naming one pull request while the plan
            // is another one's is how a review gets posted somewhere nobody chose.
            trouble.text = broke?.let { "Could not read #${pr.number}: ${reason(it)}" }
                ?: "Reading #${pr.number} was cancelled"
            withoutListening {
                picker.selectedItem = plan.pullRequest
                fileOnly.isSelected = plan.fileOnly
            }
            troubleRow.visible(true)
            return
        }
        read[key] = planned
        adopt(planned)
    }

    private fun adopt(made: GitHubDestination.Plan) {
        plan = made
        describe()
    }

    private fun describe() {
        repository.text = plan.slug
        going.text = going()
        warning.text = elsewhere()
        warningRow.visible(plan.pullRequest.number != branchPullRequest)
        troubleRow.visible(false)
    }

    /**
     * Why a pull request that is not the branch's own is worth a word.
     *
     * The lines a comment names were counted in this working tree, and the pull request's diff can
     * only say that a line is in it, not that it is the same line - so a comment that passes that
     * check lands somewhere real and wrong, which GitHub accepts without a word.
     */
    private fun elsewhere(): String {
        val ending = "so a comment may land on an unrelated line."
        if (branchPullRequest == null) return "Your branch has no pull request of its own, $ending"
        return "#${plan.pullRequest.number} is not your branch's pull request, $ending"
    }

    private fun going(): String = buildString {
        append("${comments(plan.count)} to ${plan.at}, against ${plan.head.take(SHORT_SHA)}.")
        // The same words the balloon uses afterwards, so the two accounts of a post agree.
        if (plan.onFile > 0) append(" ${plan.onFile} on the file, their lines not being in the diff.")
        if (plan.alreadyThere > 0) append(" ${comments(plan.alreadyThere)} already there, left out.")
    }

    private fun withoutListening(change: () -> Unit) {
        val was = listening
        listening = false
        try {
            change()
        } finally {
            listening = was
        }
    }

    private fun label(pr: GitHubDestination.PullRequest): String = buildString {
        append("#${pr.number}")
        if (pr.title.isNotBlank()) append(" ${pr.title}")
        if (pr.branch.isNotBlank()) append(" (${pr.branch})")
        if (pr.draft) append(" - draft")
    }

    private companion object {
        const val SHORT_SHA = 7
    }
}
