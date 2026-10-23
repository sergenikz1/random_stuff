package com.voicereader.parse

import com.voicereader.AppText
import com.voicereader.R

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.voicereader.text.Segmenter
import java.io.File

/** Извлечение текстового слоя из PDF. Работает офлайн, скан без текстового слоя распознать нельзя. */
object PdfParser {

    class Result(val title: String?, val author: String?, val chapters: List<Segmenter.RawChapter>)

    private var initialized = false

    fun parse(context: Context, file: File): Result {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
        PDDocument.load(file).use { doc ->
            val info = doc.documentInformation
            val chapters = ArrayList<Segmenter.RawChapter>()
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            for (page in 1..doc.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                val raw = runCatching { stripper.getText(doc) }.getOrNull().orEmpty()
                val text = joinWrappedLines(raw)
                if (text.isNotBlank()) chapters += Segmenter.RawChapter(AppText.get(R.string.chapter_page, page), text)
            }
            require(chapters.isNotEmpty()) {
                AppText.get(R.string.err_pdf_scan)
            }
            return Result(
                info?.title?.takeIf { it.isNotBlank() },
                info?.author?.takeIf { it.isNotBlank() },
                chapters
            )
        }
    }

    /**
     * В PDF перенос строки стоит в конце каждой визуальной строки, а не абзаца.
     * Склеиваем строки обратно, иначе предложения рассыпаются на обрывки.
     */
    private fun joinWrappedLines(raw: String): String {
        val lines = raw.split('\n').map { it.trim() }
        val out = StringBuilder()
        for (line in lines) {
            if (line.isEmpty()) {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                continue
            }
            when {
                out.isEmpty() || out.endsWith("\n") -> out.append(line)
                out.endsWith("-") -> { out.setLength(out.length - 1); out.append(line) }
                else -> out.append(' ').append(line)
            }
            // Строка, оканчивающаяся точкой и достаточно короткая, скорее всего конец абзаца.
            if (line.length < 40 && line.lastOrNull() in listOf('.', '!', '?', '»', '"')) out.append('\n')
        }
        return out.toString()
    }
}
