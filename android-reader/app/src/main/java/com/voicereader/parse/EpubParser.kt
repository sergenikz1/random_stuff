package com.voicereader.parse

import com.voicereader.text.Segmenter
import java.io.File
import java.util.zip.ZipFile

/** Разбор EPUB: container.xml -> OPF -> spine -> XHTML-главы. Всё локально. */
object EpubParser {

    class Result(val title: String?, val author: String?, val language: String?, val chapters: List<Segmenter.RawChapter>)

    fun parse(file: File): Result = ZipFile(file).use { zip ->
        val container = zip.textOf("META-INF/container.xml")
            ?: throw IllegalArgumentException("Повреждённый EPUB: нет META-INF/container.xml")
        val opfPath = Regex("full-path=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Повреждённый EPUB: не найден OPF")
        val opf = zip.textOf(opfPath) ?: throw IllegalArgumentException("Повреждённый EPUB: OPF не читается")
        val baseDir = opfPath.substringBeforeLast('/', "")

        val title = tag(opf, "dc:title") ?: tag(opf, "title")
        val author = tag(opf, "dc:creator") ?: tag(opf, "creator")
        val language = tag(opf, "dc:language") ?: tag(opf, "language")

        val manifest = HashMap<String, String>()
        Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val item = m.value
            val id = attr(item, "id") ?: return@forEach
            val href = attr(item, "href") ?: return@forEach
            val type = attr(item, "media-type").orEmpty()
            if (type.contains("html") || href.endsWith(".xhtml", true) ||
                href.endsWith(".html", true) || href.endsWith(".htm", true)
            ) manifest[id] = href
        }

        val spine = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .mapNotNull { manifest[it] }
            .toMutableList()
        if (spine.isEmpty()) spine += manifest.values

        val chapters = ArrayList<Segmenter.RawChapter>()
        spine.forEach { href ->
            val path = resolve(baseDir, href.substringBefore('#'))
            val markup = zip.textOf(path) ?: return@forEach
            val text = Html.toText(markup)
            if (text.isBlank()) return@forEach
            val chapterTitle = Html.title(markup)
                ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                ?: "Глава ${chapters.size + 1}"
            chapters += Segmenter.RawChapter(chapterTitle, text)
        }
        Result(title, author, language, chapters)
    }

    private fun ZipFile.textOf(path: String): String? {
        val entry = getEntry(path)
            ?: entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }
            ?: entries().asSequence().firstOrNull { it.name.endsWith("/" + path.substringAfterLast('/')) }
            ?: return null
        val bytes = getInputStream(entry).use { it.readBytes() }
        return Charsets.decode(bytes, Charsets.declaredXmlCharset(bytes) ?: "UTF-8")
    }

    private fun resolve(baseDir: String, href: String): String {
        val decoded = href.replace("%20", " ")
        if (baseDir.isEmpty()) return decoded
        val parts = ArrayList(baseDir.split('/'))
        decoded.split('/').forEach { part ->
            when (part) {
                "." , "" -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<${Regex.escape(name)}\\b[^>]*>(.*?)</${Regex.escape(name)}>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)
            ?.let { Html.unescape(it.replace(Regex("<[^>]+>"), " ")) }
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
}
