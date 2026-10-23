package com.voicereader.parse

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Определение кодировки для txt/fb2: русские книги нередко приходят в windows-1251 или koi8-r. */
object Charsets {

    private val CANDIDATES = listOf("UTF-8", "windows-1251", "KOI8-R", "ISO-8859-5")

    fun decode(bytes: ByteArray, declared: String? = null): String {
        stripBom(bytes)?.let { return it }
        declared?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()?.let { cs ->
                strictDecode(bytes, cs)?.let { return it }
            }
        }
        for (name in CANDIDATES) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val text = strictDecode(bytes, cs) ?: continue
            // windows-1251 декодирует что угодно, поэтому дополнительно смотрим на «осмысленность».
            if (name == "UTF-8" || looksSane(text)) return text
        }
        return String(bytes, Charset.forName("windows-1251"))
    }

    private fun stripBom(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charset.forName("UTF-8"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }
        return null
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = runCatching {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /** Доля букв и пробелов среди первых символов — грубый признак верной кодировки. */
    private fun looksSane(text: String): Boolean {
        val sample = text.take(4000)
        if (sample.isEmpty()) return false
        val good = sample.count { it.isLetter() || it.isWhitespace() || it.isDigit() || it in ".,!?—-«»\"'():;" }
        return good.toDouble() / sample.length > 0.9
    }

    /** Кодировка из XML-декларации: <?xml version="1.0" encoding="windows-1251"?> */
    fun declaredXmlCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charset.forName("ISO-8859-1"))
        return Regex("encoding=[\"']([\\w-]+)[\"']", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
    }
}
