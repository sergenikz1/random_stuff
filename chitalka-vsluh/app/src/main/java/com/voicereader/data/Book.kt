package com.voicereader.data

import org.json.JSONObject

/** Одна книга в библиотеке. Всё хранится локально, без сети. */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    /** Имя файла внутри приватной папки books/ */
    val fileName: String,
    val language: String,
    val sentenceCount: Int,
    /** Индекс предложения, на котором остановились */
    val position: Int,
    val addedAt: Long,
    val lastOpenedAt: Long
) {
    val progress: Float
        get() = if (sentenceCount <= 1) 0f else (position.toFloat() / (sentenceCount - 1)).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("format", format)
        put("fileName", fileName)
        put("language", language)
        put("sentenceCount", sentenceCount)
        put("position", position)
        put("addedAt", addedAt)
        put("lastOpenedAt", lastOpenedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.optString("title", ""),
            author = o.optString("author", ""),
            format = o.optString("format", ""),
            fileName = o.optString("fileName", ""),
            language = o.optString("language", "ru"),
            sentenceCount = o.optInt("sentenceCount", 0),
            position = o.optInt("position", 0),
            addedAt = o.optLong("addedAt", 0L),
            lastOpenedAt = o.optLong("lastOpenedAt", 0L)
        )
    }
}

/** Заголовок главы и индекс предложения, с которого она начинается. */
data class Chapter(val title: String, val startIndex: Int)

/** Полностью разобранная книга: плоский список предложений + разметка глав. */
data class BookContent(
    val sentences: List<String>,
    val chapters: List<Chapter>,
    val language: String
) {
    fun chapterIndexFor(sentenceIndex: Int): Int {
        if (chapters.isEmpty()) return 0
        var lo = 0
        var hi = chapters.size - 1
        var res = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chapters[mid].startIndex <= sentenceIndex) {
                res = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return res
    }
}
