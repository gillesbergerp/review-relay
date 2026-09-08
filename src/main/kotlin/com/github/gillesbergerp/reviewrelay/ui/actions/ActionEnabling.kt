package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.actionSystem.AnActionEvent

/** Shown out of the project, enabled once there is a review to step through. */
internal fun enableWhenThereAreComments(e: AnActionEvent) {
    val project = e.project
    e.presentation.isVisible = project != null
    e.presentation.isEnabled = project != null &&
        ReviewSessionService.getInstance(project).currentSession.threads.isNotEmpty()
}
