package com.voicereader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.voicereader.parse.BookParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Библиотека книг: список метаданных в library.json, сами файлы — в books/,
 * распознанный текст кэшируется в cache/<id>.txt, чтобы повторное открытие было мгновенным.
 */
object LibraryStore {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    private lateinit var appContext: Context
    private var loaded = false

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!loaded) {
            _books.value = readIndex()
            loaded = true
        }
    }

    private fun indexFile() = File(appContext.filesDir, "library.json")
    private fun booksDir() = File(appContext.filesDir, "books").apply { mkdirs() }
    private fun cacheDir() = File(appContext.filesDir, "parsed").apply { mkdirs() }

    private fun readIndex(): List<Book> {
        val f = indexFile()
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Book.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(list: List<Book>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { indexFile().writeText(arr.toString()) }
    }

    private fun update(list: List<Book>) {
        val sorted = list.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        _books.value = sorted
        writeIndex(sorted)
    }

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun savePosition(id: String, position: Int) {
        val current = _books.value
        val book = current.firstOrNull { it.id == id } ?: return
        if (book.position == position) return
        update(current.map { if (it.id == id) it.copy(position = position, lastOpenedAt = System.currentTimeMillis()) else it })
    }

    fun touch(id: String) {
        val current = _books.value
        if (current.none { it.id == id }) return
        update(current.map { if (it.id == id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it })
    }

    fun delete(id: String) {
        val book = get(id) ?: return
        runCatching { File(booksDir(), book.fileName).delete() }
        runCatching { cacheFile(id).delete() }
        update(_books.value.filterNot { it.id == id })
    }

    private fun cacheFile(id: String) = File(cacheDir(), "$id.txt")

    /** Копирует выбранный документ внутрь приложения, разбирает его и добавляет в библиотеку. */
    fun import(uri: Uri): Result<Book> = runCatching {
        val displayName = queryName(uri) ?: "book"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        require(BookParser.isSupported(ext)) {
            "Формат .$ext пока не поддерживается. Доступны: ${BookParser.SUPPORTED.joinToString(", ")}"
        }
        val id = UUID.randomUUID().toString()
        val target = File(booksDir(), "$id.$ext")
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть файл" }
            target.outputStream().use { input.copyTo(it) }
        }

        val parsed = BookParser.parse(appContext, target, ext)
        require(parsed.sentences.isNotEmpty()) { "В файле не найдено текста для озвучивания" }
        writeCache(id, parsed)

        val now = System.currentTimeMillis()
        val book = Book(
            id = id,
            title = parsed.title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.'),
            author = parsed.author.orEmpty(),
            format = ext.uppercase(),
            fileName = target.name,
            language = parsed.content.language,
            sentenceCount = parsed.sentences.size,
            position = 0,
            addedAt = now,
            lastOpenedAt = now
        )
        update(_books.value + book)
        book
    }

    /** Возвращает текст книги: из кэша, а если его нет — заново разбирает исходный файл. */
    fun content(book: Book): BookContent {
        readCache(book.id)?.let { return it }
        val file = File(booksDir(), book.fileName)
        val parsed = BookParser.parse(appContext, file, book.format.lowercase())
        writeCache(book.id, parsed)
        return parsed.content
    }

    private fun writeCache(id: String, parsed: BookParser.Parsed) {
        runCatching {
            val header = JSONObject().apply {
                put("language", parsed.content.language)
                put("chapters", JSONArray().also { arr ->
                    parsed.content.chapters.forEach { ch ->
                        arr.put(JSONObject().put("t", ch.title).put("s", ch.startIndex))
                    }
                })
            }
            cacheFile(id).bufferedWriter().use { w ->
                w.write(header.toString()); w.newLine()
                parsed.sentences.forEach { w.write(it); w.newLine() }
            }
        }
    }

    private fun readCache(id: String): BookContent? {
        val f = cacheFile(id)
        if (!f.exists()) return null
        return runCatching {
            f.bufferedReader().use { r ->
                val header = JSONObject(r.readLine() ?: return@use null)
                val chArr = header.optJSONArray("chapters") ?: JSONArray()
                val chapters = (0 until chArr.length()).map {
                    val o = chArr.getJSONObject(it)
                    Chapter(o.optString("t"), o.optInt("s"))
                }
                val sentences = r.readLines()
                if (sentences.isEmpty()) null
                else BookContent(sentences, chapters, header.optString("language", "ru"))
            }
        }.getOrNull()
    }

    private fun queryName(uri: Uri): String? {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
