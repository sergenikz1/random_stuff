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
        card.contentText = video.authorName ?: video.group.ifBlank { if (video.isRutube) "Rutube" else "Прямой поток" }
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
        if (!video.isRutube) return showSourced(fragment, video, playlist, onChanged)
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

    /** Видео из плейлиста или дополнения: пользователь выбирает, с какой ссылки смотреть. */
    private fun showSourced(fragment: Fragment, video: VideoItem, playlist: List<VideoItem>, onChanged: () -> Unit) {
        val context = fragment.requireContext()
        val store = StreamStore(context)
        val watch = if (video.isShow) "Серии и сезоны" else "Смотреть — выбрать ссылку"
        // У видео из плейлиста соседние видео группы — его «серии».
        val actions = listOfNotNull(watch, "Серии из плейлиста".takeIf { !video.isShow && playlist.size > 1 }, "Добавить / убрать из избранного")
        AlertDialog.Builder(context).setTitle(video.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Серии и сезоны" -> chooseEpisode(fragment, video)
                    "Смотреть — выбрать ссылку" -> chooseStream(fragment, video, playlist)
                    "Серии из плейлиста" -> AlertDialog.Builder(context).setTitle(video.group.ifBlank { "Плейлист" })
                        .setItems(playlist.map { it.title }.toTypedArray()) { _, index -> play(context, playlist[index], playlist) }
                        .setNegativeButton("Закрыть", null).show()
                    else -> { store.toggleFavorite(video); onChanged(); Toast.makeText(context, "Избранное обновлено", Toast.LENGTH_SHORT).show() }
                }
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun chooseEpisode(fragment: Fragment, show: VideoItem) {
        val context = fragment.requireContext()
        loading(fragment, "Загружаю серии…", { SourceRepository.episodes(show) }) { episodes ->
            if (episodes.isEmpty()) {
                Toast.makeText(context, "Источник не прислал список серий", Toast.LENGTH_LONG).show()
                return@loading
            }
            AlertDialog.Builder(context).setTitle(show.title)
                .setItems(episodes.map { it.title }.toTypedArray()) { _, index -> chooseStream(fragment, episodes[index], episodes) }
                .setNegativeButton("Закрыть", null).show()
        }
    }

    /** Собирает ссылки со всех включённых источников и даёт выбрать одну. */
    fun chooseStream(fragment: Fragment, video: VideoItem, playlist: List<VideoItem> = emptyList()) {
        val context = fragment.requireContext()
        val store = StreamStore(context)
        loading(fragment, "Ищу ссылки в источниках…", { SourceRepository.streams(store, video) }) { options ->
            when (options.size) {
                0 -> Toast.makeText(context, "Ни один включённый источник не дал ссылку на это видео", Toast.LENGTH_LONG).show()
                1 -> playOption(context, video, playlist, options.first())
                else -> AlertDialog.Builder(context).setTitle("Откуда смотреть · ${options.size}")
                    .setItems(options.map { listOf(it.sourceName, it.label).filter(String::isNotBlank).joinToString(" · ") }.toTypedArray()) { _, index ->
                        playOption(context, video, playlist, options[index])
                    }
                    .setNegativeButton("Отмена", null).show()
            }
        }
    }

    private fun playOption(context: Context, video: VideoItem, playlist: List<VideoItem>, option: StreamOption) {
        if (video.isAddon) {
            // Ссылки дополнений живут недолго — запоминаем источник и подпись, а адрес берём при запуске.
            fun pin(item: VideoItem) = item.copy(streamAddonBase = option.addonBase, streamHint = option.hint)
            play(context, pin(video), playlist.map(::pin))
        } else {
            play(context, video.copy(streamUrl = option.url, requestHeaders = option.headers, streamMime = option.mime), playlist)
        }
    }

    private fun <T> loading(fragment: Fragment, message: String, work: suspend () -> T, done: (T) -> Unit) {
        val context = fragment.requireContext()
        val dialog = AlertDialog.Builder(context).setMessage(message).setNegativeButton("Отмена", null).create()
        var job: Job? = null
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        job = fragment.lifecycleScope.launch {
            try {
                val result = work()
                if (!fragment.isAdded) return@launch
                dialog.setOnDismissListener(null); dialog.dismiss()
                done(result)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                dialog.setOnDismissListener(null); dialog.dismiss()
                Toast.makeText(context, e.message ?: "Источник не ответил", Toast.LENGTH_LONG).show()
            }
        }
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
    private var sourcesJob: Job? = null
    private var sourceRows: List<SourceRow> = emptyList()
    private var status = "Rutube и ваши источники"

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
                    105 -> refreshSources()
                    106 -> startActivity(Intent(requireContext(), org.scryertv.client.update.UpdateActivity::class.java))
                    107 -> startActivity(Intent(requireContext(), SourcesActivity::class.java))
                }
                is VideoItem -> StreamActions.show(this, item, playlistFor(item)) { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized) return
        render()
        loadSourceRows()
    }

    /** Очередь для автоперехода: соседние видео той же группы того же плейлиста. */
    private fun playlistFor(video: VideoItem): List<VideoItem> {
        if (video.streamUrl == null) return emptyList()
        return store.sources().asSequence()
            .map { store.videos(SourceRepository.itemsKey(it)) }
            .firstOrNull { list -> list.any { it.id == video.id } }
            ?.filter { it.group == video.group }
            .orEmpty()
    }

    private fun loadSourceRows() {
        sourcesJob?.cancel()
        sourcesJob = viewLifecycleOwner.lifecycleScope.launch {
            sourceRows = SourceRepository.rows(store)
            render()
        }
    }

    private fun refreshSources() {
        job?.cancel()
        status = "Обновляю источники…"; render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            var failed = 0
            store.sources().filter { it.enabled }.forEach { source ->
                try { SourceRepository.refresh(store, source) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
            }
            status = if (failed == 0) "Источники обновлены" else "Не ответили источники: $failed"
            render(); loadSourceRows()
        }
    }

    private fun render() {
        rows.clear()
        val menu = ArrayObjectAdapter(MenuCardPresenter()).apply { addAll(0, listOf(
            MenuCard(101, "Найти онлайн", status),
            MenuCard(107, "Источники", sourcesSummary()),
            MenuCard(102, "Открыть ссылку", "Видео, плейлист, страница с видео или manifest.json"),
            MenuCard(103, "Настройки плеера", "Автопереход, качество, история"),
            MenuCard(104, "Загрузки Scryer", "Библиотека и торрент-загрузки"),
            MenuCard(105, "Обновить источники", "Перечитать плейлисты и дополнения"),
            MenuCard(106, "Обновить приложение", "Проверить новую версию на GitHub")
        )) }
        rows.add(ListRow(HeaderItem(0, "Онлайн без скачивания"), menu))
        fun row(name: String, items: List<VideoItem>) {
            if (items.isEmpty()) return
            rows.add(ListRow(HeaderItem(rows.size().toLong(), name), ArrayObjectAdapter(StreamCardPresenter()).apply { addAll(0, items) }))
        }
        row("Продолжить просмотр", store.videos("history"))
        row("Избранное", store.videos("favorites"))
        sourceRows.forEach { row(it.title, it.items) }
    }

    private fun sourcesSummary(): String {
        val sources = store.sources()
        val on = sources.count { it.enabled }
        return if (sources.isEmpty()) "Добавьте плейлист или дополнение" else "Включено $on из ${sources.size}"
    }

    private fun search() = startActivity(Intent(requireContext(), StreamSearchActivity::class.java))

    private fun openUrl() {
        val field = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://…"; setSingleLine(true)
        }
        AlertDialog.Builder(requireContext()).setTitle("Любая ссылка: видео, плейлист или страница").setView(field)
            .setPositiveButton("Открыть") { _, _ -> loadUrl(field.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun loadUrl(url: String) {
        job?.cancel()
        status = "Открываю ссылку…"; render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Дополнение и плейлист из нескольких видео сохраняются как источники.
                if (AddonClient.baseUrl(url) != null) {
                    val source = SourceRepository.add(store, url)
                    status = "Добавлен источник «${source.name}»"; render(); loadSourceRows()
                    return@launch
                }
                val videos = StreamRepository.openUrl(url)
                if (videos.size == 1) StreamActions.play(requireContext(), videos.first())
                else {
                    val source = SourceRepository.add(store, url)
                    status = "Добавлен источник «${source.name}» · ${videos.size} видео"; render(); loadSourceRows()
                    return@launch
                }
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
