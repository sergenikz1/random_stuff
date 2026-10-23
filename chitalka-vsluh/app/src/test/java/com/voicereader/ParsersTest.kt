package com.voicereader

import com.voicereader.parse.Charsets
import com.voicereader.parse.EpubParser
import com.voicereader.parse.Fb2Parser
import com.voicereader.parse.Html
import com.voicereader.text.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParsersTest {

    @Test
    fun `html to text strips markup and decodes entities`() {
        val html = """
            <html><head><title>Заголовок</title><style>p{color:red}</style></head>
            <body><p>Первый абзац &mdash; с тире.</p><p>Второй&nbsp;абзац &amp; символы &#1071;.</p>
            <script>alert(1)</script></body></html>
        """.trimIndent()
        val text = Html.toText(html)
        assertTrue(text.contains("Первый абзац — с тире."))
        assertTrue(text.contains("Второй абзац & символы Я."))
        assertTrue("скрипты не должны попадать в текст", !text.contains("alert"))
        assertTrue("стили не должны попадать в текст", !text.contains("color:red"))
        assertEquals("Заголовок", Html.title(html))
    }

    @Test
    fun `segmenter splits russian text into sentences`() {
        val text = "Это первое предложение. А это второе! Третье? Да.\nНовый абзац здесь."
        val sentences = Segmenter.splitToSentences(text, Locale.forLanguageTag("ru"))
        assertEquals(
            listOf(
                "Это первое предложение.",
                "А это второе!",
                "Третье?",
                "Да.",
                "Новый абзац здесь."
            ),
            sentences
        )
    }

    @Test
    fun `segmenter cuts overly long sentences for the tts engine`() {
        val long = (1..120).joinToString(", ") { "слово$it" } + "."
        val sentences = Segmenter.splitToSentences(long, Locale.forLanguageTag("ru"))
        assertTrue("длинное предложение должно быть разбито", sentences.size > 1)
        assertTrue(sentences.all { it.length <= 330 })
        // Текст не должен теряться при нарезке.
        assertEquals(
            long.replace(Regex("[\\s,]+"), ""),
            sentences.joinToString("").replace(Regex("[\\s,]+"), "")
        )
    }

    @Test
    fun `language detection distinguishes russian and english`() {
        assertEquals("ru", Segmenter.detectLanguage("Мороз и солнце, день чудесный"))
        assertEquals("en", Segmenter.detectLanguage("It was a bright cold day in April"))
    }

    @Test
    fun `windows-1251 text is decoded correctly`() {
        val original = "Привет, мир! Это проверка кодировки."
        val bytes = original.toByteArray(charset("windows-1251"))
        assertEquals(original, Charsets.decode(bytes))
    }

    @Test
    fun `fb2 is parsed with metadata sections and without base64 images`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FictionBook>
              <description><title-info>
                <book-title>Тестовая книга</book-title>
                <author><first-name>Иван</first-name><last-name>Петров</last-name></author>
                <lang>ru</lang>
              </title-info></description>
              <body>
                <section><title><p>Глава первая</p></title>
                  <p>Начало книги. Второе предложение главы.</p>
                </section>
                <section><title><p>Глава вторая</p></title>
                  <p>Продолжение истории.</p>
                </section>
              </body>
              <binary id="cover.jpg" content-type="image/jpeg">QUJDREVGR0hJSktMTU5PUFFSU1Q=</binary>
            </FictionBook>
        """.trimIndent()
        val result = Fb2Parser.parseXml(xml)
        assertEquals("Тестовая книга", result.title)
        assertEquals("Иван Петров", result.author)
        assertEquals(listOf("Глава первая", "Глава вторая"), result.chapters.map { it.title })

        val content = Segmenter.build(result.chapters, result.language)
        assertEquals("ru", content.language)
        assertTrue(content.sentences.any { it == "Начало книги." })
        assertTrue(content.sentences.any { it == "Продолжение истории." })
        assertTrue("base64 картинки не должны попадать в текст",
            content.sentences.none { it.contains("QUJDREVG") })
        assertEquals(2, content.chapters.size)
        assertEquals(0, content.chapters[0].startIndex)
    }

    @Test
    fun `epub spine order defines chapter order`() {
        val epub = File.createTempFile("test", ".epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("META-INF/container.xml", """
                <container><rootfiles>
                  <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles></container>
            """.trimIndent())
            put("OEBPS/content.opf", """
                <package><metadata>
                  <dc:title>Книга из EPUB</dc:title>
                  <dc:creator>Автор Тестов</dc:creator>
                  <dc:language>ru</dc:language>
                </metadata>
                <manifest>
                  <item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="two.xhtml" media-type="application/xhtml+xml"/>
                  <item id="css" href="style.css" media-type="text/css"/>
                </manifest>
                <spine><itemref idref="c2"/><itemref idref="c1"/></spine>
                </package>
            """.trimIndent())
            put("OEBPS/one.xhtml", "<html><body><h1>Первая</h1><p>Текст первой главы.</p></body></html>")
            put("OEBPS/two.xhtml", "<html><body><h1>Вторая</h1><p>Текст второй главы.</p></body></html>")
            put("OEBPS/style.css", "body{margin:0}")
        }

        val result = EpubParser.parse(epub)
        assertEquals("Книга из EPUB", result.title)
        assertEquals("Автор Тестов", result.author)
        assertEquals("ru", result.language)
        // Порядок задаётся spine, а не порядком файлов в архиве.
        assertEquals(listOf("Вторая", "Первая"), result.chapters.map { it.title })

        val content = Segmenter.build(result.chapters, result.language)
        assertEquals("Текст второй главы.", content.sentences[content.chapters[0].startIndex + 1])
        epub.delete()
    }

    @Test
    fun `chapter lookup by sentence index`() {
        val content = Segmenter.build(
            listOf(
                Segmenter.RawChapter("A", "Раз. Два. Три."),
                Segmenter.RawChapter("B", "Четыре. Пять.")
            ),
            "ru"
        )
        assertEquals(5, content.sentences.size)
        assertEquals(0, content.chapterIndexFor(0))
        assertEquals(0, content.chapterIndexFor(2))
        assertEquals(1, content.chapterIndexFor(3))
        assertEquals(1, content.chapterIndexFor(4))
    }

    @Test
    fun `nested fb2 sections do not truncate chapter text`() {
        val xml = """
            <FictionBook><description><title-info><lang>ru</lang></title-info></description>
            <body>
              <section>
                <title><p>Часть первая</p></title>
                <p>Вступление к части.</p>
                <section><title><p>Глава один</p></title><p>Текст первой главы.</p></section>
                <section><title><p>Глава два</p></title><p>Текст второй главы.</p></section>
              </section>
              <section><title><p>Эпилог</p></title><p>Финал книги.</p></section>
            </body></FictionBook>
        """.trimIndent()

        val result = Fb2Parser.parseXml(xml)
        assertEquals(
            listOf("Часть первая", "Глава один", "Глава два", "Эпилог"),
            result.chapters.map { it.title }
        )
        val all = result.chapters.joinToString(" ") { it.text }
        // Ни один фрагмент не должен потеряться на вложенности.
        listOf("Вступление к части.", "Текст первой главы.", "Текст второй главы.", "Финал книги.")
            .forEach { assertTrue("потерян фрагмент: $it", all.contains(it)) }
    }
}
