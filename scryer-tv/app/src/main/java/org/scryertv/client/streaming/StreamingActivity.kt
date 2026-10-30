package org.scryertv.client.streaming

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.scryertv.client.R
import org.scryertv.client.data.MenuCard
import org.scryertv.client.data.Prefs
import org.scryertv.client.ui.MainActivity
import org.scryertv.client.ui.MenuCardPresenter
import org.scryertv.client.ui.SetupActivity

class StreamingActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, StreamingFragment()).commit()
    }
}

class StreamCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(320, 180)
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_card))
        }
        return ViewHolder(card)
    }
    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as VideoItem
        val card = viewHolder.view as ImageCardView
        card.titleText = video.title
        card.contentText = video.authorName ?: video.group.ifBlank { if (video.streamUrl == null) "Rutube" else "Прямой поток" }
        Glide.with(card).load(video.thumbnail).placeholder(R.drawable.card_placeholder).into(card.mainImageView)
    }
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card).clear(card.mainImageView)
    }
}

/** Shared actions used from both browsing and search results. */
object StreamActions {
    fun show(fragment: Fragment, video: VideoItem, playlist: List<VideoItem> = emptyList(), onChanged: () -> Unit = {}) {
        val context = fragment.requireContext()
        val store = StreamStore(context)
        AlertDialog.Builder(context).setTitle(video.title)
            .setItems(arrayOf("Смотреть", "Серии и сезоны", "Добавить / убрать из избранного")) { _, which ->
                when (which) {
                    0 -> play(context, video, playlist)
                    1 -> {
                        val dialog = AlertDialog.Builder(context).setMessage("Ищу серии…").setNegativeButton("Отмена", null).create()
                        var job: Job? = null
                        dialog.setOnDismissListener { job?.cancel() }
                        dialog.show()
                        job = fragment.lifecycleScope.launch {
                            try {
                                val episodes = if (playlist.isNotEmpty()) playlist else StreamRepository.episodes(video)
                                if (!fragment.isAdded) return@launch
                                // Clear listener before closing the loading dialog.
                                dialog.setOnDismissListener(null)
                                dialog.dismiss()
                                AlertDialog.Builder(context).setTitle("Серии · ${video.authorName.orEmpty()}")
                                    .setItems(episodes.map { it.title }.toTypedArray()) { _, index -> play(context, episodes[index], episodes) }
                                    .setNegativeButton("Закрыть", null).show()
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) {
                                dialog.setOnDismissListener(null); dialog.dismiss()
                                Toast.makeText(context, e.message ?: "Не удалось найти серии", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    2 -> { store.toggleFavorite(video); onChanged(); Toast.makeText(context, "Избранное обновлено", Toast.LENGTH_SHORT).show() }
                }
            }.setNegativeButton("Закрыть", null).show()
    }

    fun play(context: Context, video: VideoItem, playlist: List<VideoItem> = emptyList()) {
        // Persist the queue instead of putting hundreds of descriptions in a Binder transaction.
        StreamStore(context).putVideos("queue", playlist)
        context.startActivity(Intent(context, StreamPlayerActivity::class.java).putExtra("video", video))
    }
}

class StreamingFragment : BrowseSupportFragment() {
    private val rows = ArrayObjectAdapter(ListRowPresenter())
    private lateinit var store: StreamStore
    private var job: Job? = null
    private var status = "Поиск фильмов и сериалов на Rutube"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = StreamStore(requireContext())
        title = "Scryer TV · Онлайн"
        headersState = HEADERS_ENABLED
        brandColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_accent)
        adapter = rows
        setOnSearchClickedListener { search() }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is MenuCard -> when (item.action) {
                    101 -> search()
                    102 -> openUrl()
                    103 -> settings()
                    104 -> startActivity(Intent(requireContext(),
                        if (Prefs.get(requireContext()).token.isNotBlank()) MainActivity::class.java else SetupActivity::class.java))
                    105 -> loadCatalog()
                    106 -> startActivity(Intent(requireContext(), org.scryertv.client.update.UpdateActivity::class.java))
                }
                is VideoItem -> StreamActions.show(this, item, playlistFor(item)) { render() }
            }
        }
        render()
    }

    override fun onResume() { super.onResume(); if (::store.isInitialized) render() }

    private fun playlistFor(video: VideoItem): List<VideoItem> =
        if (video.streamUrl == null) emptyList()
        else store.videos("catalog").filter { it.group == video.group }

    private fun render() {
        rows.clear()
        val menu = ArrayObjectAdapter(MenuCardPresenter()).apply { addAll(0, listOf(
            MenuCard(101, "Найти онлайн", status),
            MenuCard(102, "Открыть ссылку", "Rutube, MP4, HLS, DASH или M3U"),
            MenuCard(103, "Настройки плеера", "Автопереход, качество, история"),
            MenuCard(104, "Загрузки Scryer", "Библиотека и торрент-загрузки"),
            MenuCard(105, "Обновить плейлист", "Загрузить сохранённый M3U заново"),
            MenuCard(106, "Обновить приложение", "Проверить новую версию на GitHub")
        )) }
        rows.add(ListRow(HeaderItem(0, "Онлайн без скачивания"), menu))
        fun row(name: String, items: List<VideoItem>) {
            if (items.isEmpty()) return
            rows.add(ListRow(HeaderItem(rows.size().toLong(), name), ArrayObjectAdapter(StreamCardPresenter()).apply { addAll(0, items) }))
        }
        row("Продолжить просмотр", store.videos("history"))
        row("Избранное", store.videos("favorites"))
        store.videos("catalog").groupBy { it.group.ifBlank { "Мой плейлист" } }.forEach { (name, items) -> row(name, items) }
    }

    private fun search() = startActivity(Intent(requireContext(), StreamSearchActivity::class.java))

    private fun openUrl() {
        val field = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://…"; setSingleLine(true); setText(store.catalogUrl)
        }
        AlertDialog.Builder(requireContext()).setTitle("Ссылка на видео или M3U-плейлист").setView(field)
            .setPositiveButton("Открыть") { _, _ -> loadUrl(field.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun loadCatalog() {
        if (store.catalogUrl.isBlank()) openUrl() else loadUrl(store.catalogUrl)
    }

    private fun loadUrl(url: String) {
        job?.cancel()
        status = "Открываю ссылку…"; render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val videos = StreamRepository.openUrl(url)
                if (videos.size == 1) StreamActions.play(requireContext(), videos.first())
                else { store.catalogUrl = url.trim(); store.putVideos("catalog", videos) }
                status = "Готово · ${videos.size} видео"; render()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                status = "Не удалось открыть ссылку"; render()
                AlertDialog.Builder(requireContext()).setMessage(e.message).setPositiveButton("Закрыть", null).show()
            }
        }
    }

    private fun settings() {
        val labels = arrayOf("Автопереход: ${if (store.autoplay) "вкл" else "выкл"}", "Качество: ${if (store.maxHeight == 0) "авто" else "${store.maxHeight}p"}", "Очистить историю", "Обновить приложение")
        AlertDialog.Builder(requireContext()).setTitle("Настройки онлайн-плеера").setItems(labels) { _, index ->
            when (index) {
                0 -> { store.autoplay = !store.autoplay; settings() }
                1 -> AlertDialog.Builder(requireContext()).setTitle("Максимальное качество")
                    .setItems(arrayOf("Авто", "360p", "480p", "720p", "1080p")) { _, value -> store.maxHeight = listOf(0,360,480,720,1080)[value] }.show()
                2 -> { store.clearHistory(); render() }
                3 -> startActivity(Intent(requireContext(), org.scryertv.client.update.UpdateActivity::class.java))
            }
        }.setNegativeButton("Закрыть", null).show()
    }
}
