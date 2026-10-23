package org.scryertv.client.ui

import android.view.ViewGroup
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.Presenter
import org.scryertv.client.R
import org.scryertv.client.data.CalendarItem
import org.scryertv.client.data.MediaItem
import org.scryertv.client.data.MenuCard
import org.scryertv.client.data.QueueItem
import org.scryertv.client.util.Format

/** Общая часть: карточка [MediaCardView] нужного размера. */
abstract class CardPresenter(
    private val widthRes: Int,
    private val heightRes: Int
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(MediaCardView(parent.context, widthRes, heightRes))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        bind(viewHolder.view as MediaCardView, item ?: return)
    }

    protected abstract fun bind(card: MediaCardView, item: Any)

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as MediaCardView).recycle()
    }
}

/** Постер тайтла: из библиотеки, «Обзора» или поиска. */
class MediaPresenter : CardPresenter(R.dimen.poster_width, R.dimen.poster_height) {
    override fun bind(card: MediaCardView, item: Any) {
        val media = item as? MediaItem ?: return
        val (badge, style) = when {
            !media.inLibrary -> null to R.drawable.badge_pill
            media.monitored == false -> "Не отслеж." to R.drawable.badge_pill
            !media.qualityTier.isNullOrBlank() -> media.qualityTier to R.drawable.badge_pill
            else -> "В библиотеке" to R.drawable.badge_pill_accent
        }
        card.bind(
            titleText = media.name,
            subtitleText = Format.mediaSubtitle(media),
            imageUrl = media.posterUrl,
            badgeText = badge,
            badgeStyle = style
        )
    }
}

/** Загрузка: кадр, прогресс и состояние. */
class QueuePresenter : CardPresenter(R.dimen.wide_width, R.dimen.wide_height) {
    override fun bind(card: MediaCardView, item: Any) {
        val queue = item as? QueueItem ?: return
        val problem = queue.attentionReason != null ||
            queue.displayState.endsWith("FAILED") || queue.displayState == "WARNING"
        card.bind(
            titleText = queue.titleName,
            subtitleText = queue.attentionReason ?: Format.queueSubtitle(queue),
            imageUrl = queue.imageUrl,
            badgeText = if (problem) Format.downloadState(queue.displayState) else queue.clientName,
            badgeStyle = if (problem) R.drawable.badge_pill_warning else R.drawable.badge_pill,
            progressPercent = queue.progressPercent
        )
    }
}

/** Серия из календаря выхода. */
class CalendarPresenter : CardPresenter(R.dimen.wide_width, R.dimen.wide_height) {
    override fun bind(card: MediaCardView, item: Any) {
        val episode = item as? CalendarItem ?: return
        val title = episode.episodeTitle?.let { "${episode.titleName}: $it" } ?: episode.titleName
        card.bind(
            titleText = title,
            subtitleText = Format.calendarSubtitle(episode),
            imageUrl = episode.imageUrl,
            badgeText = Format.episodeCode(episode),
            badgeStyle = if (episode.availability == "AVAILABLE") R.drawable.badge_pill_accent
            else R.drawable.badge_pill
        )
    }
}

/** Карточка-действие в меню. */
class MenuCardPresenter : CardPresenter(R.dimen.wide_width, R.dimen.wide_height) {
    override fun bind(card: MediaCardView, item: Any) {
        val menu = item as? MenuCard ?: return
        val icon = when (menu.action) {
            MenuCard.ACTION_SEARCH -> R.drawable.ic_search
            MenuCard.ACTION_SETTINGS -> R.drawable.ic_settings
            MenuCard.ACTION_QUEUE -> R.drawable.ic_download
            MenuCard.ACTION_LIBRARY_MOVIE -> R.drawable.ic_movie
            MenuCard.ACTION_LIBRARY_SERIES -> R.drawable.ic_tv
            MenuCard.ACTION_LIBRARY_ANIME -> R.drawable.ic_star
            else -> R.drawable.ic_refresh
        }
        card.bindAction(menu.title, menu.subtitle, icon)
    }
}

/** Один селектор на все типы карточек: полки могут смешивать их. */
fun cardPresenterSelector(): ClassPresenterSelector = ClassPresenterSelector()
    .addClassPresenter(MediaItem::class.java, MediaPresenter())
    .addClassPresenter(QueueItem::class.java, QueuePresenter())
    .addClassPresenter(CalendarItem::class.java, CalendarPresenter())
    .addClassPresenter(MenuCard::class.java, MenuCardPresenter())
