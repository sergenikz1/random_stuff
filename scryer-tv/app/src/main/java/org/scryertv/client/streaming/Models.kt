package org.scryertv.client.streaming

import java.io.Serializable
import org.json.JSONObject

/** Online video from Rutube or a user-supplied direct stream. */
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
    val group: String = ""
) : Serializable {

    val pageUrl: String get() = streamUrl ?: "https://rutube.ru/video/$id/"

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
            group = o.optString("group")
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
    val mimeType: String? = null
)
