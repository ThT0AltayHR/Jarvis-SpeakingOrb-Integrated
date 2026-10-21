package com.jarvis.assistant.ui.chat

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
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

    fun render(textView: TextView, text: String) {
        val result = SpannableStringBuilder()
        var cursor = 0
        val matches = fencePattern.findAll(text).toList()
        if (matches.isEmpty()) {
            textView.text = text
            return
        }
        for (match in matches) {
            if (match.range.first > cursor) result.append(text.substring(cursor, match.range.first))
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
        if (cursor < text.length) result.append(text.substring(cursor))
        textView.text = result
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.linksClickable = true
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
