package com.flowvoice.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The voice keyboard: pick it from the keyboard switcher, tap the microphone,
 * and the dictated text is committed straight into whatever field has focus.
 */
class FlowImeService : InputMethodService(), DictationController.Listener {

    private lateinit var prefs: Prefs
    private lateinit var controller: DictationController

    private var root: View? = null
    private var micButton: ImageButton? = null
    private var pulse: View? = null
    private var statusView: TextView? = null
    private var languageChip: TextView? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        controller = DictationController(this, prefs)
        controller.listener = this
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        root = view

        micButton = view.findViewById(R.id.imeMic)
        pulse = view.findViewById(R.id.imePulse)
        statusView = view.findViewById(R.id.imeStatus)
        languageChip = view.findViewById(R.id.imeLanguage)

        micButton?.setOnClickListener { onMicTapped() }

        languageChip?.setOnClickListener {
            prefs.language = when (prefs.language) {
                "auto" -> "ru"
                "ru" -> "en"
                else -> "auto"
            }
            renderLanguage()
        }

        view.findViewById<ImageButton>(R.id.imeSettings).setOnClickListener { openApp() }
        view.findViewById<ImageButton>(R.id.imeBackspace).setOnClickListener { sendKey(KeyEvent.KEYCODE_DEL) }
        view.findViewById<ImageButton>(R.id.imeEnter).setOnClickListener { onEnter() }
        view.findViewById<ImageButton>(R.id.imeSwitch).setOnClickListener { switchKeyboard() }
        view.findViewById<View>(R.id.imeSpace).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        renderLanguage()
        renderIdleHint()
        return view
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        controller.cancel()
    }

    override fun onDestroy() {
        controller.listener = null
        controller.release()
        super.onDestroy()
    }

    /* ------------------------------------------------------------- actions */

    private fun onMicTapped() {
        if (!hasMicPermission()) {
            // A service cannot request permissions; send the user to the app screen.
            statusView?.text = getString(R.string.err_permission)
            openApp()
            return
        }
        if (prefs.engine == Prefs.ENGINE_CLOUD && prefs.apiKey.isBlank()) {
            statusView?.text = getString(R.string.ime_hint_setup)
            openApp()
            return
        }
        controller.toggle()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun switchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToNextInputMethod(false)) imm.showInputMethodPicker()
        } else {
            imm.showInputMethodPicker()
        }
    }

    private fun onEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val inputType = info?.inputType ?: 0
        val multiline = (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun sendKey(keyCode: Int) {
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /* -------------------------------------------------------------- render */

    private fun renderLanguage() {
        languageChip?.text = when (prefs.language) {
            "ru" -> getString(R.string.subtype_ru)
            "en" -> getString(R.string.subtype_en)
            else -> getString(R.string.lang_auto)
        }
    }

    private fun renderIdleHint() {
        statusView?.text = if (prefs.engine == Prefs.ENGINE_CLOUD && prefs.apiKey.isBlank()) {
            getString(R.string.ime_hint_setup)
        } else {
            getString(R.string.ime_hint_tap)
        }
    }

    override fun onState(state: DictationController.State) {
        when (state) {
            DictationController.State.IDLE -> {
                micButton?.setImageResource(R.drawable.ic_mic)
                micButton?.setBackgroundResource(R.drawable.bg_mic)
                pulse?.alpha = 0f
                renderIdleHint()
            }
            DictationController.State.RECORDING -> {
                micButton?.setImageResource(R.drawable.ic_stop)
                micButton?.setBackgroundResource(R.drawable.bg_mic_active)
                statusView?.text = getString(R.string.state_listening)
            }
            DictationController.State.PROCESSING -> {
                micButton?.setImageResource(R.drawable.ic_mic)
                micButton?.setBackgroundResource(R.drawable.bg_mic)
                pulse?.alpha = 0f
                statusView?.text = getString(R.string.state_processing)
            }
        }
    }

    override fun onLevel(level: Float) {
        if (controller.state != DictationController.State.RECORDING) return
        pulse?.let {
            it.alpha = 0.10f + level * 0.35f
            val scale = 1f + level * 0.30f
            it.scaleX = scale
            it.scaleY = scale
        }
    }

    override fun onPartial(text: String) {
        statusView?.text = text
    }

    override fun onResult(text: String, language: String?) {
        val connection = currentInputConnection
        if (connection == null) {
            statusView?.text = text
            return
        }
        val payload = if (prefs.trailingSpace) "$text " else text
        connection.commitText(payload, 1)
        renderIdleHint()
    }

    override fun onError(code: String, detail: String?) {
        statusView?.text = when (code) {
            "no-key" -> getString(R.string.err_no_key)
            "network" -> getString(R.string.err_network)
            "http" -> getString(R.string.err_http)
            "empty" -> getString(R.string.err_empty)
            "permission" -> getString(R.string.err_permission)
            "unavailable" -> getString(R.string.err_unavailable)
            else -> getString(R.string.err_mic)
        }
    }
}
