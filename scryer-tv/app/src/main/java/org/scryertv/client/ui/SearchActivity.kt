package org.scryertv.client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.scryertv.client.R
import org.scryertv.client.data.Facet
import org.scryertv.client.data.MediaItem
import org.scryertv.client.data.MenuCard
import org.scryertv.client.data.Repository
import org.scryertv.client.util.Ime

class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        requestMicrophoneIfNeeded()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TitleSearchFragment())
                .commit()
        }
    }

    /** Пока открыта экранная клавиатура, «Назад» закрывает её, а не экран поиска. */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (Ime.hideIfVisible(this)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Голосовой поиск в leanback работает только с выданным разрешением. */
    private fun requestMicrophoneIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}

/**
 * Поиск сразу в двух местах: в своей библиотеке и в базе метаданных Scryer
 * (оттуда тайтл можно добавить).
 */
class TitleSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var query: String = ""
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(
            OnItemViewClickedListener { _, item, _, _ ->
                if (item is MediaItem) Nav.open(requireContext(), item)
            }
        )
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        scheduleSearch(newQuery, DEBOUNCE_MS)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        scheduleSearch(query, 0L)
        return true
    }

    private fun scheduleSearch(newQuery: String, delayMs: Long) {
        val trimmed = newQuery.trim()
        if (trimmed == query && rowsAdapter.size() > 0) return
        searchJob?.cancel()
        query = trimmed
        if (trimmed.length < 2) {
            rowsAdapter.clear()
            return
        }

        searchJob = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            val libraryJob = async { runCatching { Repository.searchLibrary(trimmed) } }
            val metadataJob = async { runCatching { Repository.searchMetadata(trimmed) } }
            val library = libraryJob.await()
            val metadata = metadataJob.await()
            if (trimmed != query || !isAdded) return@launch

            rowsAdapter.clear()
            var id = 0L
            fun row(title: String, items: List<MediaItem>) {
                if (items.isEmpty()) return
                val adapter = ArrayObjectAdapter(MediaPresenter()).apply { addAll(0, items) }
                rowsAdapter.add(ListRow(HeaderItem(id++, title), adapter))
            }

            val owned = library.getOrDefault(emptyList())
            row(getString(R.string.search_results_library), owned)
            metadata.getOrNull()?.let { found ->
                row(Facet.MOVIE.plural, found.movies)
                row(Facet.SERIES.plural, found.series)
                row(Facet.ANIME.plural, found.anime)
            }

            if (rowsAdapter.size() == 0) {
                val error = (metadata.exceptionOrNull() ?: library.exceptionOrNull())
                    ?.let { Nav.errorText(activity, it) }
                showEmpty(trimmed, error)
            }
        }
    }

    /** Пустая выдача — не пустой экран: показываем понятную карточку-подсказку. */
    private fun showEmpty(query: String, error: String?) {
        val adapter = ArrayObjectAdapter(MenuCardPresenter()).apply {
            add(
                MenuCard(
                    MenuCard.ACTION_INFO,
                    error ?: getString(R.string.search_empty, query),
                    if (error == null) getString(R.string.search_empty_hint) else ""
                )
            )
        }
        rowsAdapter.add(ListRow(HeaderItem(0L, ""), adapter))
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}
