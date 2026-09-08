package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.REVIEW_ID
import com.intellij.ui.content.Content

/** The tool window this plugin owns; the context menu group is shared with every other one. */
internal const val REVIEW_RELAY = "Review Relay"

internal fun Content?.reviewId(): ReviewId? = this?.getUserData(REVIEW_ID)
