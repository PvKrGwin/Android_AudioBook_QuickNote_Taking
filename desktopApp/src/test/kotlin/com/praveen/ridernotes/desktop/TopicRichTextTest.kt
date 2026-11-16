package com.praveen.ridernotes.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicRichTextTest {
    @Test
    fun italicAndUnderlineCanBeRemovedAndStayRemovedAfterReload() {
        val builder = AnnotatedString.Builder("formatted text")
        builder.addStyle(
            SpanStyle(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline),
            0,
            9
        )
        var value = TextFieldValue(builder.toAnnotatedString(), selection = TextRange(0, 9))
        assertTrue(TopicRichText.selectionIsItalic(value))
        assertTrue(TopicRichText.selectionIsUnderlined(value))

        value = TopicRichText.setItalic(value, false)
        value = TopicRichText.setUnderline(value, false)
        val reloaded = TopicRichText.decode(value.text, TopicRichText.encode(value))
            .copy(selection = TextRange(0, 9))

        assertFalse(TopicRichText.selectionIsItalic(reloaded))
        assertFalse(TopicRichText.selectionIsUnderlined(reloaded))
    }

    @Test
    fun activeTypingStyleContinuesAndOlderFormattingSurvivesEditingAndReload() {
        val builder = AnnotatedString.Builder("old text")
        builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), 0, 3)
        builder.addStyle(SpanStyle(color = Color.Red), 4, 8)
        val previous = TextFieldValue(builder.toAnnotatedString(), selection = TextRange(8))
        val proposed = TextFieldValue("old text blue", selection = TextRange(13))

        val edited = TopicRichText.reconcileEdit(
            previous,
            proposed,
            SpanStyle(color = Color.Blue)
        )
        val reloaded = TopicRichText.decode(edited.text, TopicRichText.encode(edited))

        assertTrue(
            TopicRichText.selectionIsItalic(reloaded.copy(selection = TextRange(0, 3)))
        )
        assertEquals(Color.Red, effectiveStyleAt(reloaded, 5).color)
        assertEquals(Color.Blue, effectiveStyleAt(reloaded, 10).color)
    }

    private fun effectiveStyleAt(value: TextFieldValue, index: Int): SpanStyle {
        var style = SpanStyle()
        value.annotatedString.spanStyles.forEach { range ->
            if (index >= range.start && index < range.end) style = style.merge(range.item)
        }
        return style
    }
}
