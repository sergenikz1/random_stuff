package org.scryertv.client.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ошибка Scryer. [code] — значение `extensions.code` из ответа GraphQL
 * (`AUTHENTICATION_REQUIRED`, `UNAUTHORIZED`, `VALIDATION_ERROR`…), если сервер его прислал.
 */
class ScryerException(
    message: String,
    val code: String? = null,
    val httpStatus: Int = 0
) : IOException(message) {

    /** Ключ отозван, сессия истекла или администратор сбросил пароль — нужно войти заново. */
    val needsLogin: Boolean
        get() = code == "AUTHENTICATION_REQUIRED" || code == "PASSWORD_CHANGE_REQUIRED" ||
            httpStatus == 401

    /** У пользователя нет права на действие (например, добавлять тайтлы). */
    val forbidden: Boolean
        get() = code == "UNAUTHORIZED" || code == "FORBIDDEN" || httpStatus == 403
}

/**
 * Транспорт к серверу Scryer: `POST <сервер>/graphql` с API-ключом в
 * `Authorization: Bearer`. Сами запросы лежат в [Queries], разбор ответов — в [Repository].
 */
object ScryerApi {

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var base: HttpUrl? = null

    @Volatile
    private var token: String = ""

    fun configure(serverUrl: String, token: String) {
        base = normalizeServerUrl(serverUrl)
        this.token = token
    }

    /** «192.168.1.10:8080» → «http://192.168.1.10:8080». Путь (`/scryer`) сохраняется. */
    fun normalizeServerUrl(raw: String): HttpUrl? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.contains("://")) value = "http://$value"
        return value.trimEnd('/').toHttpUrlOrNull()
    }

    /** Постеры Scryer отдаёт относительными адресами (`/images/…`) — дописываем сервер. */
    fun resolveUrl(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return base?.resolve(value)?.toString()
    }

    private fun endpoint(server: HttpUrl, path: String): String =
        server.toString().trimEnd('/') + path

    /**
     * Выполняет GraphQL-запрос и возвращает объект `data`.
     *
     * @param bearer токен для `Authorization`; null — запрос без авторизации.
     */
    suspend fun execute(
        query: String,
        variables: JSONObject = JSONObject(),
        bearer: String? = token,
        server: HttpUrl? = base,
        headers: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val target = server ?: throw ScryerException("Не указан адрес сервера Scryer")
        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
            .toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(endpoint(target, "/graphql"))
            .post(body)
            .header("Accept", "application/json")
            .apply {
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val error = json?.optJSONArray("errors")?.let(::firstError)
            if (error != null) {
                throw ScryerException(error.first, error.second, response.code)
            }
            if (!response.isSuccessful) {
                throw ScryerException(httpMessage(response.code, text), null, response.code)
            }
            // Пока Scryer запускается, вместо JSON он отдаёт HTML-заставку.
            if (json == null) {
                throw ScryerException("Сервер ответил не как Scryer. Если он только что запущен — подождите минуту")
            }
            json.optJSONObject("data") ?: throw ScryerException("Пустой ответ сервера")
        }
    }

    private fun firstError(errors: JSONArray): Pair<String, String?>? {
        val first = errors.optJSONObject(0) ?: return null
        val message = first.optString("message").ifBlank { "Ошибка сервера" }
        val code = first.optJSONObject("extensions")?.optString("code")?.takeIf { it.isNotBlank() }
        return message to code
    }

    private fun httpMessage(code: Int, body: String): String {
        val serverText = runCatching { JSONObject(body).optString("error") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return when (code) {
            401 -> "Нужно войти заново"
            403 -> serverText ?: "Сервер отклонил запрос (403)"
            404 -> "По этому адресу нет Scryer (404). Проверьте адрес и путь"
            else -> serverText ?: "Сервер ответил кодом $code"
        }
    }

    /**
     * Доступ без пароля. Если на сервере выключен вход (или включён обход для
     * локальной сети), Scryer выдаёт веб-клиенту «пропуск»: подпись в ответе
     * `GET /authless-client` плюс cookie. С ними можно выпустить API-ключ.
     *
     * @return заголовки для GraphQL-запроса или null, если сервер требует пароль.
     */
    suspend fun authlessHeaders(server: HttpUrl): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(server, "/authless-client"))
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val proof = runCatching {
                    JSONObject(response.body?.string().orEmpty()).optString("proof")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@use null
                // Cookie передаём вручную: у неё может стоять Secure, а сервер в
                // домашней сети обычно открыт по http.
                val cookie = response.headers("Set-Cookie")
                    .map { it.substringBefore(';').trim() }
                    .firstOrNull { it.startsWith("scryer_authless_client=") }
                    ?: return@use null
                mapOf("x-scryer-web-client" to proof, "Cookie" to cookie)
            }
        }
}
