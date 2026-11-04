package org.intergalactictv.client.streaming

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Native, focusable TV episode list. Discovery never covers or disables existing results. */
class WebEpisodesActivity : FragmentActivity() {
    private lateinit var show: VideoItem
    private lateinit var store: StreamStore
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var control: Button
    private lateinit var list: ListView
    private val items = mutableListOf<VideoItem>()
    private var job: Job? = null
    private var stopped = false
    private var finished = false
    private var generation = 0
    private val cacheKey: String get() = "web-episodes:${show.webEpisodeTarget?.seriesId ?: show.id}"
    private val episodeAdapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val text = convertView as? TextView ?: TextView(parent.context).apply {
                textSize = 20f; setTextColor(android.graphics.Color.WHITE)
                setPadding(24, 18, 24, 18); minimumHeight = 72
            }
            val video = items[position]
            text.text = "${video.webEpisodeTarget?.label ?: video.title}   ·   " +
                if (video.streamUrl == null) "Открыть серию" else "Готова к просмотру"
            return text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val selected = intent.getSerializableExtra("video") as? VideoItem
        if (selected == null || !selected.isWeb) { finish(); return }
        show = selected
        store = StreamStore(this)
        items.addAll(store.videos(cacheKey).filter { it.webEpisodeTarget != null })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
            setBackgroundColor(0xff101522.toInt())
        }
        root.addView(TextView(this).apply { text = "Серии · ${show.title}"; textSize = 24f; setTextColor(-1) })
        val progress = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        spinner = ProgressBar(this).apply { contentDescription = "Поиск серий" }
        progress.addView(spinner, LinearLayout.LayoutParams(40, 40))
        status = TextView(this).apply { textSize = 16f; setTextColor(-1); setPadding(16, 0, 16, 0) }
        progress.addView(status, LinearLayout.LayoutParams(0, 64, 1f))
        control = Button(this).apply {
            text = "Остановить"
            setOnClickListener {
                if (job?.isActive == true) {
                    stopped = true; job?.cancel(); status.text = "Поиск остановлен · серий: ${items.size}"; loading(false)
                } else { stopped = false; finished = false; startSearch() }
            }
        }
        progress.addView(control)
        root.addView(progress)
        list = ListView(this).apply {
            adapter = episodeAdapter; emptyView = null
            setOnItemClickListener { _, _, position, _ -> StreamActions.play(this@WebEpisodesActivity, items[position]) }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (items.isNotEmpty()) list.requestFocus() else control.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (::show.isInitialized && !stopped && !finished) startSearch()
    }

    private fun loading(active: Boolean) {
        spinner.visibility = if (active) View.VISIBLE else View.GONE
        control.text = if (active) "Остановить" else "Искать ещё"
    }

    private fun startSearch() {
        if (job?.isActive == true) return
        val request = ++generation
        loading(true)
        job = lifecycleScope.launch {
            try {
                search(this@WebEpisodesActivity, show, items.toList(), { video ->
                    val index = items.indexOfFirst { it.id == video.id }
                    if (index >= 0) items[index] = video else items.add(video)
                    items.sortWith(compareBy({ it.season }, { it.episode }))
                    episodeAdapter.notifyDataSetChanged()
                    store.putVideos(cacheKey, items)
                }, { status.text = it })
                finished = true
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                status.text = "Не удалось продолжить поиск. Найденные серии доступны; можно повторить."
            } finally { if (request == generation) loading(false) }
        }
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }

    companion object {
        internal var search: suspend (android.app.Activity, VideoItem, List<VideoItem>, (VideoItem) -> Unit, (String) -> Unit) -> Unit =
            { activity, show, cached, found, progress -> WebEpisodeSearch(activity).search(show, cached, found, progress) }
    }
}
