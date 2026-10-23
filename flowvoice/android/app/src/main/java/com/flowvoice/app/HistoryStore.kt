package com.flowvoice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val timestamp: Long, val text: String, val language: String?)

/** A short, local-only log of what was dictated. Never leaves the device. */
class HistoryStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("flowvoice_history", Context.MODE_PRIVATE)

    fun list(): List<HistoryItem> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HistoryItem(
                    o.optLong("ts"),
                    o.optString("text"),
                    o.optString("language").ifBlank { null }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(text: String, language: String?) {
        val array = JSONArray()
        array.put(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("text", text)
                .put("language", language ?: "")
        )
        list().take(LIMIT - 1).forEach { item ->
            array.put(
                JSONObject()
                    .put("ts", item.timestamp)
                    .put("text", item.text)
                    .put("language", item.language ?: "")
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    fun clear() {
        sp.edit().putString(KEY, "[]").apply()
    }

    private companion object {
        const val KEY = "items"
        const val LIMIT = 100
    }
}
