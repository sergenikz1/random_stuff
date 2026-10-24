package org.scryertv.client.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.scryertv.client.R
import org.scryertv.client.data.Prefs
import org.scryertv.client.data.Repository
import org.scryertv.client.data.ScryerApi
import org.scryertv.client.data.Session

/** Подключение к серверу Scryer: адрес и вход. */
class SetupActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SetupFragment(), android.R.id.content)
        }
    }
}

class SetupFragment : GuidedStepSupportFragment() {

    private lateinit var prefs: Prefs
    private var server = ""
    private var username = ""
    private var password = ""
    private var totp = ""
    private var apiKey = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.get(requireContext())
        server = prefs.serverUrl
        username = prefs.username
        super.onCreate(savedInstanceState)
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            "Подключение к Scryer",
            "Укажите адрес сервера, как в браузере, например http://192.168.1.10:8080, " +
                "и войдите под своей учётной записью Scryer. Приложение выпустит себе " +
                "API-ключ «Scryer TV» — отозвать его можно в веб-интерфейсе.",
            getString(R.string.app_name),
            ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher)
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(field(ID_SERVER, "Адрес сервера", server, InputType.TYPE_TEXT_VARIATION_URI))
        actions.add(field(ID_USER, "Логин", username, InputType.TYPE_TEXT_VARIATION_NORMAL))
        actions.add(field(ID_PASSWORD, "Пароль", "", InputType.TYPE_TEXT_VARIATION_PASSWORD))
        actions.add(
            field(ID_TOTP, "Код 2FA", "", InputType.TYPE_CLASS_NUMBER)
        )
        actions.add(button(ID_LOGIN, "Войти", "по логину и паролю"))
        actions.add(button(ID_NO_PASSWORD, "Подключиться без пароля", "если вход на сервере выключен"))
        actions.add(
            field(ID_API_KEY, "API-ключ", "", InputType.TYPE_TEXT_VARIATION_NORMAL)
        )
        actions.add(button(ID_LOGIN_KEY, "Войти по API-ключу", "ключ из Настройки → Безопасность"))
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_STATUS)
                .title("")
                .infoOnly(true)
                .focusable(false)
                .build()
        )
    }

    private fun field(id: Long, title: String, value: String, variation: Int) =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .description(value.ifEmpty { HINTS[id].orEmpty() })
            .editDescription(value)
            .descriptionEditable(true)
            .descriptionEditInputType(
                if (variation == InputType.TYPE_CLASS_NUMBER) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_TEXT or variation
            )
            .build()

    private fun button(id: Long, title: String, description: String) =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .description(description)
            .build()

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        // Leanback кладёт введённый текст в editDescription, а в списке видна description.
        val value = action.editDescription?.toString()?.trim().orEmpty()
        when (action.id) {
            ID_SERVER -> server = value
            ID_USER -> username = value
            ID_TOTP -> totp = value
            ID_API_KEY -> apiKey = value
            ID_PASSWORD -> password = action.editDescription?.toString().orEmpty()
        }
        if (action.id == ID_PASSWORD || action.id == ID_API_KEY) {
            // Пароль и ключ не показываем: в списке — точки, при повторном вводе поле пустое.
            action.description = "•".repeat(value.length.coerceAtMost(12))
            action.editDescription = ""
        } else {
            action.description = value.ifEmpty { HINTS[action.id].orEmpty() }
        }
        notifyActionChanged(findActionPositionById(action.id))
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_LOGIN -> {
                if (username.isBlank() || password.isEmpty()) {
                    setStatus("Введите логин и пароль")
                    return
                }
                connect { Repository.loginWithPassword(server, username, password, totp) }
            }
            ID_NO_PASSWORD -> connect { Repository.loginWithoutPassword(server) }
            ID_LOGIN_KEY -> {
                if (apiKey.isBlank()) {
                    setStatus("Введите API-ключ")
                    return
                }
                connect { Repository.loginWithApiKey(server, apiKey) }
            }
        }
    }

    private fun connect(login: suspend () -> Session) {
        if (busy) return
        if (ScryerApi.normalizeServerUrl(server) == null) {
            setStatus("Укажите адрес сервера")
            return
        }
        busy = true
        setStatus("Подключаюсь…")
        lifecycleScope.launch {
            try {
                val session = login()
                prefs.serverUrl = ScryerApi.normalizeServerUrl(server).toString()
                prefs.token = session.token
                prefs.username = session.username.orEmpty()
                ScryerApi.configure(prefs.serverUrl, prefs.token)
                startActivity(
                    Intent(requireContext(), MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                requireActivity().finish()
            } catch (e: Exception) {
                setStatus("Не получилось: " + (e.message ?: getString(R.string.error_network)))
            } finally {
                busy = false
            }
        }
    }

    private fun setStatus(text: String) {
        val action = findActionById(ID_STATUS) ?: return
        action.title = text
        notifyActionChanged(findActionPositionById(ID_STATUS))
    }

    companion object {
        private const val ID_SERVER = 1L
        private const val ID_USER = 2L
        private const val ID_PASSWORD = 3L
        private const val ID_TOTP = 4L
        private const val ID_LOGIN = 5L
        private const val ID_NO_PASSWORD = 6L
        private const val ID_API_KEY = 7L
        private const val ID_LOGIN_KEY = 8L
        private const val ID_STATUS = 9L

        private val HINTS = mapOf(
            ID_SERVER to "http://192.168.1.10:8080",
            ID_TOTP to "если включён",
            ID_API_KEY to "ska_…"
        )
    }
}
