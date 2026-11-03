package org.intergalactictv.client.streaming

import java.io.Serializable
import org.json.JSONObject

/** Online video: Rutube, a direct stream from a playlist or a title from a user-added addon. */
data class VideoItem(
    val id: String,
    val title: String,
    val description: String = "",
    val thumbnail: String? = null,
    val durationSec: Int = 0,
    val authorId: Long = 0L,
    val authorName: String? = null,
    val authorAvatar: String? = null,
    val hits: Long = 0L,
    val published: String? = null,
    val isLive: Boolean = false,
    val streamUrl: String? = null,
    val group: String = "",
    /** Дополнение, из каталога которого пришла карточка (адрес без /manifest.json). */
    val addonBase: String? = null,
    /** Тип и id по протоколу дополнений: movie/series, «tt0111161», «tt0944947:1:2». */
    val contentType: String? = null,
    val metaId: String? = null,
    val videoId: String? = null,
    /** Дополнение и подпись ссылки, выбранной пользователем: по ним автопереход берёт ту же озвучку/качество. */
    val streamAddonBase: String? = null,
    val streamHint: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val season: Int = 0,
    val episode: Int = 0,
    /** Original website, also retained when an expiring media URL was discovered. */
    val webPageUrl: String? = null
) : Serializable {

    /** Видео Rutube: адрес потока узнаём у Rutube в момент запуска. */
    val isWeb: Boolean get() = webPageUrl != null
    val isRutube: Boolean get() = streamUrl == null && addonBase == null && !isWeb

    /** Карточка из дополнения: ссылки на поток запрашиваются у источников. */
    val isAddon: Boolean get() = addonBase != null && streamUrl == null

    /** Сериал целиком: сначала выбирают серию, ссылки бывают только у серии. */
    val isShow: Boolean get() = isAddon && videoId == null

    val pageUrl: String get() = webPageUrl ?: streamUrl ?: "https://rutube.ru/video/$id/"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("thumbnail", thumbnail ?: "")
        put("duration", durationSec)
        put("author_id", authorId)
        put("author_name", authorName ?: "")
        put("author_avatar", authorAvatar ?: "")
        put("hits", hits)
        put("published", published ?: "")
        put("is_live", isLive)
        put("stream_url", streamUrl ?: "")
        put("group", group)
        put("addon_base", addonBase ?: "")
        put("content_type", contentType ?: "")
        put("meta_id", metaId ?: "")
        put("video_id", videoId ?: "")
        put("stream_addon_base", streamAddonBase ?: "")
        put("stream_hint", streamHint ?: "")
        put("headers", JSONObject(requestHeaders))
        put("season", season)
        put("episode", episode)
        put("web_page_url", webPageUrl ?: "")
    }

    companion object {
        private const val serialVersionUID = 1L

        fun fromJson(o: JSONObject): VideoItem = VideoItem(
            id = o.optString("id"),
            title = o.optString("title"),
            description = o.optString("description"),
            thumbnail = o.optString("thumbnail").ifBlank { null },
            durationSec = o.optInt("duration"),
            authorId = o.optLong("author_id"),
            authorName = o.optString("author_name").ifBlank { null },
            authorAvatar = o.optString("author_avatar").ifBlank { null },
            hits = o.optLong("hits"),
            published = o.optString("published").ifBlank { null },
            isLive = o.optBoolean("is_live"),
            streamUrl = o.optString("stream_url").ifBlank { null },
            group = o.optString("group"),
            addonBase = o.optString("addon_base").ifBlank { null },
            contentType = o.optString("content_type").ifBlank { null },
            metaId = o.optString("meta_id").ifBlank { null },
            videoId = o.optString("video_id").ifBlank { null },
            streamAddonBase = o.optString("stream_addon_base").ifBlank { null },
            streamHint = o.optString("stream_hint").ifBlank { null },
            requestHeaders = o.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            } ?: emptyMap(),
            season = o.optInt("season"),
            episode = o.optInt("episode"),
            webPageUrl = o.optString("web_page_url").ifBlank { null }
        )
    }
}

/** Страница выдачи списочного API. */
data class Page(
    val items: List<VideoItem>,
    val hasNext: Boolean,
    val page: Int
)

/** Готовый к воспроизведению поток. */
data class PlaybackInfo(
    val url: String,
    val isLive: Boolean,
    val title: String?,
    val mimeType: String? = null,
    /** Заголовки, которых требует источник (например, Referer). */
    val headers: Map<String, String> = emptyMap()
)
