package com.github.gillesbergerp.reviewrelay.ui

import java.awt.Color
import kotlin.math.roundToInt

/**
 * Surface tints for the review UI, derived from the theme rather than fixed RGB.
 *
 * A comment's type is a word and its status is the one coloured thing about it; nothing here gives
 * a hue to either.
 */
object CommentPalette {

    /** Nudges a surface away from itself, so an inset block reads as inset without adding a hue. */
    fun shade(base: Color, strength: Float): Color =
        blend(base, if (isDark(base)) Color.WHITE else Color.BLACK, strength)

    private fun blend(base: Color, overlay: Color, ratio: Float): Color = Color(
        (base.red * (1 - ratio) + overlay.red * ratio).roundToInt().coerceIn(0, 255),
        (base.green * (1 - ratio) + overlay.green * ratio).roundToInt().coerceIn(0, 255),
        (base.blue * (1 - ratio) + overlay.blue * ratio).roundToInt().coerceIn(0, 255),
    )

    private fun isDark(color: Color): Boolean =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) < 150
}
