package org.intergalactictv.client.streaming

import android.content.Context
import org.json.JSONArray

class StreamStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("streaming", Context.MODE_PRIVATE)

    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(value) { sp.edit().putBoolean("autoplay", value).apply() }
    var maxHeight: Int
        get() = sp.getInt("height", 0)
        set(value) { sp.edit().putInt("height", value).apply() }
    var catalogUrl: String
        get() = sp.getString("catalog_url", "").orEmpty()
        set(value) { sp.edit().putString("catalog_url", value).apply() }

    /** Источники пользователя в порядке добавления. */
    fun sources(): List<StreamSource> {
        migrateCatalog()
        return try {
            val array = JSONArray(sp.getString("sources", "[]"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(StreamSource::fromJson) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveSource(source: StreamSource) {
        val list = sources()
        val updated = if (list.any { it.id == source.id }) list.map { if (it.id == source.id) source else it } else list + source
        writeSources(updated)
    }

    fun removeSource(source: StreamSource) {
        writeSources(sources().filter { it.id != source.id })
        sp.edit().remove(SourceRepository.itemsKey(source)).apply()
    }

    private fun writeSources(list: List<StreamSource>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        sp.edit().putString("sources", array.toString()).apply()
    }

    /** До 1.3 был один сохранённый плейлист — превращаем его в обычный источник. */
    private fun migrateCatalog() {
        val url = catalogUrl
        if (url.isBlank() || sp.contains("sources")) return
        val source = StreamSource("legacy-playlist", url, "Мой плейлист", StreamSource.Kind.PLAYLIST)
        val array = JSONArray().put(source.toJson())
        sp.edit()
            .putString("sources", array.toString())
            .putString(SourceRepository.itemsKey(source), sp.getString("catalog", "[]"))
            .remove("catalog")
            .apply()
    }

    fun videos(key: String): List<VideoItem> = try {
        val array = JSONArray(sp.getString(key, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(VideoItem::fromJson) }
    } catch (_: Exception) { emptyList() }

    fun putVideos(key: String, videos: List<VideoItem>) {
        val array = JSONArray()
        videos.take(200).forEach { array.put(it.toJson()) }
        sp.edit().putString(key, array.toString()).apply()
    }

    fun remember(video: VideoItem) = putVideos("history", listOf(video) + videos("history").filter { it.id != video.id })
    fun toggleFavorite(video: VideoItem) {
        val old = videos("favorites")
        putVideos("favorites", if (old.any { it.id == video.id }) old.filter { it.id != video.id } else listOf(video) + old)
    }
    fun position(id: String): Long = sp.getLong("position:$id", 0)
    fun savePosition(id: String, position: Long, duration: Long) {
        val value = if (duration > 0 && position >= duration - 5000) 0 else position.coerceAtLeast(0)
        sp.edit().putLong("position:$id", value).apply()
    }
    fun clearHistory() {
        val editor = sp.edit().remove("history")
        sp.all.keys.filter { it.startsWith("position:") }.forEach { editor.remove(it) }
        editor.apply()
    }
}
