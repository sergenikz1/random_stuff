package org.scryertv.client.streaming

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Разбирает произвольную ссылку: что за ней — M3U-плейлист, прямой поток
 * (MP4/WebM/HLS/DASH), видео Rutube или веб-страница с видео.
 *
 * На странице ищется только видео, которое она отдаёт открыто: метатеги
 * og:video, теги video/source и ссылки на .m3u8/.mp4/.webm/.mpd в коде страницы.
 * Ничего специфичного для конкретных сайтов здесь нет, защиту и шифрование
 * плееров приложение не обходит.
 */
object LinkInspector {

    data class Result(val kind: StreamSource.Kind, val title: String, val videos: List<VideoItem>)

    private const val PAGE_LIMIT = 3 * 1024 * 1024
    private const val MAX_VIDEOS = 50
    private val VIDEO_EXTENSIONS = listOf("m3u8", "mp4", "m4v", "webm", "mkv", "mov", "mpd")

    const val MIME_HLS = "application/x-mpegURL"
    const val MIME_DASH = "application/dash+xml"

    /** Сетевой запрос — вызывать только из фонового потока. */
    fun inspect(input: String): Result {
        val url = input.trim().toHttpUrlOrNull() ?: throw IOException("Введите ссылку, начинающуюся с http:// или https://")
        if (url.host == "rutube.ru" || url.host.endsWith(".rutube.ru")) {
            RutubeApi.parseVideoId(url.toString())?.let { id ->
                val video = RutubeApi.video(id)
                return Result(StreamSource.Kind.LINK, video.title, listOf(video))
            }
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", RutubeApi.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .build()
        RutubeApi.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Ссылка ответила HTTP ${response.code}")
            val finalUrl = response.request.url
            val type = response.header("Content-Type").orEmpty().lowercase()
            val name = fileTitle(finalUrl)

            // Видеофайл не скачиваем целиком: тип ответа или расширение говорят сами за себя.
            if (type.startsWith("video/") || type.startsWith("audio/") || extensionOf(finalUrl) in VIDEO_EXTENSIONS - "m3u8" - "mpd") {
                return single(finalUrl.toString(), name, mimeFor(finalUrl, type))
            }

            val body = response.body ?: throw IOException("Пустой ответ")
            val bytes = body.byteStream().use { it.readLimited(PAGE_LIMIT) }
            val charset = runCatching { body.contentType()?.charset() }.getOrNull() ?: Charsets.UTF_8
            val text = String(bytes, charset).removePrefix("\uFEFF")
            val head = text.trimStart()

            if (head.startsWith("#EXTM3U")) {
                // HLS-манифест — одно видео, обычный M3U — список видео.
                if (text.lineSequence().any { it.startsWith("#EXT-X-") }) return single(finalUrl.toString(), name, MIME_HLS)
                val videos = try { Playlist.parse(text, finalUrl.toString()) } catch (e: IllegalArgumentException) {
                    throw IOException(e.message ?: "Это не M3U-плейлист")
                }
                return Result(StreamSource.Kind.PLAYLIST, name, videos)
            }
            if (head.startsWith("<?xml") && text.contains("<MPD") || head.startsWith("<MPD")) {
                return single(finalUrl.toString(), name, MIME_DASH)
            }
            if (type.contains("html") || head.startsWith("<")) {
                val title = pageTitle(text) ?: finalUrl.host
                val videos = extractVideos(text, finalUrl.toString(), title)
                if (videos.isEmpty()) {
                    throw IOException(
                        "На странице нет видео в открытом виде (тег video, og:video или ссылка на .m3u8/.mp4). " +
                            "Плееры, которые прячут адрес потока, не поддерживаются."
                    )
                }
                return Result(StreamSource.Kind.LINK, title, videos)
            }
            throw IOException("По ссылке нет ни видео, ни плейлиста, ни веб-страницы с видео")
        }
    }

    /** Видео, которые страница отдаёт открыто, по порядку появления, без повторов. */
    fun extractVideos(html: String, pageUrl: String, pageTitle: String): List<VideoItem> {
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        // Адреса в JSON внутри страниц часто экранированы: https:\/\/cdn\/…
        val text = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        val found = LinkedHashMap<String, String?>()

        fun add(raw: String?, mime: String? = null) {
            val value = raw?.trim()?.let(::unescape)?.takeIf { it.isNotEmpty() && !it.startsWith("data:") && !it.startsWith("blob:") } ?: return
            val url = base.resolve(value) ?: return
            if (url.scheme != "http" && url.scheme != "https") return
            val key = url.toString()
            if (key !in found) found[key] = mime ?: mimeFor(url, "")
        }

        // 1. Метатеги og:video / twitter:player:stream — только если это видеофайл, а не страница плеера.
        val metas = TAG_META.findAll(text).map { attrs(it.value) }.toList()
        val videoType = metas.firstOrNull { it["property"] == "og:video:type" }?.get("content").orEmpty().lowercase()
        metas.filter { it["property"] in OG_VIDEO || it["name"] in OG_VIDEO }.forEach { meta ->
            val content = meta["content"] ?: return@forEach
            val url = base.resolve(unescape(content)) ?: return@forEach
            if (extensionOf(url) in VIDEO_EXTENSIONS || videoType.startsWith("video/") || videoType.contains("mpegurl")) {
                add(content, mimeFor(url, videoType))
            }
        }
        // 2. Теги <video src> и <source src type>.
        TAG_MEDIA.findAll(text).map { attrs(it.value) }.forEach { add(it["src"], it["type"]?.let(::mimeFromType)) }
        // 3. Ссылки на видеофайлы и манифесты в коде страницы.
        URL_IN_TEXT.findAll(text).forEach { add(it.groupValues[1]) }

        val list = found.entries.take(MAX_VIDEOS)
        return list.mapIndexed { index, (url, mime) ->
            val title = if (list.size == 1) pageTitle else "$pageTitle · ${index + 1} · ${fileTitle(url.toHttpUrlOrNull())}"
            StreamRepository.direct(url, title, pageTitle).copy(
                // Потоки со страниц часто проверяют, откуда их открыли.
                requestHeaders = mapOf("Referer" to pageUrl),
                streamMime = mime
            )
        }
    }

    fun pageTitle(html: String): String? {
        val metas = TAG_META.findAll(html).map { attrs(it.value) }
        val og = metas.firstOrNull { it["property"] == "og:title" }?.get("content")
        val title = og ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        return title?.let(::unescape)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun single(url: String, title: String, mime: String?): Result =
        Result(StreamSource.Kind.LINK, title, listOf(StreamRepository.direct(url, title).copy(streamMime = mime)))

    private fun mimeFor(url: HttpUrl, contentType: String): String? = when {
        extensionOf(url) == "m3u8" || contentType.contains("mpegurl") -> MIME_HLS
        extensionOf(url) == "mpd" || contentType.contains("dash+xml") -> MIME_DASH
        else -> null
    }

    private fun mimeFromType(type: String): String? = when {
        type.lowercase().contains("mpegurl") -> MIME_HLS
        type.lowercase().contains("dash") -> MIME_DASH
        else -> null
    }

    private fun extensionOf(url: HttpUrl): String = url.pathSegments.lastOrNull().orEmpty().substringAfterLast('.', "").lowercase()

    private fun fileTitle(url: HttpUrl?): String =
        url?.pathSegments?.lastOrNull { it.isNotBlank() }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: url?.host ?: "Видео"

    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[4] }) }

    private fun unescape(value: String) = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x2F;", "/")
        .replace("&lt;", "<").replace("&gt;", ">")

    private val OG_VIDEO = setOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")
    private val TAG_META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG_MEDIA = Regex("<(?:video|source)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val URL_IN_TEXT = Regex(
        "[\"'(=\\s]((?:https?:)?//[^\\s\"'<>()]+?\\.(?:m3u8|mp4|m4v|webm|mpd)(?:\\?[^\\s\"'<>()]*)?|/[^\\s\"'<>()]+?\\.(?:m3u8|mp4|m4v|webm|mpd)(?:\\?[^\\s\"'<>()]*)?)(?=[\"'\\s)<>])",
        RegexOption.IGNORE_CASE
    )
}

internal fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (out.size() + count > limit) throw IOException("Ответ больше ${limit / 1024 / 1024} МБ")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
