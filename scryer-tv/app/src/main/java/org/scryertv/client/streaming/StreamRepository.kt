package org.scryertv.client.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

object StreamRepository {
    suspend fun search(query: String, page: Int = 1): Page = withContext(Dispatchers.IO) {
        RutubeApi.search(query, page)
    }

    suspend fun resolve(video: VideoItem): PlaybackInfo = withContext(Dispatchers.IO) {
        video.streamUrl?.let { directInfo(it, video.isLive, video.title, video.requestHeaders, video.streamMime) }
            ?: if (video.isAddon) SourceRepository.resolve(video) else RutubeApi.playback(video.id)
    }

    internal fun directInfo(url: String, isLive: Boolean, title: String?, headers: Map<String, String> = emptyMap(), knownMime: String? = null): PlaybackInfo {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
        val mime = when {
            path.endsWith(".m3u8") -> LinkInspector.MIME_HLS
            path.endsWith(".mpd") -> LinkInspector.MIME_DASH
            // Поток без расширения в адресе: формат узнали по ответу сервера при добавлении.
            else -> knownMime
        }
        return PlaybackInfo(url, isLive, title, mime, headers)
    }

    suspend fun episodes(video: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) {
        if (video.isAddon) {
            // Серии из того же сериала по порядку; выбранная озвучка переходит на следующие серии.
            return@withContext SourceRepository.episodes(video.copy(videoId = null))
                .map { it.copy(streamAddonBase = video.streamAddonBase, streamHint = video.streamHint) }
                .ifEmpty { listOf(video) }
        }
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

    /** Любая ссылка: Rutube, прямой поток, M3U-плейлист или страница с открытым видео. */
    suspend fun openUrl(input: String): List<VideoItem> = withContext(Dispatchers.IO) {
        LinkInspector.inspect(input).videos
    }

    internal fun direct(url: String, title: String, group: String = "", logo: String? = null): VideoItem {
        val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return VideoItem("direct:$id", title, thumbnail = logo, streamUrl = url, group = group)
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
