package org.scryertv.client.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.MessageDigest

object StreamRepository {
    suspend fun search(query: String, page: Int = 1): Page = withContext(Dispatchers.IO) {
        RutubeApi.search(query, page)
    }

    suspend fun resolve(video: VideoItem): PlaybackInfo = withContext(Dispatchers.IO) {
        video.streamUrl?.let { PlaybackInfo(it, video.isLive, video.title) } ?: RutubeApi.playback(video.id)
    }

    suspend fun episodes(video: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) {
        val number = EpisodeOrder.parse(video.title) ?: return@withContext listOf(video)
        if (video.streamUrl != null) return@withContext listOf(video)
        val candidates = mutableListOf<VideoItem>()
        // Search by series name, not the current episode number. Follow pagination.
        for (page in 1..8) {
            val result = RutubeApi.search(number.series, page)
            candidates += result.items
            if (!result.hasNext || result.items.isEmpty()) break
        }
        EpisodeOrder.ordered(video, candidates)
    }

    suspend fun openUrl(input: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val url = input.trim().toHttpUrlOrNull() ?: throw IOException("Введите HTTP/HTTPS-ссылку")
        if (url.host == "rutube.ru" || url.host.endsWith(".rutube.ru")) {
            RutubeApi.parseVideoId(url.toString())?.let { return@withContext listOf(RutubeApi.video(it)) }
        }
        val path = url.encodedPath.lowercase()
        if (path.endsWith(".m3u") || path.endsWith(".m3u8")) {
            val request = Request.Builder().url(url).header("User-Agent", RutubeApi.USER_AGENT).build()
            RutubeApi.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Плейлист: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Пустой плейлист")
                val bytes = body.byteStream().use { it.readBytesLimited(2 * 1024 * 1024) }
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                // An HLS manifest is one video, never a list of episode segments.
                if (text.lineSequence().any { it.startsWith("#EXT-X-") }) {
                    return@withContext listOf(direct(url.toString(), "Онлайн-видео"))
                }
                return@withContext Playlist.parse(text, response.request.url.toString())
            }
        }
        if (listOf(".mp4", ".mkv", ".webm", ".mpd").any(path::endsWith)) {
            return@withContext listOf(direct(url.toString(), url.pathSegments.last().ifBlank { "Онлайн-видео" }))
        }
        throw IOException("Поддерживаются ссылки Rutube, прямые MP4/HLS/DASH и M3U-плейлисты. Страница сайта с веб-плеером не является видеопотоком.")
    }

    internal fun direct(url: String, title: String, group: String = "", logo: String? = null): VideoItem {
        val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return VideoItem("direct:$id", title, thumbnail = logo, streamUrl = url, group = group)
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) throw IOException("Плейлист больше 2 МБ")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

object Playlist {
    fun parse(text: String, base: String): List<VideoItem> {
        require(text.trimStart().startsWith("#EXTM3U")) { "Ожидается M3U-плейлист" }
        require(!text.lineSequence().any { it.startsWith("#EXT-X-") }) { "Это HLS-поток, а не список серий" }
        val baseUrl = base.toHttpUrlOrNull() ?: error("Некорректный адрес плейлиста")
        val videos = mutableListOf<VideoItem>()
        var title = ""
        var group = ""
        var logo: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    title = line.substringAfter(',', "Онлайн-видео")
                    group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                    logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    val url = baseUrl.resolve(line) ?: continue
                    videos += StreamRepository.direct(url.toString(), title.ifBlank { "Видео ${videos.size + 1}" }, group, logo)
                    title = ""; group = ""; logo = null
                    if (videos.size >= 200) break
                }
            }
        }
        require(videos.isNotEmpty()) { "В плейлисте нет HTTP/HTTPS-видео" }
        return videos.distinctBy { it.id }
    }
}
