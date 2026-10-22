package com.jarvis.assistant.ui.chat

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.text.style.QuoteSpan
import android.text.style.UnderlineSpan
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object ChatMessageFormatter {
    data class FileArtifact(val fileName: String, val language: String, val content: String)

    private val fencePattern = Regex("```([^\\r\\n`]*)\\r?\\n([\\s\\S]*?)```")
    private val stringPattern = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
    private val commentPattern = Regex("(?m)(//.*$|#.*$|/\\*[\\s\\S]*?\\*/)")
    private val numberPattern = Regex("\\b\\d+(?:\\.\\d+)?\\b")
    private val keywordPattern = Regex("\\b(fun|val|var|class|object|data|return|if|else|when|for|while|try|catch|throw|import|package|public|private|protected|static|final|const|let|function|async|await|new|true|false|null|None|def|from|as|in|is|interface|override|suspend|companion|enum|type|return|export|default)\\b")
    private val functionPattern = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?=\\()")
    private val linkPattern = Regex("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)")

    fun render(textView: TextView, text: String) {
        val result = SpannableStringBuilder()
        var cursor = 0
        val matches = fencePattern.findAll(text).toList()
        for (match in matches) {
            if (match.range.first > cursor) {
                appendProse(result, text.substring(cursor, match.range.first))
            }
            val code = match.groupValues[2].trimEnd('\n', '\r')
            val start = result.length
            result.append(code)
            val end = result.length
            if (end > start) {
                result.setSpan(BackgroundColorSpan(Color.rgb(21, 27, 39)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(ForegroundColorSpan(Color.rgb(223, 231, 245)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                highlight(result, code, start)
                result.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val context = widget.context
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis code", code))
                        Toast.makeText(context, "Kod bloğu kopyalandı", Toast.LENGTH_SHORT).show()
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                    }
                }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) appendProse(result, text.substring(cursor))
        if (matches.isEmpty()) {
            // Empty input is still a valid render result; otherwise the rich-text path handles all prose.
            if (text.isEmpty()) result.clear()
        }
        textView.text = result
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.linksClickable = true
    }

    /** Convert the common prose subset of Markdown into real text spans without changing code blocks. */
    private fun appendProse(out: SpannableStringBuilder, source: String) {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        lines.forEachIndexed { index, rawLine ->
            val heading = Regex("^\\s{0,3}(#{1,3})\\s+(.+?)\\s*#*\\s*$").matchEntire(rawLine)
            val unordered = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(rawLine)
            val ordered = Regex("^\\s*(\\d+[.)])\\s+(.+)$").matchEntire(rawLine)
            val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(rawLine)
            when {
                heading != null -> {
                    val start = out.length
                    appendInlineMarkdown(out, heading.groupValues[2])
                    val end = out.length
                    if (end > start) {
                        out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(RelativeSizeSpan(when (heading.groupValues[1].length) { 1 -> 1.18f; 2 -> 1.10f; else -> 1.04f }), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                unordered != null -> {
                    out.append("  • ")
                    appendInlineMarkdown(out, unordered.groupValues[1])
                }
                ordered != null -> {
                    out.append("  ").append(ordered.groupValues[1]).append(' ')
                    appendInlineMarkdown(out, ordered.groupValues[2])
                }
                quote != null -> {
                    val start = out.length
                    out.append("  ")
                    appendInlineMarkdown(out, quote.groupValues[1])
                    val end = out.length
                    if (end > start) {
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(QuoteSpan(Color.rgb(150, 160, 177)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                else -> appendInlineMarkdown(out, rawLine)
            }
            if (index < lines.lastIndex) out.append('\n')
        }
    }

    /** Handles bold, italic, inline code and Markdown links with bounds-safe scans. */
    private fun appendInlineMarkdown(out: SpannableStringBuilder, source: String) {
        var i = 0
        while (i < source.length) {
            val link = if (source[i] == '[') linkPattern.find(source, i)?.takeIf { it.range.first == i } else null
            if (link != null) {
                val start = out.length
                out.append(link.groupValues[1])
                val end = out.length
                val url = link.groupValues[2]
                if (end > start) {
                    out.setSpan(ForegroundColorSpan(Color.rgb(96, 157, 244)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            try {
                                widget.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            } catch (_: Exception) {
                                Toast.makeText(widget.context, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = true }
                    }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i = link.range.last + 1
                continue
            }

            val marker = when {
                source.startsWith("**", i) -> "**"
                source.startsWith("__", i) -> "__"
                source[i] == '`' -> "`"
                source[i] == '*' -> "*"
                else -> ""
            }
            if (marker.isNotEmpty()) {
                val close = source.indexOf(marker, i + marker.length)
                if (close > i + marker.length) {
                    val content = source.substring(i + marker.length, close)
                    val start = out.length
                    if (marker == "`") out.append(content) else appendInlineMarkdown(out, content)
                    val end = out.length
                    if (end > start) {
                        when (marker) {
                            "**", "__" -> out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            "*" -> out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            "`" -> {
                                out.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                out.setSpan(BackgroundColorSpan(Color.rgb(236, 239, 244)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                out.setSpan(ForegroundColorSpan(Color.rgb(62, 73, 91)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                    }
                    i = close + marker.length
                    continue
                }
            }
            out.append(source[i])
            i++
        }
    }

    fun extractFirstFile(text: String): FileArtifact? {
        for (match in fencePattern.findAll(text)) {
            val header = match.groupValues[1].trim()
            val fileMatch = Regex("(?:^|\\s)filename\\s*=\\s*['\"]?([^\\s'\"]+)", RegexOption.IGNORE_CASE).find(header)
                ?: Regex("(?:^|\\s)filename\\s*:\\s*['\"]?([^\\s'\"]+)", RegexOption.IGNORE_CASE).find(header)
            val fileName = fileMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val language = header.substringBefore(' ').trim().ifBlank { "text" }
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(90)
            if (safeName.isBlank() || safeName == "." || safeName == "..") continue
            return FileArtifact(safeName, language, match.groupValues[2].trimEnd('\n', '\r'))
        }
        return null
    }

    private fun highlight(out: SpannableStringBuilder, code: String, absoluteStart: Int) {
        fun apply(regex: Regex, color: Int) {
            regex.findAll(code).forEach { m ->
                out.setSpan(ForegroundColorSpan(color), absoluteStart + m.range.first, absoluteStart + m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Strings/comments are painted first so their colors win over keyword matches.
        apply(stringPattern, Color.rgb(124, 220, 151))
        apply(commentPattern, Color.rgb(127, 143, 164))
        apply(numberPattern, Color.rgb(255, 174, 102))
        apply(keywordPattern, Color.rgb(95, 193, 255))
        apply(functionPattern, Color.rgb(204, 164, 255))
    }
}
