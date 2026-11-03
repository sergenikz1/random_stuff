package org.intergalactictv.client.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Источник, который пользователь добавил сам: M3U-плейлист или дополнение
 * в формате Stremio. Встроенных источников, кроме поиска Rutube, нет.
 */
data class StreamSource(
    val id: String,
    val url: String,
    val name: String,
    val kind: Kind,
    val enabled: Boolean = true,
    /** Последний загруженный manifest.json дополнения — чтобы экран открывался без сети. */
    val manifest: String? = null
) {
    enum class Kind(val label: String) { PLAYLIST("Плейлист M3U"), ADDON("Дополнение") }

    val addon: AddonManifest?
        get() = manifest?.let { runCatching { AddonClient.parseManifest(JSONObject(it)) }.getOrNull() }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("name", name).put("kind", kind.name)
        .put("enabled", enabled).put("manifest", manifest ?: "")

    companion object {
        fun fromJson(o: JSONObject): StreamSource? {
            val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
            return StreamSource(
                id = o.optString("id").ifBlank { return null },
                url = o.optString("url").ifBlank { return null },
                name = o.optString("name").ifBlank { "Источник" },
                kind = kind,
                enabled = o.optBoolean("enabled", true),
                manifest = o.optString("manifest").ifBlank { null }
            )
        }
    }
}

/** Полка главного экрана из источника. */
data class SourceRow(val title: String, val items: List<VideoItem>)

/** Добавление, обновление и опрос источников. Сеть — только из фоновых потоков. */
object SourceRepository {

    private const val TIMEOUT_MS = 20_000L

    /** Узнаёт по ссылке, что это: дополнение (manifest.json) или M3U-плейлист, и загружает его. */
    suspend fun add(store: StreamStore, input: String): StreamSource = withContext(Dispatchers.IO) {
        val url = input.trim()
        val base = AddonClient.baseUrl(url)
        // У дополнения хранится адрес без /manifest.json, у плейлиста — ссылка как есть.
        if (store.sources().any { it.url == (base ?: url) }) throw IOException("Этот источник уже добавлен")
        val source = if (base != null) {
            val json = AddonClient.getJson(AddonClient.manifestUrl(base))
            val manifest = AddonClient.parseManifest(json)
            if (manifest.resources.none { it.name == "stream" || it.name == "catalog" }) {
                throw IOException("Дополнение не отдаёт ни каталогов, ни ссылок на видео")
            }
            StreamSource(UUID.randomUUID().toString(), base, manifest.name, StreamSource.Kind.ADDON, manifest = json.toString())
        } else {
            val videos = StreamRepository.fetchPlaylist(url)
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "Плейлист" }
            StreamSource(UUID.randomUUID().toString(), url, name, StreamSource.Kind.PLAYLIST).also {
                store.putVideos(itemsKey(it), videos)
            }
        }
        store.saveSource(source)
        source
    }

    /** Перечитывает плейлист или manifest.json. */
    suspend fun refresh(store: StreamStore, source: StreamSource): StreamSource = withContext(Dispatchers.IO) {
        val updated = when (source.kind) {
            StreamSource.Kind.ADDON -> source.copy(manifest = AddonClient.getJson(AddonClient.manifestUrl(source.url)).toString())
            StreamSource.Kind.PLAYLIST -> {
                store.putVideos(itemsKey(source), StreamRepository.fetchPlaylist(source.url))
                source
            }
        }
        store.saveSource(updated)
        updated
    }

    fun itemsKey(source: StreamSource) = "source:${source.id}"

    /** Полки главного экрана: группы плейлистов и каталоги дополнений (первая страница). */
    suspend fun rows(store: StreamStore): List<SourceRow> = coroutineScope {
        val sources = store.sources().filter { it.enabled }
        val playlists = sources.filter { it.kind == StreamSource.Kind.PLAYLIST }.flatMap { source ->
            store.videos(itemsKey(source)).groupBy { it.group }.map { (group, items) ->
                SourceRow(listOf(source.name, group).filter { it.isNotBlank() }.joinToString(" · "), items)
            }
        }
        val addons = sources.filter { it.kind == StreamSource.Kind.ADDON }.flatMap { source ->
            val manifest = source.addon ?: return@flatMap emptyList()
            manifest.catalogs.filter { !it.needsExtra }.take(MAX_CATALOGS).map { catalog ->
                async(Dispatchers.IO) {
                    val items = withTimeoutOrNull(TIMEOUT_MS) {
                        runCatching { AddonClient.catalog(source.url, source.name, catalog) }.getOrNull()
                    }.orEmpty()
                    SourceRow("${source.name} · ${catalog.name}", items)
                }
            }
        }.awaitAll()
        (playlists + addons).filter { it.items.isNotEmpty() }
    }

    /** Поиск по всем включённым источникам: каталоги дополнений с поиском и названия в плейлистах. */
    suspend fun search(store: StreamStore, query: String): List<SourceRow> = coroutineScope {
        val sources = store.sources().filter { it.enabled }
        val needle = normalize(query)
        val playlistHits = sources.filter { it.kind == StreamSource.Kind.PLAYLIST }.mapNotNull { source ->
            val hits = store.videos(itemsKey(source)).filter { normalize(it.title).contains(needle) }
            if (hits.isEmpty()) null else SourceRow(source.name, hits)
        }
        val addonHits = sources.filter { it.kind == StreamSource.Kind.ADDON }.flatMap { source ->
            val manifest = source.addon ?: return@flatMap emptyList()
            manifest.catalogs.filter { it.searchable }.take(MAX_CATALOGS).map { catalog ->
                async(Dispatchers.IO) {
                    val items = withTimeoutOrNull(TIMEOUT_MS) {
                        runCatching { AddonClient.catalog(source.url, source.name, catalog, mapOf("search" to query)) }.getOrNull()
                    }.orEmpty()
                    SourceRow("${source.name} · ${catalog.name}", items)
                }
            }
        }.awaitAll()
        (playlistHits + addonHits).filter { it.items.isNotEmpty() }
    }

    /**
     * Все ссылки на это видео из всех включённых источников — из них пользователь
     * выбирает, откуда смотреть. Для фильма из плейлиста это тот же фильм в других плейлистах.
     */
    suspend fun streams(store: StreamStore, video: VideoItem): List<StreamOption> = coroutineScope {
        val sources = store.sources().filter { it.enabled }
        if (video.isAddon) {
            val type = video.contentType ?: return@coroutineScope emptyList()
            val id = video.videoId ?: return@coroutineScope emptyList()
            sources.filter { it.kind == StreamSource.Kind.ADDON }
                .filter { it.addon?.supports("stream", type, id) == true }
                .map { source ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(TIMEOUT_MS) {
                            runCatching { AddonClient.streams(source.url, source.name, type, id) }.getOrNull()
                        }.orEmpty()
                    }
                }.awaitAll().flatten()
        } else {
            val title = normalize(video.title)
            val own = video.streamUrl?.let { listOf(StreamOption(video.group.ifBlank { "Плейлист" }, "", video.title, it, video.requestHeaders, null)) }.orEmpty()
            own + sources.filter { it.kind == StreamSource.Kind.PLAYLIST }.flatMap { source ->
                store.videos(itemsKey(source))
                    .filter { it.streamUrl != video.streamUrl && normalize(it.title) == title }
                    .mapNotNull { other -> other.streamUrl?.let { StreamOption(source.name, "", other.title, it, other.requestHeaders, null) } }
            }
        }.distinctBy { it.url }
    }

    /** Ссылка для запуска без вопросов: при автопереходе берём ту же озвучку и качество. */
    suspend fun resolve(video: VideoItem): PlaybackInfo = withContext(Dispatchers.IO) {
        val base = video.streamAddonBase ?: video.addonBase ?: throw IOException("Нет источника")
        val type = video.contentType ?: throw IOException("Нет типа видео")
        val id = video.videoId ?: throw IOException("Выберите серию")
        val options = AddonClient.streams(base, "", type, id)
        val chosen = AddonClient.pick(options, base, video.streamHint)
            ?: throw IOException("Источник не нашёл ссылок на «${video.title}»")
        StreamRepository.directInfo(chosen.url, video.isLive, video.title, chosen.headers)
    }

    suspend fun episodes(show: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) { AddonClient.episodes(show) }

    /** Сравнение названий без регистра, ё/е, пунктуации и лишних пробелов. */
    fun normalize(title: String): String = title.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private const val MAX_CATALOGS = 6
}
