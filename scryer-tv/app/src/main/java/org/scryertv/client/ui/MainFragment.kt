package org.scryertv.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.scryertv.client.R
import org.scryertv.client.data.CalendarItem
import org.scryertv.client.data.DiscoverySection
import org.scryertv.client.data.Facet
import org.scryertv.client.data.HomeData
import org.scryertv.client.data.MenuCard
import org.scryertv.client.data.QueueItem
import org.scryertv.client.data.Repository

/**
 * Главный экран: загрузки, календарь выхода, новое в библиотеке,
 * подборки «Обзора» Scryer и меню.
 */
class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val queueAdapter = ArrayObjectAdapter(QueuePresenter())

    private var home: HomeData? = null
    private var calendar: List<CalendarItem> = emptyList()
    private var discovery: List<DiscoverySection> = emptyList()
    private var loading = false

    private val status: StatusHost?
        get() = activity as? StatusHost

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter
        loadAll()
        watchQueue()
    }

    private fun setupUi() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_accent)

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MenuCard) onMenuCard(item) else Nav.open(requireContext(), item)
        }
    }

    private fun onMenuCard(card: MenuCard) {
        val context = requireContext()
        when (card.action) {
            MenuCard.ACTION_ONLINE -> startActivity(Intent(context, org.scryertv.client.streaming.StreamingActivity::class.java))
            MenuCard.ACTION_SEARCH -> startActivity(Intent(context, SearchActivity::class.java))
            MenuCard.ACTION_QUEUE -> startActivity(Intent(context, QueueActivity::class.java))
            MenuCard.ACTION_SETTINGS -> startActivity(Intent(context, SettingsActivity::class.java))
            MenuCard.ACTION_LIBRARY_MOVIE -> startActivity(LibraryActivity.intent(context, Facet.MOVIE))
            MenuCard.ACTION_LIBRARY_SERIES -> startActivity(LibraryActivity.intent(context, Facet.SERIES))
            MenuCard.ACTION_LIBRARY_ANIME -> startActivity(LibraryActivity.intent(context, Facet.ANIME))
            MenuCard.ACTION_REFRESH -> loadAll()
        }
    }

    /**
     * Библиотека — обязательная часть экрана: без неё показываем ошибку.
     * Календарь, «Обзор» и очередь — по возможности: их отказ экран не ломает.
     */
    private fun loadAll() {
        if (loading) return
        loading = true
        if (home == null) status?.showLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                coroutineScope {
                    val homeJob = async { Repository.home() }
                    val calendarJob = async { runCatching { Repository.calendar() }.getOrDefault(emptyList()) }
                    val discoveryJob = async { runCatching { Repository.discovery() }.getOrDefault(emptyList()) }
                    val queueJob = async { runCatching { Repository.queue() }.getOrNull() }
                    home = homeJob.await()
                    calendar = calendarJob.await()
                    discovery = discoveryJob.await()
                    queueJob.await()?.let(::updateQueue)
                }
                status?.hideStatus()
                renderRows()
            } catch (e: Exception) {
                val text = Nav.errorText(activity, e) ?: return@launch
                if (home == null) {
                    status?.showError(text) { loadAll() }
                } else {
                    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
                }
            } finally {
                loading = false
            }
        }
    }

    /** Пока экран на виду, раз в 10 секунд обновляем полку загрузок. */
    private fun watchQueue() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(QUEUE_REFRESH_MS)
                    val items = runCatching { Repository.queue() }.getOrNull() ?: continue
                    val hadRow = queueAdapter.size() > 0
                    updateQueue(items)
                    if (hadRow != (queueAdapter.size() > 0)) renderRows()
                }
            }
        }
    }

    /**
     * Обновляет карточки на месте, если состав очереди не поменялся, —
     * так фокус пульта не прыгает при каждом обновлении прогресса.
     */
    private fun updateQueue(items: List<QueueItem>) {
        val sameIds = items.size == queueAdapter.size() &&
            items.indices.all { (queueAdapter.get(it) as? QueueItem)?.id == items[it].id }
        if (sameIds) {
            items.forEachIndexed { index, item ->
                if (queueAdapter.get(index) != item) queueAdapter.replace(index, item)
            }
        } else {
            queueAdapter.clear()
            if (items.isNotEmpty()) queueAdapter.addAll(0, items)
        }
    }

    private fun renderRows() {
        val data = home ?: return
        rowsAdapter.clear()
        var id = 0L

        fun row(title: String, items: List<Any>) {
            if (items.isEmpty()) return
            val adapter = ArrayObjectAdapter(cardPresenterSelector()).apply { addAll(0, items) }
            rowsAdapter.add(ListRow(HeaderItem(id++, title), adapter))
        }

        if (queueAdapter.size() > 0) {
            rowsAdapter.add(ListRow(HeaderItem(id++, getString(R.string.row_queue)), queueAdapter))
        }
        row(getString(R.string.row_calendar), calendar)
        row(getString(R.string.row_movies), data.movies)
        row(getString(R.string.row_series), data.series)
        row(getString(R.string.row_anime), data.anime)
        discovery.forEach { section -> row(section.title, section.items) }

        val counts = data.counts
        row(
            getString(R.string.row_menu),
            listOf(
                MenuCard(MenuCard.ACTION_ONLINE, "Смотреть онлайн", "Фильмы и сериалы без скачивания"),
                MenuCard(MenuCard.ACTION_SEARCH, getString(R.string.action_search), "Найти и добавить фильм или сериал"),
                MenuCard(MenuCard.ACTION_LIBRARY_MOVIE, Facet.MOVIE.plural, counts?.let { "В библиотеке: ${it.movies}" } ?: "Вся библиотека"),
                MenuCard(MenuCard.ACTION_LIBRARY_SERIES, Facet.SERIES.plural, counts?.let { "В библиотеке: ${it.series}" } ?: "Вся библиотека"),
                MenuCard(MenuCard.ACTION_LIBRARY_ANIME, Facet.ANIME.plural, counts?.let { "В библиотеке: ${it.anime}" } ?: "Вся библиотека"),
                MenuCard(MenuCard.ACTION_QUEUE, getString(R.string.action_queue), "Очередь загрузок и импорт"),
                MenuCard(MenuCard.ACTION_SETTINGS, getString(R.string.action_settings), "Сервер и учётная запись"),
                MenuCard(MenuCard.ACTION_REFRESH, getString(R.string.action_refresh), "Перезагрузить все полки")
            )
        )
    }

    companion object {
        private const val QUEUE_REFRESH_MS = 10_000L
    }
}
