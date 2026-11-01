package org.scryertv.client.streaming

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Список источников видео: добавить, включить/выключить, обновить, удалить. */
class SourcesActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SourcesFragment(), android.R.id.content)
        }
    }
}

class SourcesFragment : GuidedStepSupportFragment() {

    private lateinit var store: StreamStore
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        store = StreamStore(requireContext())
        super.onCreate(savedInstanceState)
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            "Источники видео",
            "Подходит любая ссылка: плейлист M3U, дополнение в формате Stremio (manifest.json), " +
                "прямая ссылка на видео (MP4, HLS, DASH), видео Rutube или страница сайта, " +
                "где видео лежит в открытом виде. Когда у видео несколько ссылок, приложение спросит, откуда смотреть. " +
                "Торрент-ссылки не воспроизводятся. Добавляйте только источники, " +
                "которыми вам разрешено пользоваться.",
            "Scryer TV · Онлайн",
            null
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.addAll(buildActions())
    }

    private fun buildActions(): List<GuidedAction> {
        val context = requireContext()
        val list = mutableListOf<GuidedAction>()
        list += GuidedAction.Builder(context)
            .id(ID_ADD)
            .title("Добавить источник")
            .description("Любая ссылка: плейлист, видео, страница, manifest.json")
            .editDescription("")
            .descriptionEditable(true)
            .descriptionEditInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            .build()
        store.sources().forEachIndexed { index, source ->
            val state = if (source.enabled) "включён" else "выключен"
            list += GuidedAction.Builder(context)
                .id(ID_SOURCE_BASE + index * 10)
                .title(source.name)
                .description("${source.kind.label} · $state · ${source.url}")
                .subActions(listOf(
                    sub(ID_SOURCE_BASE + index * 10 + SUB_TOGGLE, if (source.enabled) "Выключить" else "Включить"),
                    sub(ID_SOURCE_BASE + index * 10 + SUB_REFRESH, "Обновить"),
                    sub(ID_SOURCE_BASE + index * 10 + SUB_DELETE, "Удалить")
                ))
                .build()
        }
        if (store.sources().isEmpty()) {
            list += GuidedAction.Builder(context)
                .id(ID_EMPTY)
                .title("Источников пока нет")
                .description("Без них работает поиск Rutube и открытие прямых ссылок")
                .infoOnly(true)
                .focusable(false)
                .build()
        }
        return list
    }

    private fun sub(id: Long, title: String) = GuidedAction.Builder(requireContext()).id(id).title(title).build()

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ID_ADD) {
            val url = action.editDescription?.toString()?.trim().orEmpty()
            action.editDescription = ""
            if (url.isNotEmpty()) run("Проверяю источник…") {
                val source = SourceRepository.add(store, url)
                toast("Добавлен: ${source.name}")
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = ((action.id - ID_SOURCE_BASE) / 10).toInt()
        val source = store.sources().getOrNull(index) ?: return true
        when ((action.id - ID_SOURCE_BASE) % 10) {
            SUB_TOGGLE -> { store.saveSource(source.copy(enabled = !source.enabled)); rebuild() }
            SUB_REFRESH -> run("Обновляю «${source.name}»…") {
                SourceRepository.refresh(store, source)
                toast("Обновлено: ${source.name}")
            }
            SUB_DELETE -> { store.removeSource(source); rebuild() }
        }
        return true
    }

    private fun run(message: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        toast(message)
        lifecycleScope.launch {
            try {
                work()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                toast("Не получилось: ${e.message ?: "источник не ответил"}")
            } finally {
                busy = false
                if (isAdded) rebuild()
            }
        }
    }

    private fun rebuild() { actions = buildActions() }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val ID_ADD = 1L
        private const val ID_EMPTY = 2L
        private const val ID_SOURCE_BASE = 100L
        private const val SUB_TOGGLE = 1L
        private const val SUB_REFRESH = 2L
        private const val SUB_DELETE = 3L
    }
}
