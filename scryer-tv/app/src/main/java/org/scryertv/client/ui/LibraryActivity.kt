package org.scryertv.client.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.scryertv.client.data.Facet
import org.scryertv.client.data.MediaItem
import org.scryertv.client.data.Repository

/** Вся библиотека одного раздела сеткой постеров, по алфавиту. */
class LibraryActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, LibraryGridFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_FACET = "extra_facet"

        fun intent(context: Context, facet: Facet): Intent =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_FACET, facet.name)
    }
}

class LibraryGridFragment : VerticalGridSupportFragment() {

    private lateinit var facet: Facet
    private val items = ArrayObjectAdapter(MediaPresenter())
    private var offset = 0
    private var hasMore = true
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        facet = requireActivity().intent.getStringExtra(LibraryActivity.EXTRA_FACET)
            ?.let { runCatching { Facet.valueOf(it) }.getOrNull() } ?: Facet.MOVIE
        title = facet.plural

        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            numberOfColumns = COLUMNS
        }
        adapter = items

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            Nav.open(requireContext(), item)
        }
        setOnItemViewSelectedListener(
            OnItemViewSelectedListener { _, item, _, _ ->
                if (item is MediaItem && items.indexOf(item) >= items.size() - COLUMNS * 2) loadMore()
            }
        )
        loadMore()
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        lifecycleScope.launch {
            try {
                val page = Repository.catalog(facet, offset, PAGE)
                offset += page.items.size
                hasMore = page.hasMore && page.items.isNotEmpty()
                items.addAll(items.size(), page.items)
                title = "${facet.plural} • ${page.totalCount}"
            } catch (e: Exception) {
                hasMore = false
                Nav.errorText(activity, e)?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                }
            } finally {
                loading = false
            }
        }
    }

    companion object {
        private const val COLUMNS = 6
        private const val PAGE = 60
    }
}
