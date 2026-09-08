package com.github.gillesbergerp.reviewrelay.review.changes

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId

private val GIT = PluginId.getId("Git4Idea")

/**
 * Whether the Git plugin is here to be used.
 *
 * The dependency on it is optional, so the review loop still loads without it. Nothing that touches
 * git4idea may be loaded when this is false: the classloader has no such classes to give.
 */
internal fun gitAvailable(): Boolean =
    PluginManager.getInstance().findEnabledPlugin(GIT) != null
