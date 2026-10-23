package org.scryertv.client.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.scryertv.client.BuildConfig
import org.scryertv.client.R
import org.scryertv.client.data.Prefs
import org.scryertv.client.data.Repository

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
        }
    }
}

class SettingsFragment : GuidedStepSupportFragment() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.get(requireContext())
        super.onCreate(savedInstanceState)
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.action_settings),
            getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
            null,
            null
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(info(ACTION_SERVER, "Сервер", prefs.serverUrl))
        actions.add(info(ACTION_USER, "Пользователь", prefs.username.ifBlank { "—" }))
        actions.add(info(ACTION_VERSION, "Версия Scryer", "проверяю…"))
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_LOGOUT)
                .title("Сменить сервер или выйти")
                .description("Ключ останется в Scryer — отзовите его в веб-интерфейсе, если он больше не нужен")
                .build()
        )
        actions.add(
            info(
                ACTION_ABOUT,
                "О приложении",
                "Неофициальный клиент Scryer для телевизоров: библиотека, поиск, " +
                    "добавление тайтлов и очередь загрузок с пульта."
            )
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            val info = runCatching { Repository.serverInfo() }
            val version = findActionById(ACTION_VERSION) ?: return@launch
            version.description = info.getOrNull()?.version?.ifBlank { null }
                ?: info.exceptionOrNull()?.message
                ?: "нет связи"
            notifyActionChanged(findActionPositionById(ACTION_VERSION))
            info.getOrNull()?.username?.let { name ->
                prefs.username = name
                findActionById(ACTION_USER)?.description = name
                notifyActionChanged(findActionPositionById(ACTION_USER))
            }
        }
    }

    private fun info(id: Long, title: String, description: String) =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .description(description)
            .infoOnly(true)
            .focusable(false)
            .multilineDescription(true)
            .build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ACTION_LOGOUT) {
            prefs.clearSession()
            Nav.toSetup(requireActivity())
        }
    }

    companion object {
        private const val ACTION_SERVER = 1L
        private const val ACTION_USER = 2L
        private const val ACTION_VERSION = 3L
        private const val ACTION_LOGOUT = 4L
        private const val ACTION_ABOUT = 5L
    }
}
