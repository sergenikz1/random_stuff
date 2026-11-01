package org.scryertv.client.streaming

import java.io.IOException
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Каталог дополнения: полка на главном экране или место поиска. */
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    val searchable: Boolean,
    /** Каталог нельзя открыть без параметров (например, только поиск или только жанр). */
    val needsExtra: Boolean
)

/** Ресурс дополнения (catalog, meta, stream) и для каких типов и id он работает. */
data class AddonResource(val name: String, val types: List<String>, val idPrefixes: List<String>?)

data class AddonManifest(
    val id: String,
    val name: String,
    val description: String,
    val types: List<String>,
    val catalogs: List<AddonCatalog>,
    val resources: List<AddonResource>,
    val idPrefixes: List<String>?
) {
    /** Отвечает ли дополнение на запрос [resource] для карточки типа [type] с id [id]. */
    fun supports(resource: String, type: String, id: String): Boolean {
        val match = resources.firstOrNull { it.name == resource } ?: return false
        val types = match.types.ifEmpty { this.types }
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = match.idPrefixes ?: idPrefixes ?: return true
        return prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
    }
}

/** Одна ссылка на поток, из которых пользователь выбирает, откуда смотреть. */
data class StreamOption(
    val sourceName: String,
    val addonBase: String,
    val label: String,
    val url: String,
    val headers: Map<String, String>,
    val bingeGroup: String?
) {
    /** По этой подписи автопереход находит ту же раздачу/озвучку у следующей серии. */
    val hint: String get() = bingeGroup ?: label
}

/**
 * Клиент открытого протокола дополнений Stremio
 * (https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md):
 * `manifest.json`, `catalog/…`, `meta/…`, `stream/…` — обычные GET с JSON.
 * Источники добавляет пользователь, в приложение ни одно дополнение не встроено.
 * Берутся только ссылки http/https: торрентов и внешних плееров приложение не открывает.
 */
object AddonClient {

    /** «stremio://host/manifest.json» или ссылка на manifest.json → базовый адрес дополнения. */
    fun baseUrl(input: String): String? {
        var value = input.trim()
        if (value.startsWith("stremio://", ignoreCase = true)) value = "https://" + value.substring(10)
        val url = value.toHttpUrlOrNull() ?: return null
        val text = url.toString().substringBefore('?').trimEnd('/')
        return if (text.endsWith("/manifest.json")) text.removeSuffix("/manifest.json") else null
    }

    fun manifestUrl(base: String) = "$base/manifest.json"

    fun catalogUrl(base: String, type: String, id: String, extra: Map<String, String> = emptyMap()): String {
        val extraPart = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "$base/catalog/${enc(type)}/${enc(id)}$extraPart.json"
    }

    fun metaUrl(base: String, type: String, id: String) = "$base/meta/${enc(type)}/${enc(id)}.json"

    fun streamUrl(base: String, type: String, id: String) = "$base/stream/${enc(type)}/${enc(id)}.json"

    /** Как encodeURIComponent в JavaScript, которым пользуются сами дополнения. */
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    fun parseManifest(json: JSONObject): AddonManifest {
        val types = json.optJSONArray("types").strings()
        val catalogs = json.optJSONArray("catalogs").objects().mapNotNull { c ->
            val type = c.optString("type").ifBlank { return@mapNotNull null }
            val id = c.optString("id").ifBlank { return@mapNotNull null }
            val extras = c.optJSONArray("extra").objects()
            val legacy = c.optJSONArray("extraSupported").strings()
            val required = extras.filter { it.optBoolean("isRequired") }.map { it.optString("name") } +
                c.optJSONArray("extraRequired").strings()
            AddonCatalog(
                type = type,
                id = id,
                name = c.optString("name").ifBlank { id },
                searchable = extras.any { it.optString("name") == "search" } || "search" in legacy,
                needsExtra = required.isNotEmpty()
            )
        }
        val resources = json.optJSONArray("resources")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                when (val r = array.opt(i)) {
                    is String -> AddonResource(r, emptyList(), null)
                    is JSONObject -> AddonResource(
                        r.optString("name"),
                        r.optJSONArray("types").strings(),
                        r.optJSONArray("idPrefixes")?.strings()
                    )
                    else -> null
                }
            }
        }.orEmpty()
        return AddonManifest(
            id = json.optString("id"),
            name = json.optString("name").ifBlank { json.optString("id").ifBlank { "Дополнение" } },
            description = json.optString("description"),
            types = types,
            catalogs = catalogs,
            resources = resources,
            idPrefixes = json.optJSONArray("idPrefixes")?.strings()
        )
    }

    /** Карточки каталога (`metas`) в общем формате приложения. */
    fun parseMetas(json: JSONObject, base: String, sourceName: String): List<VideoItem> =
        json.optJSONArray("metas").objects().mapNotNull { meta ->
            val id = meta.optString("id").ifBlank { return@mapNotNull null }
            val type = meta.optString("type").ifBlank { return@mapNotNull null }
            val name = meta.optString("name").ifBlank { return@mapNotNull null }
            VideoItem(
                id = itemId(base, type, id),
                title = listOf(name, meta.optString("releaseInfo")).filter { it.isNotBlank() }.joinToString(" · "),
                description = meta.optString("description"),
                thumbnail = httpOrNull(meta.optString("poster")),
                authorName = sourceName,
                addonBase = base,
                contentType = type,
                metaId = id,
                // У фильма id видео совпадает с id карточки; у сериала его задаёт выбранная серия.
                videoId = if (type == "series") null else id
            )
        }

    /** Серии сериала из ответа `meta`, по порядку сезонов и серий; спецвыпуски (сезон 0) в конце. */
    fun parseEpisodes(json: JSONObject, show: VideoItem): List<VideoItem> {
        val meta = json.optJSONObject("meta") ?: return emptyList()
        val base = show.addonBase ?: return emptyList()
        val type = meta.optString("type").ifBlank { show.contentType ?: "series" }
        val name = meta.optString("name").ifBlank { show.title }
        val poster = httpOrNull(meta.optString("poster")) ?: show.thumbnail
        val videos = meta.optJSONArray("videos").objects().mapNotNull { v ->
            val id = v.optString("id").ifBlank { return@mapNotNull null }
            val season = v.optInt("season")
            val episode = v.optInt("episode", v.optInt("number"))
            val episodeTitle = v.optString("name").ifBlank { v.optString("title") }
            val code = if (season > 0 || episode > 0) "S%02dE%02d".format(season, episode) else ""
            VideoItem(
                id = itemId(base, type, id),
                title = listOf(name, code, episodeTitle).filter { it.isNotBlank() }.joinToString(" · "),
                description = v.optString("overview").ifBlank { v.optString("description") },
                thumbnail = httpOrNull(v.optString("thumbnail")) ?: poster,
                authorName = show.authorName,
                addonBase = base,
                contentType = type,
                metaId = meta.optString("id").ifBlank { show.metaId },
                videoId = id,
                season = season,
                episode = episode
            )
        }
        return videos.sortedWith(compareBy<VideoItem>({ it.season == 0 }, { it.season }, { it.episode }))
    }

    /** Ссылки из ответа `stream`. Торренты (infoHash), YouTube и внешние ссылки пропускаем. */
    fun parseStreams(json: JSONObject, base: String, sourceName: String): List<StreamOption> =
        json.optJSONArray("streams").objects().mapNotNull { s ->
            val url = httpOrNull(s.optString("url")) ?: return@mapNotNull null
            val hints = s.optJSONObject("behaviorHints")
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }.filterValues { it.isNotBlank() }
            }.orEmpty()
            val label = listOf(s.optString("name"), s.optString("title").ifBlank { s.optString("description") })
                .flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
                .ifBlank { url.toHttpUrlOrNull()?.host ?: "Ссылка" }
            StreamOption(sourceName, base, label, url, headers, hints?.optString("bingeGroup")?.ifBlank { null })
        }

    /** Выбор ссылки для автоперехода: та же группа или подпись, иначе первая. */
    fun pick(options: List<StreamOption>, addonBase: String?, hint: String?): StreamOption? {
        val sameAddon = options.filter { addonBase == null || it.addonBase == addonBase }.ifEmpty { options }
        return hint?.let { h -> sameAddon.firstOrNull { it.bingeGroup == h } ?: sameAddon.firstOrNull { it.label == h } }
            ?: sameAddon.firstOrNull()
    }

    // ---------- сеть ----------

    fun fetchManifest(base: String): AddonManifest = parseManifest(getJson(manifestUrl(base)))

    fun catalog(base: String, sourceName: String, catalog: AddonCatalog, extra: Map<String, String> = emptyMap()) =
        parseMetas(getJson(catalogUrl(base, catalog.type, catalog.id, extra)), base, sourceName)

    fun episodes(show: VideoItem): List<VideoItem> {
        val base = show.addonBase ?: return emptyList()
        return parseEpisodes(getJson(metaUrl(base, show.contentType ?: "series", show.metaId ?: return emptyList())), show)
    }

    fun streams(base: String, sourceName: String, type: String, id: String) =
        parseStreams(getJson(streamUrl(base, type, id)), base, sourceName)

    internal fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", RutubeApi.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        RutubeApi.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Источник ответил HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return try { JSONObject(body) } catch (_: Exception) { throw IOException("Источник прислал не JSON") }
        }
    }

    private fun itemId(base: String, type: String, id: String) = "addon:${base.hashCode()}:$type:$id"

    private fun httpOrNull(value: String?): String? =
        value?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
