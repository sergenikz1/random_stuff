package org.scryertv.client.streaming

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
