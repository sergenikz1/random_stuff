package com.voicereader.parse

import com.voicereader.text.Segmenter
import java.io.File
import java.util.zip.ZipFile

/** FictionBook 2 (.fb2 и .fb2.zip) — самый популярный формат русскоязычных книг. */
object Fb2Parser {

    class Result(val title: String?, val author: String?, val language: String?, val chapters: List<Segmenter.RawChapter>)

    fun parse(file: File, zipped: Boolean): Result {
        val bytes = if (zipped) unzipFirstFb2(file) else file.readBytes()
        val xml = Charsets.decode(bytes, Charsets.declaredXmlCharset(bytes))
        return parseXml(xml)
    }

    private fun unzipFirstFb2(file: File): ByteArray = ZipFile(file).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".fb2", true) }
            ?: zip.entries().asSequence().firstOrNull { !it.isDirectory }
            ?: throw IllegalArgumentException("Архив пуст")
        zip.getInputStream(entry).use { it.readBytes() }
    }

    fun parseXml(rawXml: String): Result {
        // <binary> содержит картинки в base64 — выкидываем сразу, иначе распухнет память.
        val xml = Regex("<binary[^>]*>.*?</binary>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(rawXml, " ")

        val description = Regex("<description>.*?</description>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.value.orEmpty()
        val title = text(Regex("<book-title>(.*?)</book-title>", RegexOption.DOT_MATCHES_ALL).find(description)?.groupValues?.get(1))
        val author = listOfNotNull(
            text(Regex("<first-name>(.*?)</first-name>", RegexOption.DOT_MATCHES_ALL).find(description)?.groupValues?.get(1)),
            text(Regex("<last-name>(.*?)</last-name>", RegexOption.DOT_MATCHES_ALL).find(description)?.groupValues?.get(1))
        ).joinToString(" ").takeIf { it.isNotBlank() }
        val language = text(Regex("<lang>(.*?)</lang>", RegexOption.DOT_MATCHES_ALL).find(description)?.groupValues?.get(1))

        val bodies = Regex("<body(?![^>]*name=[\"']notes[\"'])[^>]*>(.*?)</body>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(xml).map { it.groupValues[1] }.toList()
        val body = bodies.joinToString("\n").ifBlank {
            Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(xml)?.groupValues?.get(1).orEmpty()
        }

        val chapters = ArrayList<Segmenter.RawChapter>()
        collectSections(body, chapters, depth = 0)
        if (chapters.isEmpty()) {
            val text = Html.toText(body)
            if (text.isNotBlank()) chapters += Segmenter.RawChapter(title ?: "Книга", text)
        }
        return Result(title, author, language, chapters)
    }

    private val SECTION_TAG = Regex("</?section\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * Секции в FB2 вкладываются друг в друга (часть -> глава -> подглава).
     * Считаем глубину вложенности, чтобы не обрывать раздел на первом же </section>.
     */
    private fun topLevelSections(xml: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var start = -1
        for (m in SECTION_TAG.findAll(xml)) {
            if (m.value.startsWith("</")) {
                depth--
                if (depth == 0 && start >= 0) {
                    result += xml.substring(start, m.range.first)
                    start = -1
                }
                if (depth < 0) depth = 0
            } else {
                if (depth == 0) start = m.range.last + 1
                depth++
            }
        }
        if (depth > 0 && start >= 0) result += xml.substring(start)
        return result
    }

    private fun collectSections(xml: String, out: MutableList<Segmenter.RawChapter>, depth: Int) {
        val nested = if (depth > 6) emptyList() else topLevelSections(xml)
        if (nested.isEmpty()) {
            val text = Html.toText(xml)
            if (text.isNotBlank()) out += Segmenter.RawChapter(sectionTitle(xml) ?: "Глава ${out.size + 1}", text)
            return
        }
        // Текст до первой вложенной секции — заголовок части, эпиграф, предисловие.
        val headEnd = SECTION_TAG.find(xml)?.range?.first ?: xml.length
        val head = Html.toText(xml.substring(0, headEnd))
        if (head.isNotBlank()) {
            out += Segmenter.RawChapter(sectionTitle(xml) ?: "Часть ${out.size + 1}", head)
        }
        nested.forEach { collectSections(it, out, depth + 1) }
    }

    /** Заголовок секции — <title> до начала вложенных секций. */
    private fun sectionTitle(section: String): String? {
        val limit = SECTION_TAG.find(section)?.range?.first ?: section.length
        val head = section.substring(0, limit)
        val raw = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(head)?.groupValues?.get(1) ?: return null
        return Html.toText(raw).replace('\n', ' ').replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
    }

    private fun text(raw: String?): String? =
        raw?.let { Html.unescape(it.replace(Regex("<[^>]+>"), " ")) }
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
}
