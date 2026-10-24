package org.scryertv.client.ui

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.scryertv.client.R
import org.scryertv.client.data.QueueItem
import org.scryertv.client.data.Repository

/** Очередь загрузок с прогрессом; обновляется раз в 5 секунд. */
class QueueActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, QueueGridFragment())
                .commit()
        }
    }
}

class QueueGridFragment : VerticalGridSupportFragment() {

    private val items = ArrayObjectAdapter(QueuePresenter())
    private var errorShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.row_queue)
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            numberOfColumns = 3
        }
        adapter = items
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            Nav.open(requireContext(), item)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    private suspend fun refresh() {
        try {
            val queue = Repository.queue(limit = 100)
            errorShown = false
            update(queue)
            title = if (queue.isEmpty()) "Загрузок нет" else "${getString(R.string.row_queue)} • ${queue.size}"
        } catch (e: Exception) {
            if (errorShown) return
            errorShown = true
            Nav.errorText(activity, e)?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Те же загрузки — обновляем карточки на месте, чтобы не сбивать фокус. */
    private fun update(queue: List<QueueItem>) {
        val sameIds = queue.size == items.size() &&
            queue.indices.all { (items.get(it) as? QueueItem)?.id == queue[it].id }
        if (sameIds) {
            queue.forEachIndexed { index, item ->
                if (items.get(index) != item) items.replace(index, item)
            }
        } else {
            items.clear()
            if (queue.isNotEmpty()) items.addAll(0, queue)
        }
    }

    companion object {
        private const val REFRESH_MS = 5_000L
    }
}
