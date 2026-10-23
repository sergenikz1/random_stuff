package org.scryertv.client.data

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Итог входа: что сохранить в настройках. */
data class Session(val token: String, val username: String?, val isApiKey: Boolean)

data class HomeData(
    val counts: LibraryCounts?,
    val movies: List<MediaItem>,
    val series: List<MediaItem>,
    val anime: List<MediaItem>
)

data class TitleDetails(val item: MediaItem, val similar: List<MediaItem>)

data class MetadataResults(
    val movies: List<MediaItem>,
    val series: List<MediaItem>,
    val anime: List<MediaItem>
)

/** Что сделал сервер в ответ на «Добавить». */
sealed class AddOutcome {
    data class Added(val titleId: String, val alreadyExisted: Boolean) : AddOutcome()

    /** Прав на добавление нет — ушёл запрос на одобрение администратору. */
    object Requested : AddOutcome()
}

/** Сценарии приложения поверх GraphQL Scryer. */
object Repository {

    private const val API_KEY_LABEL = "Scryer TV"

    // ---------- вход ----------

    /** Вход по логину и паролю; затем выпускаем бессрочный API-ключ для ТВ. */
    suspend fun loginWithPassword(
        serverUrl: String,
        username: String,
        password: String,
        totp: String?
    ): Session {
        val server = ScryerApi.normalizeServerUrl(serverUrl)
            ?: throw ScryerException("Некорректный адрес сервера")
        val input = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("persistSession", true)
        if (!totp.isNullOrBlank()) input.put("totpCode", totp.trim())

        val login = ScryerApi.execute(
            Queries.LOGIN,
            JSONObject().put("input", input),
            bearer = null,
            server = server
        ).getJSONObject("login")

        if (login.optBoolean("passwordChangeRequired")) {
            throw ScryerException("Сначала смените временный пароль в веб-интерфейсе Scryer")
        }
        if (login.optBoolean("mfaEnrollmentRequired")) {
            throw ScryerException("Сначала настройте двухфакторный вход в веб-интерфейсе Scryer")
        }
        val sessionToken = login.getString("token")
        val name = login.optJSONObject("user")?.optStringOrNull("username") ?: username

        // Сессия живёт ограниченное время, ключ — пока его не отзовут. Если сервер
        // не дал выпустить ключ (например, просит свежее подтверждение MFA), остаёмся на сессии.
        val apiKey = runCatching { createApiKey(server, bearer = sessionToken) }.getOrNull()
        return if (apiKey != null) {
            Session(apiKey, name, isApiKey = true)
        } else {
            Session(sessionToken, name, isApiKey = false)
        }
    }

    /** Сервер без пароля (вход выключен или включён обход для локальной сети). */
    suspend fun loginWithoutPassword(serverUrl: String): Session {
        val server = ScryerApi.normalizeServerUrl(serverUrl)
            ?: throw ScryerException("Некорректный адрес сервера")
        val headers = ScryerApi.authlessHeaders(server)
            ?: throw ScryerException("Сервер требует логин и пароль")
        val key = createApiKey(server, bearer = null, headers = headers)
        return Session(key, null, isApiKey = true)
    }

    /** Готовый ключ из веб-интерфейса (Настройки → Безопасность → API-ключи). */
    suspend fun loginWithApiKey(serverUrl: String, apiKey: String): Session {
        val server = ScryerApi.normalizeServerUrl(serverUrl)
            ?: throw ScryerException("Некорректный адрес сервера")
        val data = ScryerApi.execute(Queries.SERVER_INFO, bearer = apiKey.trim(), server = server)
        val name = data.optJSONObject("me")?.optStringOrNull("username")
        return Session(apiKey.trim(), name, isApiKey = true)
    }

    private suspend fun createApiKey(
        server: HttpUrl,
        bearer: String?,
        headers: Map<String, String> = emptyMap()
    ): String {
        val input = JSONObject()
            .put("label", API_KEY_LABEL)
            .put("expiry", "NEVER")
        return ScryerApi.execute(
            Queries.CREATE_API_KEY,
            JSONObject().put("input", input),
            bearer = bearer,
            server = server,
            headers = headers
        ).getJSONObject("createMyApiKey").getString("apiKey")
    }

    suspend fun serverInfo(): ServerInfo {
        val data = ScryerApi.execute(Queries.SERVER_INFO)
        return ServerInfo(
            version = data.optString("scryerVersion"),
            username = data.optJSONObject("me")?.optStringOrNull("username")
        )
    }

    // ---------- библиотека ----------

    suspend fun home(limit: Int = 30): HomeData {
        val data = ScryerApi.execute(Queries.HOME, JSONObject().put("limit", limit))
        val counts = LibraryCounts(
            movies = data.optJSONObject("movies")?.optInt("totalCount") ?: 0,
            series = data.optJSONObject("series")?.optInt("totalCount") ?: 0,
            anime = data.optJSONObject("anime")?.optInt("totalCount") ?: 0
        )
        return HomeData(
            counts = counts,
            movies = titleList(data.optJSONObject("movies")),
            series = titleList(data.optJSONObject("series")),
            anime = titleList(data.optJSONObject("anime"))
        )
    }

    suspend fun catalog(
        facet: Facet?,
        offset: Int,
        limit: Int,
        query: String? = null,
        sortKey: String = "TITLE"
    ): CatalogPage {
        val variables = JSONObject()
            .put("facet", facet?.name ?: JSONObject.NULL)
            .put("query", query ?: JSONObject.NULL)
            .put("sort", JSONObject().put("key", sortKey).put("direction", "ASC"))
            .put("limit", limit)
            .put("offset", offset)
        val titles = ScryerApi.execute(Queries.CATALOG, variables).getJSONObject("titles")
        return CatalogPage(
            items = titleList(titles),
            hasMore = titles.optBoolean("hasMore"),
            totalCount = titles.optInt("totalCount")
        )
    }

    suspend fun title(id: String): TitleDetails {
        val data = ScryerApi.execute(Queries.TITLE, JSONObject().put("id", id))
        val title = data.optJSONObject("title")
            ?: throw ScryerException("Тайтл не найден или удалён")
        val similar = title.optJSONArray("moreLikeThis").objects().map(::parseDiscovery)
        return TitleDetails(parseTitle(title), similar)
    }

    suspend fun discovery(limit: Int = 20): List<DiscoverySection> {
        val home = ScryerApi.execute(Queries.DISCOVERY, JSONObject().put("limit", limit))
            .getJSONObject("discoveryHome")
        val sections = home.optJSONArray("personalizedSections").objects() +
            home.optJSONArray("publicSections").objects()
        return sections.mapNotNull { section ->
            val items = section.optJSONArray("items").objects().map(::parseDiscovery)
            if (items.isEmpty()) null
            else DiscoverySection(section.optString("title"), items)
        }
    }

    suspend fun searchLibrary(query: String): List<MediaItem> =
        catalog(facet = null, offset = 0, limit = 30, query = query, sortKey = "POPULARITY").items

    suspend fun searchMetadata(query: String): MetadataResults {
        val variables = JSONObject()
            .put("query", query)
            .put("language", metadataLanguage())
        val result = ScryerApi.execute(Queries.SEARCH_METADATA, variables)
            .getJSONObject("searchMetadataMulti")
        return MetadataResults(
            movies = result.optJSONArray("movies").objects().map { parseMetadata(it, Facet.MOVIE) },
            series = result.optJSONArray("series").objects().map { parseMetadata(it, Facet.SERIES) },
            anime = result.optJSONArray("anime").objects().map { parseMetadata(it, Facet.ANIME) }
        )
    }

    // ---------- загрузки и календарь ----------

    suspend fun queue(limit: Int = 50): List<QueueItem> {
        val page = ScryerApi.execute(Queries.QUEUE, JSONObject().put("limit", limit))
            .getJSONObject("downloadQueuePage")
        return page.optJSONArray("items").objects().map { item ->
            val title = item.optJSONObject("title")
            QueueItem(
                id = item.getString("id"),
                titleId = item.optStringOrNull("titleId"),
                titleName = item.optString("titleName"),
                facet = Facet.parse(item.optStringOrNull("facet")),
                clientName = item.optString("clientName"),
                displayState = item.optString("displayState"),
                progressPercent = item.optInt("progressPercent"),
                sizeBytes = item.optLongOrNull("sizeBytes"),
                remainingSeconds = item.optIntOrNull("remainingSeconds"),
                attentionReason = item.optStringOrNull("attentionReason"),
                imageUrl = ScryerApi.resolveUrl(
                    title?.optStringOrNull("backgroundUrl") ?: title?.optStringOrNull("posterUrl")
                )
            )
        }
    }

    suspend fun calendar(days: Int = 14): List<CalendarItem> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val start = Calendar.getInstance()
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days) }
        val variables = JSONObject()
            .put("start", format.format(start.time))
            .put("end", format.format(end.time))
        val items = ScryerApi.execute(Queries.CALENDAR, variables).optJSONArray("calendarEpisodes")
        return items.objects().map { item ->
            val availability = item.optJSONObject("mediaAvailability")
            CalendarItem(
                id = item.getString("id"),
                titleId = item.getString("titleId"),
                titleName = item.optString("titleName"),
                season = item.optStringOrNull("seasonNumber"),
                episode = item.optStringOrNull("episodeNumber"),
                episodeTitle = item.optStringOrNull("episodeTitle"),
                airDate = item.optStringOrNull("airDate"),
                imageUrl = ScryerApi.resolveUrl(
                    item.optStringOrNull("imageUrl") ?: item.optStringOrNull("titleBackgroundUrl")
                ),
                monitored = item.optBoolean("monitored"),
                availability = availability?.optString("state").orEmpty(),
                qualityLabel = availability?.optStringOrNull("primaryQualityLabel")
            )
        }.sortedBy { it.airDate ?: "9999" }
    }

    // ---------- действия ----------

    /**
     * Добавляет тайтл в библиотеку по умолчанию для его раздела и включает
     * мониторинг — Scryer сам начнёт искать релиз. Если прав на добавление нет,
     * отправляет запрос на одобрение.
     *
     * @param onlyFutureEpisodes для сериалов: качать только новые серии.
     */
    suspend fun addToLibrary(item: MediaItem, onlyFutureEpisodes: Boolean = false): AddOutcome {
        val facet = item.facet ?: throw ScryerException("Неизвестно, фильм это или сериал")
        val monitorType = when {
            facet == Facet.MOVIE -> "MONITORED"
            onlyFutureEpisodes -> "FUTURE_EPISODES"
            else -> "MISSING_AND_FUTURE_EPISODES"
        }
        val externalIds = addExternalIds(item, facet)
        val input = JSONObject()
            .put("name", item.name)
            .put("facet", facet.name)
            .put("monitored", true)
            .put("tags", JSONArray())
            .put("options", JSONObject().put("monitorType", monitorType))
            .put("externalIds", externalIds)
            .putOpt("smgId", item.smgId)
            .putOpt("tvdbId", item.tvdbId?.takeIf { it.isNotBlank() })
            .putOpt("tmdbId", item.tmdbId)
            .putOpt("imdbId", item.imdbId?.takeIf { it.isNotBlank() })
            .putOpt("year", item.year)
            .putOpt("overview", item.overview)
            .putOpt("sortTitle", item.sortTitle)
            .putOpt("slug", item.slug)
            .putOpt("runtimeMinutes", item.runtimeMinutes)
            .putOpt("language", item.language)
            .putOpt("contentStatus", item.contentStatus)

        return try {
            val result = ScryerApi.execute(Queries.ADD_TITLE, JSONObject().put("input", input))
                .getJSONObject("addTitle")
            AddOutcome.Added(
                titleId = result.getJSONObject("title").getString("id"),
                alreadyExisted = result.optBoolean("reusedExistingTitle")
            )
        } catch (e: ScryerException) {
            if (!e.forbidden) throw e
            submitRequest(item, facet, externalIds, monitorType)
            AddOutcome.Requested
        }
    }

    private suspend fun submitRequest(
        item: MediaItem,
        facet: Facet,
        externalIds: JSONArray,
        monitorType: String
    ) {
        val libraries = ScryerApi.execute(Queries.LIBRARIES, JSONObject().put("facet", facet.name))
            .optJSONArray("libraries").objects()
        val library = libraries.firstOrNull { it.optBoolean("isDefault") } ?: libraries.firstOrNull()
            ?: throw ScryerException("Нет доступной библиотеки для раздела «${facet.plural}»")
        val input = JSONObject()
            .put("libraryId", library.getString("id"))
            .put("facet", facet.name)
            .put("title", item.name)
            .put("externalIds", externalIds)
            .put("requestedMonitorType", monitorType)
            .putOpt("year", item.year)
            .putOpt("overview", item.overview)
            .putOpt("sortTitle", item.sortTitle)
            .putOpt("slug", item.slug)
            .putOpt("runtimeMinutes", item.runtimeMinutes)
            .putOpt("language", item.language)
            .putOpt("contentStatus", item.contentStatus)
        ScryerApi.execute(Queries.SUBMIT_REQUEST, JSONObject().put("input", input))
    }

    suspend fun setMonitored(titleId: String, monitored: Boolean): Boolean {
        val input = JSONObject().put("titleId", titleId).put("monitored", monitored)
        return ScryerApi.execute(Queries.SET_MONITORED, JSONObject().put("input", input))
            .getJSONObject("setTitleMonitored")
            .optBoolean("monitored", monitored)
    }

    /** Найти и поставить на загрузку лучший релиз для всего тайтла. */
    suspend fun queueBestRelease(titleId: String): String? {
        val input = JSONObject()
            .put("titleId", titleId)
            .put("scope", JSONObject().put("title", true))
        val result = ScryerApi.execute(Queries.QUEUE_BEST_RELEASE, JSONObject().put("input", input))
            .getJSONObject("queueBestRelease")
        if (result.optString("status") != "QUEUED") {
            throw ScryerException("Подходящий релиз уже загружается или не найден")
        }
        return result.optStringOrNull("sourceTitle")
    }

    // ---------- разбор ----------

    private fun titleList(page: JSONObject?): List<MediaItem> =
        page?.optJSONArray("items").objects().map(::parseTitle)

    private fun parseTitle(json: JSONObject): MediaItem {
        val id = json.getString("id")
        return MediaItem(
            key = "title:$id",
            titleId = id,
            name = json.optString("name"),
            facet = Facet.parse(json.optStringOrNull("facet")),
            year = json.optIntOrNull("year"),
            overview = json.optStringOrNull("overview"),
            posterUrl = ScryerApi.resolveUrl(json.optStringOrNull("posterUrl")),
            backgroundUrl = ScryerApi.resolveUrl(json.optStringOrNull("backgroundUrl")),
            rating = json.optJSONObject("ratings")?.optDoubleOrNull("rating"),
            monitored = if (json.has("monitored")) json.optBoolean("monitored") else null,
            libraryName = json.optStringOrNull("libraryName"),
            qualityTier = json.optStringOrNull("qualityTier"),
            sizeBytes = json.optLongOrNull("sizeBytes"),
            episodesOwned = json.optIntOrNull("episodesOwned"),
            episodesTotal = json.optIntOrNull("episodesTotal"),
            contentStatus = json.optStringOrNull("contentStatus"),
            runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
            imdbId = json.optStringOrNull("imdbId"),
            slug = json.optStringOrNull("slug"),
            sortTitle = json.optStringOrNull("sortTitle"),
            externalIds = json.optJSONArray("externalIds").objects().mapNotNull { ext ->
                val source = ext.optStringOrNull("source") ?: return@mapNotNull null
                val value = ext.optStringOrNull("value") ?: return@mapNotNull null
                ExternalId(source, ext.optStringOrNull("kind"), value)
            },
            playbackLinks = json.optJSONArray("playbackLinks").objects().mapNotNull { link ->
                val href = link.optStringOrNull("href") ?: return@mapNotNull null
                PlaybackLink(link.optString("displayName"), link.optString("provider"), href)
            }
        )
    }

    private fun parseDiscovery(json: JSONObject): MediaItem {
        // Как в веб-интерфейсе: источник приводим к короткому имени, по одному id на источник.
        val ids = LinkedHashMap<String, String>()
        json.optJSONArray("externalIds").objects().forEach { ext ->
            val source = normalizedSource(ext.optStringOrNull("source")) ?: return@forEach
            val value = ext.optStringOrNull("id") ?: return@forEach
            if (source !in ids) ids[source] = value
        }
        val resolvedId = json.optStringOrNull("resolvedTitleId")
            ?.takeIf { json.optBoolean("resolved") }
        return MediaItem(
            key = resolvedId?.let { "title:$it" } ?: "discovery:${json.optString("targetKey")}",
            titleId = resolvedId,
            name = json.optString("displayTitle"),
            facet = Facet.parse(json.optStringOrNull("contentType") ?: json.optStringOrNull("targetKind")),
            year = json.optIntOrNull("year"),
            overview = json.optStringOrNull("overview"),
            posterUrl = ScryerApi.resolveUrl(json.optStringOrNull("posterUrl")),
            backgroundUrl = ScryerApi.resolveUrl(json.optStringOrNull("backgroundUrl")),
            rating = json.optDoubleOrNull("rating"),
            sortTitle = json.optStringOrNull("sortTitle"),
            externalIds = ids.map { (source, value) -> ExternalId(source, null, value) },
            smgId = ids["smg"]?.toIntOrNull(),
            tvdbId = ids["tvdb"],
            tmdbId = ids["tmdb"]?.toIntOrNull(),
            imdbId = ids["imdb"]
        )
    }

    private fun parseMetadata(json: JSONObject, facet: Facet): MediaItem {
        val tvdbId = json.optStringOrNull("tvdbId")
        val smgId = json.optIntOrNull("smgId")
        val tmdbId = json.optIntOrNull("tmdbId")
        return MediaItem(
            key = "meta:${facet.name}:${smgId ?: tvdbId ?: tmdbId ?: json.optString("name")}",
            titleId = null,
            name = json.optString("name"),
            facet = facet,
            year = json.optIntOrNull("year"),
            overview = json.optStringOrNull("overview"),
            posterUrl = ScryerApi.resolveUrl(json.optStringOrNull("posterUrl")),
            contentStatus = json.optStringOrNull("status"),
            runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
            externalIds = json.optJSONArray("externalIds").objects().mapNotNull { ext ->
                val source = ext.optStringOrNull("source") ?: return@mapNotNull null
                val value = ext.optStringOrNull("value") ?: return@mapNotNull null
                ExternalId(source, ext.optStringOrNull("kind"), value)
            },
            smgId = smgId,
            tvdbId = tvdbId,
            tmdbId = tmdbId,
            imdbId = json.optStringOrNull("imdbId"),
            slug = json.optStringOrNull("slug"),
            sortTitle = json.optStringOrNull("sortTitle"),
            language = json.optStringOrNull("language")
        )
    }

    /**
     * Внешние id для `addTitle`, по правилам веб-интерфейса (`metadataResultExternalIds`):
     * без дублей; id SMG — это «title», id TMDB у сериалов и аниме — «series»,
     * а уже указанный источником вид не трогаем.
     */
    private fun addExternalIds(item: MediaItem, facet: Facet): JSONArray {
        val candidates = item.externalIds +
            listOfNotNull(
                item.smgId?.let { ExternalId("smg", null, it.toString()) },
                item.tvdbId?.let { ExternalId("tvdb", null, it) },
                item.tmdbId?.let { ExternalId("tmdb", null, it.toString()) },
                item.imdbId?.let { ExternalId("imdb", null, it) }
            )
        val seen = HashSet<String>()
        val result = JSONArray()
        for (candidate in candidates) {
            val source = candidate.source.trim().lowercase()
            val value = candidate.value.trim()
            if (source.isEmpty() || value.isEmpty() || !seen.add("$source:$value")) continue
            val kind = candidate.kind?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: when {
                    source == "smg" -> "title"
                    source == "tmdb" && facet != Facet.MOVIE -> "series"
                    else -> null
                }
            result.put(
                JSONObject().put("source", source).put("value", value).apply {
                    if (kind != null) put("kind", kind)
                }
            )
        }
        return result
    }

    private val SOURCE_ALIASES = mapOf(
        "anidb" to "anidb", "anidbnet" to "anidb",
        "anilist" to "anilist", "anilistco" to "anilist",
        "imdb" to "imdb", "imdbcom" to "imdb",
        "mal" to "mal", "myanimelist" to "mal", "myanimelistnet" to "mal",
        "themoviedb" to "tmdb", "themoviedborg" to "tmdb", "tmdb" to "tmdb",
        "trakt" to "trakt", "traktv" to "trakt",
        "thetvdb" to "tvdb", "thetvdbcom" to "tvdb", "tvdb" to "tvdb",
        "smg" to "smg"
    )

    private fun normalizedSource(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.replace(Regex("[\\s_.-]+"), "")
        return normalized?.let { SOURCE_ALIASES[it] }
    }

    /** Scryer ждёт трёхбуквенный код языка: «rus», «eng»… */
    private fun metadataLanguage(): String =
        runCatching { Locale.getDefault().isO3Language }.getOrNull()
            ?.takeIf { it.length == 3 } ?: "eng"
}

// ---------- JSON-помощники ----------

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

internal fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name)) null else (opt(name) as? Number)?.toInt() ?: optString(name).toIntOrNull()

internal fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name)) null else (opt(name) as? Number)?.toLong() ?: optString(name).toLongOrNull()

internal fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (isNull(name)) null else (opt(name) as? Number)?.toDouble() ?: optString(name).toDoubleOrNull()
