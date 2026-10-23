package com.voicereader.parse

/** Минимальный конвертер разметки в читаемый текст — без внешних зависимостей и без сети. */
object Html {

    private val SKIP_BLOCKS = Regex(
        "<(script|style|head|svg)[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val BLOCK_TAGS = Regex(
        "</?(p|div|br|li|tr|h[1-6]|section|article|blockquote|title|td)[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val ANY_TAG = Regex("<[^>]+>")
    private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    fun toText(markup: String): String {
        var s = markup
        s = COMMENTS.replace(s, " ")
        s = SKIP_BLOCKS.replace(s, " ")
        s = BLOCK_TAGS.replace(s, "\n")
        s = ANY_TAG.replace(s, " ")
        s = unescape(s)
        return s.split('\n').joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }

    /** Заголовок из <title> или первого <h1>/<h2>. */
    fun title(markup: String): String? {
        val m = Regex("<(title|h1|h2)[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(markup) ?: return null
        return unescape(ANY_TAG.replace(m.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
    }

    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val end = s.indexOf(';', i + 1)
            if (end == -1 || end - i > 10) { sb.append(c); i++; continue }
            val entity = s.substring(i + 1, end)
            val replacement = when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED[entity.lowercase()]
            }
            if (replacement != null) { sb.append(replacement); i = end + 1 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»",
        "hellip" to "…", "rsquo" to "'", "lsquo" to "'", "ldquo" to "“", "rdquo" to "”",
        "shy" to "", "copy" to "©", "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×"
    )
}
