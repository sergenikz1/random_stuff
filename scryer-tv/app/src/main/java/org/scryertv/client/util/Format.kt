package org.scryertv.client.util

import org.scryertv.client.data.CalendarItem
import org.scryertv.client.data.MediaItem
import org.scryertv.client.data.QueueItem
import java.text.SimpleDateFormat
import java.util.Locale

object Format {

    fun size(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit >= 3) String.format(Locale.US, "%.1f %s", value, units[unit])
        else "${value.toLong()} ${units[unit]}"
    }

    fun eta(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h} ч ${m} мин"
            m > 0 -> "${m} мин"
            else -> "< 1 мин"
        }
    }

    fun rating(value: Double?): String =
        if (value == null || value <= 0.0) "" else String.format(Locale.US, "★ %.1f", value)

    /** `DownloadDisplayStateValue` по-русски. */
    fun downloadState(state: String): String = when (state) {
        "QUEUED" -> "В очереди"
        "DOWNLOADING" -> "Загружается"
        "PAUSED" -> "Пауза"
        "POST_PROCESSING" -> "Обработка"
        "COMPLETED" -> "Загружено"
        "IMPORTED_SEEDING" -> "Импортировано, раздаётся"
        "FAILED" -> "Ошибка"
        "WARNING" -> "Предупреждение"
        "IMPORTING" -> "Импорт"
        "IMPORT_PENDING" -> "Ждёт импорта"
        "IMPORT_BLOCKED" -> "Импорт заблокирован"
        "IMPORT_FAILED" -> "Ошибка импорта"
        "IGNORED" -> "Пропущено"
        "REMOVING" -> "Удаляется"
        "REMOVE_FAILED" -> "Не удалось удалить"
        else -> state.lowercase().replace('_', ' ')
    }

    fun queueSubtitle(item: QueueItem): String = listOf(
        downloadState(item.displayState) +
            if (item.displayState == "DOWNLOADING") " ${item.progressPercent}%" else "",
        eta(item.remainingSeconds),
        size(item.sizeBytes)
    ).filter { it.isNotBlank() }.joinToString(" • ")

    /** Подпись под постером: год, раздел, рейтинг. */
    fun mediaSubtitle(item: MediaItem): String = listOf(
        item.year?.toString().orEmpty(),
        item.facet?.label.orEmpty(),
        rating(item.rating)
    ).filter { it.isNotBlank() }.joinToString(" • ")

    /** Строка состояния в карточке тайтла из библиотеки. */
    fun libraryState(item: MediaItem): String {
        val parts = mutableListOf<String>()
        item.monitored?.let { parts.add(if (it) "Отслеживается" else "Не отслеживается") }
        val owned = item.episodesOwned
        val total = item.episodesTotal
        if (owned != null && total != null && total > 0) parts.add("Серий: $owned из $total")
        item.qualityTier?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        size(item.sizeBytes).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        item.libraryName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" • ")
    }

    fun episodeCode(item: CalendarItem): String {
        val season = item.season?.toIntOrNull()
        val episode = item.episode?.toIntOrNull()
        return when {
            season != null && episode != null ->
                String.format(Locale.US, "S%02dE%02d", season, episode)
            episode != null -> "Эп. $episode"
            else -> ""
        }
    }

    fun calendarSubtitle(item: CalendarItem): String {
        val state = when (item.availability) {
            "AVAILABLE" -> item.qualityLabel?.let { "Есть • $it" } ?: "Есть"
            else -> if (item.monitored) "Ждём" else "Не отслеживается"
        }
        return listOf(airDate(item.airDate), episodeCode(item), state)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }

    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val shortDate = SimpleDateFormat("d MMM, EE", Locale("ru"))

    fun airDate(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching { shortDate.format(isoDate.parse(value)!!) }.getOrDefault(value)
    }
}
