package org.scryertv.client.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.app.DetailsSupportFragmentBackgroundController
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.launch
import org.scryertv.client.R
import org.scryertv.client.data.AddOutcome
import org.scryertv.client.data.Facet
import org.scryertv.client.data.MediaItem
import org.scryertv.client.data.Repository
import org.scryertv.client.util.Format

class DetailsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.details_container, TitleDetailsFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_ITEM = "extra_item"

        fun intent(context: Context, item: MediaItem): Intent =
            Intent(context, DetailsActivity::class.java).putExtra(EXTRA_ITEM, item)
    }
}

/**
 * Карточка тайтла. Для тайтла из библиотеки — поиск релиза, мониторинг и
 * «Смотреть» в Plex/Jellyfin/Emby; для найденного или рекомендованного — «Добавить».
 */
class TitleDetailsFragment : DetailsSupportFragment() {

    private lateinit var item: MediaItem
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var actionsAdapter: ArrayObjectAdapter
    private lateinit var overviewRow: DetailsOverviewRow
    private lateinit var background: DetailsSupportFragmentBackgroundController

    private var busy = false
    private var parallaxEnabled = false
    private var loadedImages: Pair<String?, String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extra = requireActivity().intent.getSerializableExtra(DetailsActivity.EXTRA_ITEM)
        item = extra as? MediaItem ?: run {
            requireActivity().finish()
            return
        }
        background = DetailsSupportFragmentBackgroundController(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::item.isInitialized) return

        val rowPresenter = FullWidthDetailsOverviewRowPresenter(DescriptionPresenter()).apply {
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.background_card)
            actionsBackgroundColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
            onActionClickedListener = OnActionClickedListener { action -> onAction(action) }
        }
        val selector = ClassPresenterSelector().apply {
            addClassPresenter(DetailsOverviewRow::class.java, rowPresenter)
            addClassPresenter(ListRow::class.java, ListRowPresenter())
        }

        rowsAdapter = ArrayObjectAdapter(selector)
        actionsAdapter = ArrayObjectAdapter()
        overviewRow = DetailsOverviewRow(item).apply {
            actionsAdapter = this@TitleDetailsFragment.actionsAdapter
            imageDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.card_placeholder)
        }
        rowsAdapter.add(overviewRow)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, clicked, _, _ ->
            Nav.open(requireContext(), clicked)
        }

        showItem(item)
        item.titleId?.let(::loadTitle)
    }

    private fun showItem(newItem: MediaItem) {
        item = newItem
        overviewRow.item = newItem
        rebuildActions()
        loadImages()
    }

    private fun loadTitle(titleId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val details = Repository.title(titleId)
                if (!isAdded) return@launch
                showItem(details.item)
                showSimilar(details.similar)
            } catch (e: Exception) {
                val text = Nav.errorText(activity, e) ?: return@launch
                toast(text)
            }
        }
    }

    private fun showSimilar(items: List<MediaItem>) {
        while (rowsAdapter.size() > 1) rowsAdapter.removeItems(1, 1)
        if (items.isEmpty()) return
        val adapter = ArrayObjectAdapter(MediaPresenter()).apply { addAll(0, items) }
        rowsAdapter.add(ListRow(HeaderItem(1L, getString(R.string.row_similar)), adapter))
    }

    private fun rebuildActions() {
        actionsAdapter.clear()
        if (item.inLibrary) {
            item.playbackLinks.forEachIndexed { index, link ->
                actionsAdapter.add(Action(ACTION_PLAY_BASE + index, "Смотреть", link.serverName))
            }
            actionsAdapter.add(Action(ACTION_SEARCH_RELEASE, "Найти и скачать"))
            when (item.monitored) {
                true -> actionsAdapter.add(Action(ACTION_UNMONITOR, "Не отслеживать"))
                false -> actionsAdapter.add(Action(ACTION_MONITOR, "Отслеживать"))
                null -> Unit
            }
        } else if (item.facet == Facet.MOVIE) {
            actionsAdapter.add(Action(ACTION_ADD_ALL, "Добавить и скачать"))
        } else if (item.facet != null) {
            actionsAdapter.add(Action(ACTION_ADD_ALL, "Добавить", "все серии"))
            actionsAdapter.add(Action(ACTION_ADD_FUTURE, "Добавить", "только новые серии"))
        }
    }

    private fun onAction(action: Action) {
        val id = action.id
        when {
            id >= ACTION_PLAY_BASE -> item.playbackLinks.getOrNull((id - ACTION_PLAY_BASE).toInt())
                ?.let { openLink(it.href) }
            id == ACTION_ADD_ALL -> runBusy { add(onlyFuture = false) }
            id == ACTION_ADD_FUTURE -> runBusy { add(onlyFuture = true) }
            id == ACTION_SEARCH_RELEASE -> runBusy {
                val titleId = item.titleId ?: return@runBusy
                val release = Repository.queueBestRelease(titleId)
                toast(release?.let { "Скачивается: $it" } ?: "Релиз поставлен в загрузку")
            }
            id == ACTION_MONITOR || id == ACTION_UNMONITOR -> runBusy {
                val titleId = item.titleId ?: return@runBusy
                val monitored = Repository.setMonitored(titleId, id == ACTION_MONITOR)
                showItem(item.copy(monitored = monitored))
                toast(if (monitored) "Scryer будет искать новые релизы" else "Отслеживание выключено")
            }
        }
    }

    private suspend fun add(onlyFuture: Boolean) {
        when (val outcome = Repository.addToLibrary(item, onlyFuture)) {
            is AddOutcome.Added -> {
                toast(
                    if (outcome.alreadyExisted) "Уже есть в библиотеке"
                    else "Добавлено — Scryer ищет релиз"
                )
                showItem(item.copy(titleId = outcome.titleId, key = "title:${outcome.titleId}"))
                loadTitle(outcome.titleId)
            }
            AddOutcome.Requested -> toast("Запрос отправлен администратору на одобрение")
        }
    }

    /** Одно действие за раз: пульт легко отправляет двойное нажатие. */
    private fun runBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Nav.errorText(activity, e)?.let(::toast)
            } finally {
                busy = false
            }
        }
    }

    /** Ссылка медиасервера: на ТВ её откроет приложение Plex/Jellyfin/Emby или браузер. */
    private fun openLink(href: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
        } catch (e: ActivityNotFoundException) {
            toast("Нет приложения, которое умеет открыть эту ссылку")
        }
    }

    private fun loadImages() {
        val images = item.posterUrl to item.backgroundUrl
        if (images == loadedImages) return
        loadedImages = images

        item.posterUrl?.let { url ->
            Glide.with(this).asBitmap().load(url).into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (isAdded) overviewRow.imageDrawable = BitmapDrawable(resources, resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
        }
        item.backgroundUrl?.let { url ->
            Glide.with(this).asBitmap().load(url).into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (!isAdded) return
                    if (!parallaxEnabled) {
                        background.enableParallax()
                        parallaxEnabled = true
                    }
                    background.coverBitmap = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
        }
    }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }

    private class DescriptionPresenter : AbstractDetailsDescriptionPresenter() {
        override fun onBindDescription(viewHolder: ViewHolder, item: Any?) {
            val media = item as? MediaItem ?: return
            viewHolder.title.text = media.name
            viewHolder.subtitle.text = listOf(
                Format.mediaSubtitle(media),
                media.runtimeMinutes?.takeIf { it > 0 }?.let { "$it мин" }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" • ")
            val state = if (media.inLibrary) Format.libraryState(media) else "Нет в библиотеке"
            viewHolder.body.text = listOf(state, media.overview.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        }
    }

    companion object {
        private const val ACTION_ADD_ALL = 1L
        private const val ACTION_ADD_FUTURE = 2L
        private const val ACTION_SEARCH_RELEASE = 3L
        private const val ACTION_MONITOR = 4L
        private const val ACTION_UNMONITOR = 5L
        private const val ACTION_PLAY_BASE = 100L
    }
}
