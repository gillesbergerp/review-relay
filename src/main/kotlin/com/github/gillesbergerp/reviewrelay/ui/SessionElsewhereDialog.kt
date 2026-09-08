package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages

/**
 * Asks before sending a review to a session standing somewhere other than the code it is about.
 *
 * The same question whichever agent is driven, and asked here rather than by the agent: one may
 * prompt for permission of its own and one may not, and the ones that do prompt after the review has
 * arrived, in their own window. This is the last point at which the reviewer can still say no.
 */
fun sendingElsewhereAccepted(
    project: Project,
    sessionLabel: String?,
    sessionDirectory: String?,
    reviewDirectory: String,
): Boolean {
    if (sessionDirectory.isNullOrBlank()) return true
    if (PathMatch.same(sessionDirectory, reviewDirectory)) return true
    // A session that legitimately lives a directory up would otherwise raise this on every send,
    // forever; the answer is remembered per pair of directories, not blanket.
    val remembered = "ReviewRelay.sendElsewhere.${PathMatch.normalize(sessionDirectory).lowercase()}"
    if (PropertiesComponent.getInstance(project).getBoolean(remembered)) return true

    val named = sessionLabel?.takeIf { it.isNotBlank() }?.let { "The session \"$it\"" } ?: "The session"
    // Cancel is the default: Enter pressed out of habit used to send a review at code the session
    // is not standing in, and that cannot be taken back.
    val doNotAsk = object : DoNotAskOption.Adapter() {
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
            // Only when they said yes: remembering a refusal would silently stop every later send.
            if (isSelected && exitCode == 0) {
                PropertiesComponent.getInstance(project).setValue(remembered, true)
            }
        }

        override fun getDoNotShowMessage(): String = "Do not ask again for this session directory"
    }

    return Messages.showDialog(
        project,
        buildString {
            appendLine("$named runs in")
            appendLine(sessionDirectory)
            appendLine()
            appendLine("but these comments are about files in")
            append(reviewDirectory)
        },
        "Session Is in a Different Directory",
        arrayOf("Send Anyway", "Cancel"),
        1,
        Messages.getWarningIcon(),
        doNotAsk,
    ) == 0
}
