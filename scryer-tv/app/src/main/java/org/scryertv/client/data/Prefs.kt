package org.scryertv.client.data

import android.content.Context
import android.content.SharedPreferences

/** Адрес сервера Scryer и ключ доступа к нему. Больше приложение ничего не хранит. */
class Prefs private constructor(private val sp: SharedPreferences) {

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER, value).apply()

    /** API-ключ (`ska_…`) или, если ключ выпустить не удалось, токен сессии. */
    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var username: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(value) = sp.edit().putString(KEY_USER, value).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    fun clearSession() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
    }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                context.applicationContext.getSharedPreferences("scryertv", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
