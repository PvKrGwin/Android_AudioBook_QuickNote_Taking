package com.praveen.ridernotes.desktop

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
                .put("start", range.start).put("end", range.end)
                .put("bold", style.fontWeight == FontWeight.Bold)
                .put("italic", style.fontStyle == FontStyle.Italic)
                .put("underline", style.textDecoration?.contains(TextDecoration.Underline) == true)
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

    fun bullets(value: TextFieldValue): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        if (start == end) {
            val lineStart = value.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
            if (value.text.startsWith("• ", lineStart)) return value
            return replaceRich(value, lineStart, lineStart, "• ", start + 2, start + 2)
        }
        val selected = value.text.substring(start, end)
        val replacement = selected.lines().joinToString("\n") { if (it.startsWith("• ")) it else "• $it" }
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

    fun styleInserted(previous: TextFieldValue, next: TextFieldValue, style: SpanStyle): TextFieldValue {
        val replacedLength = previous.selection.max - previous.selection.min
        val insertedLength = next.text.length - (previous.text.length - replacedLength)
        if (insertedLength <= 0) return next
        val start = previous.selection.min.coerceIn(0, next.text.length)
        val end = (start + insertedLength).coerceIn(start, next.text.length)
        val builder = AnnotatedString.Builder().apply { append(next.annotatedString) }
        builder.addStyle(style, start, end)
        return next.copy(annotatedString = builder.toAnnotatedString())
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
        textDecoration = TextDecoration.Underline.takeIf { json.optBoolean("underline") },
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

    private fun fontName(font: FontFamily?): String? = when (font) {
        FontFamily.Serif -> "SERIF"
        FontFamily.Monospace -> "MONOSPACE"
        FontFamily.Cursive -> "CURSIVE"
        else -> null
    }
}
