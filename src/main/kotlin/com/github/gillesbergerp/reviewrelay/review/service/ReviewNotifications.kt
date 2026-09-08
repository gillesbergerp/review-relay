package com.github.gillesbergerp.reviewrelay.review.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Must match the notificationGroup id declared in plugin.xml. */
const val REVIEW_NOTIFICATIONS = "ReviewRelay"

/**
 * What went wrong, in words.
 *
 * A message is nullable and an exception's class name is not something to show a reviewer, so both
 * used to reach a balloon: "Could not send the review: null".
 */
fun reason(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: "it failed without saying why"

/** One rule for counting comments, rather than "comment(s)" in some places and a plural in others. */
fun comments(count: Int): String = if (count == 1) "1 comment" else "$count comments"

/** Balloons for the review, wherever they come from: the backend is not involved in saying things. */
fun notifyReview(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(REVIEW_NOTIFICATIONS)
        .createNotification(message, type)
        .notify(project)
}
