package com.github.gillesbergerp.reviewrelay.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

/**
 * Renders comment prose as Markdown.
 *
 * Uses Swing's own HTML kit with a stylesheet built from the current theme rather than an IDE
 * helper, so the styling is explicit and there is no platform API to drift.
 */
class MarkdownPane(markdown: String, font: Font, foreground: Color, codeBackground: Color) : JEditorPane() {

    init {
        editorKit = HTMLEditorKit()
        isEditable = false
        // Never shown, but still a caret: selecting and copying a reply keeps working.
        caret = object : DefaultCaret() {
            override fun setVisible(visible: Boolean) = super.setVisible(false)
        }.apply { blinkRate = 0 }
        isOpaque = false
        border = JBUI.Borders.empty()
        // Its own, against the arrow a card sets for the controls it holds: prose is the one thing
        // on a card you can select, and it has to say so before you try.
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        // Prose is not a stop on the way to a button: a non-editable JEditorPane takes focus by
        // default, which put every paragraph of a review in the tab order ahead of every control.
        // Focus is taken back on the press that could start a selection, in processMouseEvent.
        isFocusable = false
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                isFocusable = false
            }
        })
        // A link that does nothing when clicked is worse than no link at all.
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.url?.let { BrowserUtil.browse(it) }
            }
        }
        (document as HTMLDocument).styleSheet.addRule(styleSheet(font, foreground, codeBackground))
        text = "<html><body>" + toHtml(markdown) + "</body></html>"
        caretPosition = 0
    }

    /**
     * Selecting prose needs the caret, and the caret needs focus, which an unfocusable pane can
     * never take - so a comment could not be selected or copied at all.
     *
     * Done here rather than from a mouse listener: the caret's own listener is installed first and
     * would take the press while the pane was still refusing focus, losing the first drag.
     */
    override fun processMouseEvent(e: MouseEvent) {
        if (e.id == MouseEvent.MOUSE_PRESSED && !isFocusable) {
            isFocusable = true
            requestFocusInWindow()
        }
        super.processMouseEvent(e)
    }

    private fun styleSheet(font: Font, foreground: Color, codeBackground: Color): String {
        val body = hex(foreground)
        val code = hex(codeBackground)
        val link = hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val rule = hex(JBColor.border())
        val mono = EditorColorsManager.getInstance().globalScheme.editorFontName
        return "body { font-family: '" + font.family + "'; font-size: " + font.size + "pt; color: " + body + "; margin: 0; }" +
            "p { margin: 0 0 " + JBUI.scale(4) + "px 0; }" +
            "a { color: " + link + "; }" +
            "ul, ol { margin: 0 0 " + JBUI.scale(4) + "px " + JBUI.scale(18) + "px; padding: 0; }" +
            "li { margin: 0; }" +
            "code { font-family: '" + mono + "'; background-color: " + code + "; }" +
            "pre { font-family: '" + mono + "'; background-color: " + code + "; margin: 0; padding: " + JBUI.scale(4) + "px; }" +
            // Enough of a step to read as structure. An answer's own headings were all one size.
            "h1 { font-size: " + (font.size + 4) + "pt; margin: 0 0 " + JBUI.scale(4) + "px 0; }" +
            "h2 { font-size: " + (font.size + 2) + "pt; margin: 0 0 " + JBUI.scale(4) + "px 0; }" +
            "h3, h4, h5, h6 { font-size: " + (font.size + 1) + "pt; margin: 0 0 " + JBUI.scale(4) + "px 0; }" +
            "blockquote { margin: 0 0 " + JBUI.scale(4) + "px " + JBUI.scale(8) + "px; color: " + body + "; }" +
            "hr { border: 0; border-top: 1px solid " + rule + "; }" +
            "table { border-collapse: collapse; }" +
            "th, td { border: 1px solid " + rule + "; padding: " + JBUI.scale(2) + "px " + JBUI.scale(4) + "px; }"
    }

    private fun hex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}

private fun toHtml(markdown: String): String {
    val source = hardBreaks(markdown)
    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    // An agent writes this, and the generator passes raw HTML through: an <img> in a reply would
    // have the pane fetch a remote URL the moment the card is drawn.
    return withoutRemoteImages(HtmlGenerator(source, tree, flavour, false).generateHtml())
}

/**
 * A line break where one was typed.
 *
 * Markdown reads a single newline as a space, which is right for prose written in paragraphs and
 * wrong for a comment box: a comment typed on two lines came out as one. Two trailing spaces are
 * Markdown's own hard break, so the text stays Markdown rather than becoming HTML.
 */
internal fun hardBreaks(markdown: String): String {
    val lines = markdown.lines()
    var fenced = false
    return lines.mapIndexed { index, line ->
        // Inside a fence every character is content, including the ones that would end a paragraph.
        if (FENCE.matches(line)) {
            fenced = !fenced
            return@mapIndexed line
        }
        val next = lines.getOrNull(index + 1)
        val continues = !fenced && line.isNotBlank() && next != null && next.isNotBlank() &&
            !FENCE.matches(next) && !STRUCTURE.containsMatchIn(next) && !line.endsWith("  ")
        if (continues) "$line  " else line
    }.joinToString("\n")
}

private val FENCE = Regex("""^\s*`{3,}.*$""")

/** A line that starts a block of its own is already a break; two spaces before it change nothing. */
private val STRUCTURE = Regex("""^\s*(#{1,6}\s|[-*+]\s|\d+[.)]\s|>|\||-{3,}\s*$)""")

private val REMOTE_IMAGE =
    Regex("""<img[^>]*\ssrc\s*=\s*["']?\s*(?:https?:)?//[^>]*>""", RegexOption.IGNORE_CASE)

/** Drops an image the pane would go to the network for, leaving everything else it wrote. */
internal fun withoutRemoteImages(html: String): String = REMOTE_IMAGE.replace(html, "")
