package com.flowvoice.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), DictationController.Listener {

    private lateinit var prefs: Prefs
    private lateinit var controller: DictationController
    private lateinit var history: HistoryStore

    private lateinit var micButton: ImageButton
    private lateinit var pulse: View
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var testResult: TextView
    private lateinit var silenceLabel: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var cloudFields: View

    private var lastText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        history = HistoryStore(this)
        controller = DictationController(this, prefs)
        controller.listener = this

        micButton = findViewById(R.id.micButton)
        pulse = findViewById(R.id.pulse)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        testResult = findViewById(R.id.testResult)
        silenceLabel = findViewById(R.id.silenceLabel)
        historyList = findViewById(R.id.historyList)
        cloudFields = findViewById(R.id.cloudFields)

        micButton.setOnClickListener { onMicTapped() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyResult() }
        findViewById<Button>(R.id.shareButton).setOnClickListener { shareResult() }

        wireKeyboardButtons()
        wireEngine()
        wireLanguage()
        wireToggles()
        wireFields()
        wireHistory()

        findViewById<TextView>(R.id.versionLabel).text =
            "FlowVoice ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    override fun onDestroy() {
        controller.listener = null
        controller.release()
        super.onDestroy()
    }

    /* ------------------------------------------------------------- wiring */

    private fun wireKeyboardButtons() {
        findViewById<Button>(R.id.enableKeyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.chooseKeyboard).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private fun wireEngine() {
        val cloud = findViewById<TextView>(R.id.engineCloud)
        val device = findViewById<TextView>(R.id.engineDevice)

        fun sync() {
            val isCloud = prefs.engine == Prefs.ENGINE_CLOUD
            cloud.isSelected = isCloud
            device.isSelected = !isCloud
            cloudFields.visibility = if (isCloud) View.VISIBLE else View.GONE
        }

        cloud.setOnClickListener { prefs.engine = Prefs.ENGINE_CLOUD; sync() }
        device.setOnClickListener {
            if (!DeviceRecognizer(this).isAvailable()) {
                toast(getString(R.string.err_unavailable))
            } else {
                prefs.engine = Prefs.ENGINE_DEVICE
                sync()
            }
        }
        sync()

        findViewById<TextView>(R.id.presetOpenai).setOnClickListener {
            applyPreset("https://api.openai.com/v1", "whisper-1", "gpt-4o-mini")
        }
        findViewById<TextView>(R.id.presetGroq).setOnClickListener {
            applyPreset("https://api.groq.com/openai/v1", "whisper-large-v3", "llama-3.3-70b-versatile")
        }

        findViewById<Button>(R.id.testButton).setOnClickListener {
            testResult.text = "…"
            val transcriber = Transcriber(prefs)
            thread {
                val error = transcriber.testConnection()
                runOnUiThread {
                    when (error) {
                        null -> {
                            testResult.setTextColor(ContextCompat.getColor(this, R.color.ok))
                            testResult.text = getString(R.string.test_ok)
                        }
                        "no-key" -> {
                            testResult.setTextColor(ContextCompat.getColor(this, R.color.bad))
                            testResult.text = getString(R.string.test_no_key)
                        }
                        else -> {
                            testResult.setTextColor(ContextCompat.getColor(this, R.color.bad))
                            testResult.text = error
                        }
                    }
                }
            }
        }
    }

    private fun applyPreset(baseUrl: String, stt: String, cleanup: String) {
        prefs.apiBaseUrl = baseUrl
        prefs.sttModel = stt
        prefs.cleanupModel = cleanup
        findViewById<EditText>(R.id.baseUrl).setText(baseUrl)
        findViewById<EditText>(R.id.sttModel).setText(stt)
        findViewById<EditText>(R.id.cleanupModel).setText(cleanup)
        toast(getString(R.string.saved))
    }

    private fun wireLanguage() {
        val auto = findViewById<TextView>(R.id.langAuto)
        val ru = findViewById<TextView>(R.id.langRu)
        val en = findViewById<TextView>(R.id.langEn)

        fun sync() {
            auto.isSelected = prefs.language == "auto"
            ru.isSelected = prefs.language == "ru"
            en.isSelected = prefs.language == "en"
        }

        auto.setOnClickListener { prefs.language = "auto"; sync() }
        ru.setOnClickListener { prefs.language = "ru"; sync() }
        en.setOnClickListener { prefs.language = "en"; sync() }
        sync()
    }

    private fun wireToggles() {
        findViewById<SwitchCompat>(R.id.cleanupSwitch).apply {
            isChecked = prefs.cleanup
            setOnCheckedChangeListener { _, checked -> prefs.cleanup = checked }
        }
        findViewById<SwitchCompat>(R.id.autoStopSwitch).apply {
            isChecked = prefs.autoStop
            setOnCheckedChangeListener { _, checked -> prefs.autoStop = checked }
        }
        findViewById<SwitchCompat>(R.id.trailingSwitch).apply {
            isChecked = prefs.trailingSpace
            setOnCheckedChangeListener { _, checked -> prefs.trailingSpace = checked }
        }

        val seek = findViewById<SeekBar>(R.id.silenceSeek)
        seek.progress = ((prefs.silenceMs - 600) / 100).coerceIn(0, seek.max)
        renderSilenceLabel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.silenceMs = 600 + progress * 100
                renderSilenceLabel()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    private fun renderSilenceLabel() {
        val seconds = prefs.silenceMs / 1000.0
        silenceLabel.text = "${getString(R.string.autostop_title)}: ${String.format(Locale.US, "%.1f", seconds)} s"
    }

    private fun wireFields() {
        bind(R.id.baseUrl, prefs.apiBaseUrl) { prefs.apiBaseUrl = it }
        bind(R.id.apiKey, prefs.apiKey) { prefs.apiKey = it }
        bind(R.id.sttModel, prefs.sttModel) { prefs.sttModel = it }
        bind(R.id.cleanupModel, prefs.cleanupModel) { prefs.cleanupModel = it }
        bind(R.id.dictionary, prefs.dictionary.joinToString("\n")) {
            prefs.dictionary = it.split('\n').map(String::trim).filter(String::isNotEmpty)
        }
    }

    private fun bind(id: Int, initial: String, onChange: (String) -> Unit) {
        val field = findViewById<EditText>(id)
        field.setText(initial)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
        })
    }

    private fun wireHistory() {
        findViewById<Button>(R.id.clearHistory).setOnClickListener {
            history.clear()
            renderHistory()
        }
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val items = history.list()
        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.history_empty)
            empty.setTextColor(ContextCompat.getColor(this, R.color.muted))
            empty.textSize = 13f
            empty.setPadding(0, 12, 0, 4)
            historyList.addView(empty)
            return
        }

        val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val inflater = LayoutInflater.from(this)
        items.take(25).forEach { item ->
            val row = inflater.inflate(R.layout.history_item, historyList, false)
            row.findViewById<TextView>(R.id.itemMeta).text = buildString {
                append(format.format(Date(item.timestamp)))
                item.language?.let { append("  ·  ").append(it.uppercase(Locale.US)) }
            }
            row.findViewById<TextView>(R.id.itemText).text = item.text
            row.setOnClickListener { copy(item.text) }
            historyList.addView(row)
        }
    }

    /* ---------------------------------------------------------- dictation */

    private fun onMicTapped() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        controller.toggle()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) controller.start()
            else toast(getString(R.string.err_permission))
        }
    }

    override fun onState(state: DictationController.State) {
        when (state) {
            DictationController.State.IDLE -> {
                micButton.setImageResource(R.drawable.ic_mic)
                micButton.setBackgroundResource(R.drawable.bg_mic)
                pulse.alpha = 0f
                status.text = getString(R.string.btn_record)
            }
            DictationController.State.RECORDING -> {
                micButton.setImageResource(R.drawable.ic_stop)
                micButton.setBackgroundResource(R.drawable.bg_mic_active)
                status.text = getString(R.string.state_listening)
            }
            DictationController.State.PROCESSING -> {
                micButton.setImageResource(R.drawable.ic_mic)
                micButton.setBackgroundResource(R.drawable.bg_mic)
                pulse.alpha = 0f
                status.text = getString(R.string.state_processing)
            }
        }
    }

    override fun onLevel(level: Float) {
        if (controller.state != DictationController.State.RECORDING) return
        pulse.alpha = 0.10f + level * 0.35f
        val scale = 1f + level * 0.35f
        pulse.scaleX = scale
        pulse.scaleY = scale
    }

    override fun onPartial(text: String) {
        result.text = text
        result.setTextColor(ContextCompat.getColor(this, R.color.muted))
    }

    override fun onResult(text: String, language: String?) {
        lastText = text
        result.text = text
        result.setTextColor(ContextCompat.getColor(this, R.color.text))
        renderHistory()
    }

    override fun onError(code: String, detail: String?) {
        val message = when (code) {
            "no-key" -> getString(R.string.err_no_key)
            "network" -> getString(R.string.err_network)
            "http" -> detail ?: getString(R.string.err_http)
            "empty" -> getString(R.string.err_empty)
            "permission" -> getString(R.string.err_permission)
            "unavailable" -> getString(R.string.err_unavailable)
            else -> getString(R.string.err_mic)
        }
        status.text = message
        toast(message)
    }

    /* -------------------------------------------------------------- share */

    private fun copyResult() {
        if (lastText.isBlank()) return
        copy(lastText)
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FlowVoice", text))
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(getString(R.string.copied))
    }

    private fun shareResult() {
        if (lastText.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastText)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_MIC = 101
    }
}
