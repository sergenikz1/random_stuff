package org.intergalactictv.client.streaming

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.Serializable

/** A reproducible selection, retained when a site's temporary stream URL expires. */
data class WebEpisodeTarget(
    val pageUrl: String,
    val frameUrl: String = "",
    val season: Int = 1,
    val episode: Int = 0,
    val label: String = "",
    val seasonValue: String = "",
    val episodeValue: String = "",
    val href: String? = null
) : Serializable {
    val isSeason: Boolean get() = episode == 0 && href == null
    val seriesId: String get() = WebVideoSearch.page(pageUrl, "").id
    val id: String get() = "$seriesId:s$season:e$episode" + if (episode == 0 && href != null) ":${href.hashCode()}" else ""
    val frameKey: String get() = frameKey(frameUrl)
    val loadUrl: String get() = href ?: pageUrl

    fun toJson(): JSONObject = JSONObject().put("page", pageUrl).put("frame", frameUrl)
        .put("season", season).put("episode", episode).put("label", label)
        .put("seasonValue", seasonValue).put("episodeValue", episodeValue).put("href", href ?: "")

    fun video(show: VideoItem): VideoItem = show.copy(id = id,
        title = listOf(show.title.substringBefore(" · Сезон ").substringBefore(" · Серия "), label).filter { it.isNotBlank() }.joinToString(" · "),
        streamUrl = null, streamMimeType = null, requestHeaders = emptyMap(),
        webPageUrl = pageUrl, webEpisodeTarget = this, season = season, episode = episode)

    companion object {
        private const val serialVersionUID = 1L
        internal fun frameKey(value: String): String = value.toHttpUrlOrNull()?.let {
            "${it.scheme}://${it.host}:${it.port}${it.encodedPath}"
        }.orEmpty()

        fun fromJson(json: JSONObject): WebEpisodeTarget? {
            val page = WebVideoSearch.webUrl(json.optString("page")) ?: return null
            val rawHref = json.optString("href").takeIf { it.isNotBlank() && it != "null" }
            val href = rawHref?.let { WebVideoSearch.webUrl(it) ?: return null }
            // Episode links may navigate within the chosen site, never into another site's recommendations.
            if (href != null && href.toHttpUrlOrNull()?.host?.removePrefix("www.") != page.toHttpUrlOrNull()?.host?.removePrefix("www.")) return null
            val season = json.optInt("season", 1).coerceIn(1, 200)
            val episode = json.optInt("episode", 0).coerceIn(0, 5000)
            val frame = WebVideoSearch.webUrl(json.optString("frame")).orEmpty()
            if (href == null && frame.isBlank()) return null
            return WebEpisodeTarget(page, frame, season, episode,
                json.optString("label").take(160).ifBlank { if (episode > 0) "Сезон $season · Серия $episode" else "Сезон $season" },
                json.optString("seasonValue").take(256), json.optString("episodeValue").take(256), href)
        }
    }
}
