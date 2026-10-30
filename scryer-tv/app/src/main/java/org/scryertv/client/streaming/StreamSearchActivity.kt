package org.scryertv.client.streaming

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
import org.scryertv.client.R

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
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            (item as? VideoItem)?.let { StreamActions.show(this, it) }
        })
        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ ->
            if (item is VideoItem && results.indexOf(item) >= results.size() - 5) loadPage()
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
        job?.cancel(); pageJob?.cancel()
        val request = ++generation
        query = text.trim(); page = 0; more = false
        rows.clear(); results.clear()
        if (query.length < 2) return
        job = lifecycleScope.launch {
            delay(delayMs)
            if (request != generation) return@launch
            rows.add(ListRow(HeaderItem(0, "Ищу на Rutube: $query…"), results))
            more = true; loadPage()
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
                rows.replace(0, ListRow(HeaderItem(0, if (results.size() == 0) "Ничего не найдено: $text" else "Rutube · $text"), results))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation != request) return@launch
                more = false
                rows.replace(0, ListRow(HeaderItem(0, "Ошибка · повторите поиск"), results))
                Toast.makeText(context, e.message ?: "Ошибка сети", Toast.LENGTH_LONG).show()
            }
        }
    }
}
