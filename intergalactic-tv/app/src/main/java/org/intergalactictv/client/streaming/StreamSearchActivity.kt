package org.intergalactictv.client.streaming

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.intergalactictv.client.R

class StreamSearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, StreamSearchFragment()).commit()
    }
}

class StreamSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {
    private val rows = ArrayObjectAdapter(ListRowPresenter())
    private val results = ArrayObjectAdapter(StreamCardPresenter())
    private var query = ""
    private var page = 0
    private var more = false
    private var job: Job? = null
    private var pageJob: Job? = null
    private var sourcesJob: Job? = null
    private var webJob: Job? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, row ->
            val video = item as? VideoItem ?: return@OnItemViewClickedListener
            // У видео из плейлиста очередь автоперехода — его полка в выдаче.
            val shelf = (row as? ListRow)?.adapter?.takeIf { it !== results && video.streamUrl != null }
            val playlist = shelf?.let { a -> (0 until a.size()).mapNotNull { a.get(it) as? VideoItem } }.orEmpty()
            StreamActions.show(this, video, playlist)
        })
        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ ->
            if (item is VideoItem) {
                val index = results.indexOf(item)
                if (index >= 0 && index >= results.size() - 5) loadPage()
            }
        })
    }
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.intent?.getStringExtra("query")?.takeIf { it.isNotBlank() }?.let { setSearchQuery(it, true) }
    }
    override fun getResultsAdapter(): ObjectAdapter = rows
    override fun onQueryTextChange(newQuery: String): Boolean { search(newQuery, 450); return true }
    override fun onQueryTextSubmit(query: String): Boolean { search(query, 0); return true }

    private fun search(text: String, delayMs: Long) {
        job?.cancel(); pageJob?.cancel(); sourcesJob?.cancel(); webJob?.cancel()
        val request = ++generation
        query = text.trim(); page = 0; more = false
        rows.clear(); results.clear()
        if (query.length < 2) return
        job = lifecycleScope.launch {
            delay(delayMs)
            if (request != generation) return@launch
            rows.add(ListRow(HeaderItem(0, "Ищу на Rutube: $query…"), results))
            more = true; loadPage()
            searchSources(query, request)
            searchWeb(query, request)
        }
    }

    private fun searchWeb(text: String, request: Int) {
        val browser = WebVideoSearch.browserSearch(text)
        val cards = ArrayObjectAdapter(StreamCardPresenter()).apply { add(browser) }
        val pending = ListRow(HeaderItem(10_000, "Интернет · проверяю первые ${WebVideoSearch.MAX_PAGES} страниц…"), cards)
        rows.add(0, pending)
        webJob = lifecycleScope.launch {
            val label = try {
                val result = WebVideoSearch().search(text)
                if (generation != request) return@launch
                cards.addAll(0, result.items)
                "Интернет · ${result.inspected} сайтов" +
                    if (result.unavailable > 0) " · часть страниц откроется в браузере" else ""
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                "Интернет · откройте поиск в браузере"
            }
            if (generation != request) return@launch
            val index = rows.indexOf(pending)
            if (index >= 0) rows.replace(index, ListRow(HeaderItem(10_000, label), cards))
        }
    }

    override fun onDestroyView() {
        job?.cancel(); pageJob?.cancel(); sourcesJob?.cancel(); webJob?.cancel()
        super.onDestroyView()
    }

    /** Выдача источников пользователя — отдельными полками под Rutube. */
    private fun searchSources(text: String, request: Int) {
        val context = context ?: return
        sourcesJob = lifecycleScope.launch {
            val found = try { SourceRepository.search(StreamStore(context), text) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            if (generation != request) return@launch
            found.forEach { row ->
                rows.add(ListRow(HeaderItem(rows.size().toLong(), row.title),
                    ArrayObjectAdapter(StreamCardPresenter()).apply { addAll(0, row.items) }))
            }
        }
    }

    private fun loadPage() {
        if (!more || pageJob?.isActive == true) return
        val request = generation
        val text = query
        pageJob = lifecycleScope.launch {
            try {
                val result = StreamRepository.search(text, page + 1)
                if (generation != request) return@launch
                page = result.page; more = result.hasNext && result.items.isNotEmpty()
                val known = (0 until results.size()).map { (results[it] as VideoItem).id }.toMutableSet()
                results.addAll(results.size(), result.items.filter { known.add(it.id) })
                updateRutubeRow(if (results.size() == 0) "Rutube · ничего не найдено: $text" else "Rutube · $text")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation != request) return@launch
                more = false
                updateRutubeRow("Rutube · ошибка · повторите поиск")
                Toast.makeText(context, e.message ?: "Ошибка сети", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateRutubeRow(title: String) {
        val index = (0 until rows.size()).firstOrNull { (rows[it] as? ListRow)?.adapter === results } ?: return
        rows.replace(index, ListRow(HeaderItem(0, title), results))
    }
}
