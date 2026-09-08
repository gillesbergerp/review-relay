package com.github.gillesbergerp.reviewrelay.ui

import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A painted control has somewhere to put its name.
 *
 * A bare JComponent answers `getAccessibleContext()` with null, so naming one threw at construction
 * - which took down the whole tool window and every comment drawn beside the code.
 */
class AccessibleNamesTest {

    @Test
    fun `the unread dot can be named`() {
        val dot = Dot(Color.BLUE)

        assertNotNull("a control with no accessible context cannot be named", dot.accessibleContext)

        dot.accessibleContext.accessibleName = "Unread answer"
        assertEquals("Unread answer", dot.accessibleContext.accessibleName)
    }
}
