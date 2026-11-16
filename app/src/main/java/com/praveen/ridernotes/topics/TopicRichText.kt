package com.praveen.ridernotes.topics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import org.json.JSONArray
import org.json.JSONObject

internal object TopicRichText {
    fun decode(text: String, encoded: String?): TextFieldValue {
        if (encoded.isNullOrBlank()) return TextFieldValue(text)
        return runCatching {
            val root = JSONObject(encoded)
            val savedText = root.optString("text", text)
            val builder = AnnotatedString.Builder(savedText)
            val spans = root.optJSONArray("spans") ?: JSONArray()
            repeat(spans.length()) { index ->
                val span = spans.getJSONObject(index)
                val start = span.optInt("start").coerceIn(0, savedText.length)
                val end = span.optInt("end").coerceIn(start, savedText.length)
                builder.addStyle(styleFrom(span), start, end)
            }
            TextFieldValue(builder.toAnnotatedString())
        }.getOrDefault(TextFieldValue(text))
    }

    fun encode(value: TextFieldValue): String? {
        if (value.annotatedString.spanStyles.isEmpty()) return null
        val spans = JSONArray()
        value.annotatedString.spanStyles.forEach { range ->
            val style = range.item
            spans.put(JSONObject()
                .put("start", range.start)
                .put("end", range.end)
                .put("bold", style.fontWeight == FontWeight.Bold)
                .put("italic", style.fontStyle == FontStyle.Italic)
                .put("underline", style.textDecoration?.contains(TextDecoration.Underline) == true)
                .put("strike", style.textDecoration?.contains(TextDecoration.LineThrough) == true)
                .put("color", style.color.takeUnless { it == Color.Unspecified }?.value?.toString() ?: JSONObject.NULL)
                .put("font", fontName(style.fontFamily)))
        }
        return JSONObject().put("version", 1).put("text", value.text).put("spans", spans).toString()
    }

    fun apply(value: TextFieldValue, style: SpanStyle): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        if (start == end) return value
        val builder = AnnotatedString.Builder().apply { append(value.annotatedString) }
        builder.addStyle(style, start, end)
        return value.copy(annotatedString = builder.toAnnotatedString())
    }

    fun selectionIsBold(value: TextFieldValue): Boolean = selectionHas(value) {
        it.fontWeight == FontWeight.Bold
    }

    fun selectionIsItalic(value: TextFieldValue): Boolean = selectionHas(value) {
        it.fontStyle == FontStyle.Italic
    }

    fun selectionIsUnderlined(value: TextFieldValue): Boolean = selectionHas(value) {
        it.textDecoration?.contains(TextDecoration.Underline) == true
    }

    fun selectionIsStruck(value: TextFieldValue): Boolean = selectionHas(value) {
        it.textDecoration?.contains(TextDecoration.LineThrough) == true
    }

    fun setBold(value: TextFieldValue, enabled: Boolean): TextFieldValue =
        rewriteSelection(value) { it.copy(fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal) }

    fun setItalic(value: TextFieldValue, enabled: Boolean): TextFieldValue =
        rewriteSelection(value) { it.copy(fontStyle = if (enabled) FontStyle.Italic else FontStyle.Normal) }

    fun setUnderline(value: TextFieldValue, enabled: Boolean): TextFieldValue =
        rewriteSelection(value) { it.copy(textDecoration = toggleDecoration(it.textDecoration, TextDecoration.Underline, enabled)) }

    fun setStrike(value: TextFieldValue, enabled: Boolean): TextFieldValue =
        rewriteSelection(value) { it.copy(textDecoration = toggleDecoration(it.textDecoration, TextDecoration.LineThrough, enabled)) }

    /**
     * Compose may return a new TextFieldValue containing only plain text after an edit.
     * Rebuild it from the previous annotated value so existing formatting survives, then
     * apply the currently selected typing style only to the newly inserted characters.
     */
    fun reconcileEdit(
        previous: TextFieldValue,
        proposed: TextFieldValue,
        typingStyle: SpanStyle?
    ): TextFieldValue {
        if (previous.text == proposed.text) {
            return previous.copy(selection = proposed.selection, composition = proposed.composition)
        }

        val selectionStart = previous.selection.min
        val selectionEnd = previous.selection.max
        val insertedLength = proposed.text.length - (previous.text.length - (selectionEnd - selectionStart))
        val selectionBased = insertedLength >= 0 &&
            proposed.text.take(selectionStart) == previous.text.take(selectionStart) &&
            proposed.text.drop(selectionStart + insertedLength) == previous.text.drop(selectionEnd)

        val start: Int
        val oldEnd: Int
        val newEnd: Int
        if (selectionBased) {
            start = selectionStart
            oldEnd = selectionEnd
            newEnd = selectionStart + insertedLength
        } else {
            var prefix = 0
            val shared = minOf(previous.text.length, proposed.text.length)
            while (prefix < shared && previous.text[prefix] == proposed.text[prefix]) prefix++
            var suffix = 0
            while (
                suffix < previous.text.length - prefix &&
                suffix < proposed.text.length - prefix &&
                previous.text[previous.text.lastIndex - suffix] == proposed.text[proposed.text.lastIndex - suffix]
            ) suffix++
            start = prefix
            oldEnd = previous.text.length - suffix
            newEnd = proposed.text.length - suffix
        }

        val builder = AnnotatedString.Builder()
        builder.append(previous.annotatedString.subSequence(0, start))
        val insertedStart = builder.length
        builder.append(proposed.text.substring(start, newEnd))
        if (typingStyle != null && builder.length > insertedStart) {
            builder.addStyle(typingStyle, insertedStart, builder.length)
        }
        builder.append(previous.annotatedString.subSequence(oldEnd, previous.text.length))
        return proposed.copy(annotatedString = builder.toAnnotatedString())
    }

    fun bullets(value: TextFieldValue): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        if (start == end) {
            val lineStart = value.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
            if (value.text.startsWith("• ", lineStart)) return value
            return replaceRich(value, lineStart, lineStart, "• ", start + 2, start + 2)
        }
        val selected = value.text.substring(start, end)
        val replacement = selected.lines().joinToString("\n") { line ->
            if (line.startsWith("• ")) line else "• $line"
        }
        return replaceRich(value, start, end, replacement, start, start + replacement.length)
    }

    fun continueBullets(previous: TextFieldValue, next: TextFieldValue, enabled: Boolean): TextFieldValue {
        if (!enabled || !previous.selection.collapsed) return next
        val at = previous.selection.start
        val inserted = next.text.length == previous.text.length + 1 &&
            at < next.text.length && next.text[at] == '\n'
        if (!inserted) return next
        val lineStart = previous.text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)) + 1
        if (!previous.text.startsWith("• ", lineStart)) return next
        val cursor = next.selection.start
        return replaceRich(next, cursor, cursor, "• ", cursor + 2, cursor + 2)
    }

    private fun selectionHas(value: TextFieldValue, predicate: (SpanStyle) -> Boolean): Boolean {
        val start = value.selection.min
        val end = value.selection.max
        if (start == end) return false
        return (start until end).all { index -> predicate(effectiveStyleAt(value, index)) }
    }

    private fun rewriteSelection(
        value: TextFieldValue,
        transform: (SpanStyle) -> SpanStyle
    ): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        if (start == end) return value
        val boundaries = buildSet {
            add(0); add(value.text.length); add(start); add(end)
            value.annotatedString.spanStyles.forEach { range ->
                add(range.start.coerceIn(0, value.text.length))
                add(range.end.coerceIn(0, value.text.length))
            }
        }.sorted()
        val builder = AnnotatedString.Builder(value.text)
        boundaries.zipWithNext().forEach { (segmentStart, segmentEnd) ->
            if (segmentStart == segmentEnd) return@forEach
            var style = effectiveStyleAt(value, segmentStart)
            if (segmentStart >= start && segmentEnd <= end) style = transform(style)
            builder.addStyle(style, segmentStart, segmentEnd)
        }
        return value.copy(annotatedString = builder.toAnnotatedString())
    }

    private fun effectiveStyleAt(value: TextFieldValue, index: Int): SpanStyle {
        var style = SpanStyle()
        value.annotatedString.spanStyles.forEach { range ->
            if (index >= range.start && index < range.end) style = style.merge(range.item)
        }
        return style
    }

    private fun replaceRich(
        value: TextFieldValue,
        start: Int,
        end: Int,
        replacement: String,
        selectionStart: Int,
        selectionEnd: Int
    ): TextFieldValue {
        val builder = AnnotatedString.Builder()
        builder.append(value.annotatedString.subSequence(0, start))
        builder.append(replacement)
        builder.append(value.annotatedString.subSequence(end, value.text.length))
        return value.copy(
            annotatedString = builder.toAnnotatedString(),
            selection = TextRange(selectionStart, selectionEnd),
            composition = null
        )
    }

    private fun styleFrom(json: JSONObject) = SpanStyle(
        fontWeight = FontWeight.Bold.takeIf { json.optBoolean("bold") },
        fontStyle = FontStyle.Italic.takeIf { json.optBoolean("italic") },
        textDecoration = decoration(
            underline = json.optBoolean("underline"),
            strike = json.optBoolean("strike")
        ),
        color = json.optString("color").takeIf(String::isNotBlank)?.let {
            runCatching { Color(it.toULong()) }.getOrDefault(Color.Unspecified)
        } ?: Color.Unspecified,
        fontFamily = when (json.optString("font")) {
            "SERIF" -> FontFamily.Serif
            "MONOSPACE" -> FontFamily.Monospace
            "CURSIVE" -> FontFamily.Cursive
            else -> null
        }
    )

    private fun decoration(underline: Boolean, strike: Boolean): TextDecoration? {
        val values = mutableListOf<TextDecoration>()
        if (underline) values += TextDecoration.Underline
        if (strike) values += TextDecoration.LineThrough
        return when (values.size) {
            0 -> null
            1 -> values.first()
            else -> TextDecoration.combine(values)
        }
    }

    private fun toggleDecoration(current: TextDecoration?, target: TextDecoration, enabled: Boolean): TextDecoration {
        val underline = if (target == TextDecoration.Underline) enabled
            else current?.contains(TextDecoration.Underline) == true
        val strike = if (target == TextDecoration.LineThrough) enabled
            else current?.contains(TextDecoration.LineThrough) == true
        return decoration(underline, strike) ?: TextDecoration.None
    }

    private fun fontName(font: FontFamily?): String? = when (font) {
        FontFamily.Serif -> "SERIF"
        FontFamily.Monospace -> "MONOSPACE"
        FontFamily.Cursive -> "CURSIVE"
        else -> null
    }
}
