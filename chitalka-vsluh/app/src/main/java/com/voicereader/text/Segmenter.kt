package com.voicereader.text

import com.voicereader.AppText
import com.voicereader.R

import com.voicereader.data.BookContent
import com.voicereader.data.Chapter
import java.text.BreakIterator
import java.util.Locale

/** Разбиение текста книги на предложения — единицы озвучивания и подсветки. */
object Segmenter {

    /** TTS-движки плохо переваривают очень длинные строки, поэтому режем их принудительно. */
    private const val MAX_SENTENCE = 320
    private const val MIN_SENTENCE = 2

    data class RawChapter(val title: String, val text: String)

    fun build(chapters: List<RawChapter>, languageHint: String?): BookContent {
        val joined = chapters.joinToString("\n") { it.text }
        val language = languageHint?.take(2)?.lowercase()?.takeIf { it.isNotBlank() } ?: detectLanguage(joined)
        val locale = Locale.forLanguageTag(language)

        val sentences = ArrayList<String>()
        val marks = ArrayList<Chapter>()
        chapters.forEach { chapter ->
            val start = sentences.size
            val parts = splitToSentences(chapter.text, locale)
            if (parts.isEmpty()) return@forEach
            marks += Chapter(chapter.title.ifBlank { AppText.get(R.string.chapter_n, marks.size + 1) }, start)
            sentences += parts
        }
        return BookContent(sentences, marks, language)
    }

    fun splitToSentences(text: String, locale: Locale): List<String> {
        val out = ArrayList<String>()
        // Абзацы обрабатываем отдельно: предложение не должно «перетекать» через перевод строки.
        text.split('\n').forEach { paragraph ->
            val clean = paragraph.replace(Regex("[\\t\\u00a0\\u200b]+"), " ")
                .replace(Regex(" {2,}"), " ")
                .trim()
            if (clean.length < MIN_SENTENCE) return@forEach
            val it = BreakIterator.getSentenceInstance(locale)
            it.setText(clean)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                val piece = clean.substring(start, end).trim()
                if (piece.length >= MIN_SENTENCE) out += hardWrap(piece)
                start = end
                end = it.next()
            }
        }
        return out
    }

    /** Режет слишком длинное предложение по знакам препинания, а в крайнем случае по пробелам. */
    private fun hardWrap(sentence: String): List<String> {
        if (sentence.length <= MAX_SENTENCE) return listOf(sentence)
        val result = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_SENTENCE) {
            val window = rest.substring(0, MAX_SENTENCE)
            val cut = listOf("; ", ", ", " — ", ": ", " ").firstNotNullOfOrNull { sep ->
                window.lastIndexOf(sep).takeIf { it > MAX_SENTENCE / 3 }?.plus(sep.length)
            } ?: MAX_SENTENCE
            result += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) result += rest
        return result
    }

    /** Грубое, но достаточное определение языка по алфавиту — нужно для выбора голоса. */
    fun detectLanguage(text: String): String {
        var cyrillic = 0
        var latin = 0
        val sample = text.take(20000)
        for (ch in sample) {
            when (ch) {
                in 'а'..'я', in 'А'..'Я', 'ё', 'Ё' -> cyrillic++
                in 'a'..'z', in 'A'..'Z' -> latin++
            }
        }
        return if (cyrillic >= latin) "ru" else "en"
    }
}
