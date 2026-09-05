package com.voicereader.parse

import android.content.Context
import com.voicereader.data.BookContent
import com.voicereader.text.Segmenter
import java.io.File

/** Точка входа разбора: по расширению выбирает нужный разборщик. */
object BookParser {

    val SUPPORTED = listOf("epub", "fb2", "zip", "txt", "text", "html", "htm", "xhtml", "md", "pdf")

    fun isSupported(ext: String) = ext.lowercase() in SUPPORTED

    class Parsed(
        val title: String?,
        val author: String?,
        val content: BookContent
    ) {
        val sentences: List<String> get() = content.sentences
    }

    fun parse(context: Context, file: File, ext: String): Parsed = when (ext.lowercase()) {
        "epub" -> EpubParser.parse(file).let {
            Parsed(it.title, it.author, Segmenter.build(it.chapters, it.language))
        }
        "fb2" -> Fb2Parser.parse(file, zipped = false).let {
            Parsed(it.title, it.author, Segmenter.build(it.chapters, it.language))
        }
        "zip" -> parseZip(file)
        "pdf" -> PdfParser.parse(context, file).let {
            Parsed(it.title, it.author, Segmenter.build(it.chapters, null))
        }
        "html", "htm", "xhtml" -> {
            val bytes = file.readBytes()
            val markup = Charsets.decode(bytes, Charsets.declaredXmlCharset(bytes))
            val text = Html.toText(markup)
            Parsed(Html.title(markup), null, Segmenter.build(splitPlainText(text), null))
        }
        else -> {
            val text = Charsets.decode(file.readBytes())
            Parsed(null, null, Segmenter.build(splitPlainText(text), null))
        }
    }

    /** .zip чаще всего оказывается упакованным fb2 или epub. */
    private fun parseZip(file: File): Parsed {
        runCatching { EpubParser.parse(file) }.getOrNull()?.let {
            return Parsed(it.title, it.author, Segmenter.build(it.chapters, it.language))
        }
        val fb2 = Fb2Parser.parse(file, zipped = true)
        return Parsed(fb2.title, fb2.author, Segmenter.build(fb2.chapters, fb2.language))
    }

    /** У простого текста глав нет — восстанавливаем их по строкам вида «Глава 5» / «CHAPTER V». */
    private fun splitPlainText(text: String): List<Segmenter.RawChapter> {
        val headingRegex = Regex(
            "^\\s*(глава|часть|chapter|part)\\s+[\\dIVXLCХІ]+\\.?.{0,60}$",
            setOf(RegexOption.IGNORE_CASE)
        )
        val chapters = ArrayList<Segmenter.RawChapter>()
        val buffer = StringBuilder()
        var title = "Начало"
        text.split('\n').forEach { line ->
            if (headingRegex.matches(line) && buffer.isNotBlank()) {
                chapters += Segmenter.RawChapter(title, buffer.toString())
                buffer.setLength(0)
                title = line.trim()
            } else {
                buffer.append(line).append('\n')
            }
        }
        if (buffer.isNotBlank()) chapters += Segmenter.RawChapter(title, buffer.toString())
        return chapters.ifEmpty { listOf(Segmenter.RawChapter("Текст", text)) }
    }
}
