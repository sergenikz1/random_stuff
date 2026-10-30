package org.scryertv.client.data

import java.io.Serializable

/** Раздел медиатеки Scryer. Значения совпадают с `MediaFacetValue` в GraphQL. */
enum class Facet(val label: String, val plural: String) {
    MOVIE("Фильм", "Фильмы"),
    SERIES("Сериал", "Сериалы"),
    ANIME("Аниме", "Аниме");

    companion object {
        fun parse(value: String?): Facet? = when (value?.trim()?.lowercase()) {
            "movie" -> MOVIE
            "series" -> SERIES
            "anime" -> ANIME
            else -> null
        }
    }
}

data class ExternalId(val source: String, val kind: String?, val value: String) : Serializable

data class PlaybackLink(val serverName: String, val provider: String, val href: String) : Serializable

/**
 * Карточка тайтла. Один тип на три источника: тайтл из библиотеки ([titleId] задан),
 * рекомендация из «Обзора» и результат поиска по метаданным — у последних двух
 * [titleId] пуст, и их можно добавить в библиотеку.
 */
data class MediaItem(
    val key: String,
    val titleId: String?,
    val name: String,
    val facet: Facet?,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val rating: Double? = null,
    val monitored: Boolean? = null,
    val libraryName: String? = null,
    val qualityTier: String? = null,
    val sizeBytes: Long? = null,
    val episodesOwned: Int? = null,
    val episodesTotal: Int? = null,
    val contentStatus: String? = null,
    val runtimeMinutes: Int? = null,
    val externalIds: List<ExternalId> = emptyList(),
    val smgId: Int? = null,
    val tvdbId: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val slug: String? = null,
    val sortTitle: String? = null,
    val language: String? = null,
    val playbackLinks: List<PlaybackLink> = emptyList()
) : Serializable {
    val inLibrary: Boolean
        get() = !titleId.isNullOrBlank()
}

/** Строка очереди загрузок. */
data class QueueItem(
    val id: String,
    val titleId: String?,
    val titleName: String,
    val facet: Facet?,
    val clientName: String,
    val displayState: String,
    val progressPercent: Int,
    val sizeBytes: Long?,
    val remainingSeconds: Int?,
    val attentionReason: String?,
    val imageUrl: String?
) : Serializable

/** Серия или фильм из календаря выхода. */
data class CalendarItem(
    val id: String,
    val titleId: String,
    val titleName: String,
    val season: String?,
    val episode: String?,
    val episodeTitle: String?,
    val airDate: String?,
    val imageUrl: String?,
    val monitored: Boolean,
    val availability: String,
    val qualityLabel: String?
) : Serializable

data class DiscoverySection(val title: String, val items: List<MediaItem>)

data class CatalogPage(val items: List<MediaItem>, val hasMore: Boolean, val totalCount: Int)

data class LibraryCounts(val movies: Int, val series: Int, val anime: Int)

data class ServerInfo(val version: String, val username: String?)

/** Карточка-действие на главном экране. */
data class MenuCard(val action: Int, val title: String, val subtitle: String) : Serializable {
    companion object {
        const val ACTION_SEARCH = 1
        const val ACTION_SETTINGS = 2
        const val ACTION_REFRESH = 3
        const val ACTION_QUEUE = 4
        const val ACTION_LIBRARY_MOVIE = 5
        const val ACTION_LIBRARY_SERIES = 6
        const val ACTION_LIBRARY_ANIME = 7
        const val ACTION_INFO = 8
        const val ACTION_ONLINE = 9
    }
}
