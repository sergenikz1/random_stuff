package com.voicereader.text

/**
 * Быстрое чтение по одному слову (RSVP): разбивка предложения на слова,
 * опорная буква, на которой держится взгляд, и время показа каждого слова.
 */
object SpeedWords {

    /** Слова предложения. Одиночные тире и кавычки приклеиваются к следующему слову. */
    fun split(sentence: String): List<String> {
        val out = ArrayList<String>()
        var pending = ""
        sentence.split(Regex("\\s+")).forEach { raw ->
            if (raw.isEmpty()) return@forEach
            if (raw.none { it.isLetterOrDigit() }) {
                // «—» или «…» сами по себе — без слова они только сбивают ритм.
                if (out.isNotEmpty() && raw.all { it in TRAILING }) out[out.size - 1] = out.last() + " " + raw
                else pending = if (pending.isEmpty()) raw else "$pending $raw"
                return@forEach
            }
            out += if (pending.isEmpty()) raw else "$pending $raw"
            pending = ""
        }
        if (pending.isNotEmpty()) {
            if (out.isEmpty()) out += pending else out[out.size - 1] = out.last() + " " + pending
        }
        return out
    }

    /**
     * Индекс опорной буквы — чуть левее середины, как в Spritz:
     * 1 буква → 1-я, 2–5 → 2-я, 6–9 → 3-я, 10–13 → 4-я, длиннее → 5-я.
     * Знаки препинания и кавычки по краям при подсчёте не учитываются.
     */
    fun pivot(word: String): Int {
        val first = word.indexOfFirst { it.isLetterOrDigit() }
        if (first < 0) return 0
        val last = word.indexOfLast { it.isLetterOrDigit() }
        val length = last - first + 1
        val offset = when {
            length <= 1 -> 0
            length <= 5 -> 1
            length <= 9 -> 2
            length <= 13 -> 3
            else -> 4
        }
        return first + offset
    }

    /** Сколько миллисекунд показывать слово при скорости [wpm]. */
    fun durationMs(word: String, wpm: Int, endOfSentence: Boolean): Long {
        val base = 60_000.0 / wpm.coerceAtLeast(1)
        val letters = word.count { it.isLetterOrDigit() }
        var factor = 1.0
        if (letters > 8) factor += (letters - 8) * 0.04
        val tail = word.trimEnd { it in CLOSING }.lastOrNull()
        factor += when {
            endOfSentence || tail in SENTENCE_END -> 1.0
            tail in PAUSE -> 0.5
            else -> 0.0
        }
        return (base * factor).toLong()
    }

    /** Скорость с плавным разгоном: от половины целевой до целевой за первые [rampWords] слов. */
    fun rampedWpm(target: Int, wordsShown: Int, rampWords: Int = 60): Int {
        if (wordsShown >= rampWords) return target
        val start = (target / 2).coerceAtLeast(100).coerceAtMost(target)
        return start + (target - start) * wordsShown / rampWords
    }

    private const val TRAILING = "—–-…»\")]"
    private const val CLOSING = "»\")]'"
    private val SENTENCE_END = setOf('.', '!', '?', '…')
    private val PAUSE = setOf(',', ';', ':', '—', '–')
}
