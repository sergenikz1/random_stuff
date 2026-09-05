package com.voicereader.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.voicereader.data.Book
import com.voicereader.data.Chapter
import com.voicereader.data.LibraryStore
import com.voicereader.data.Settings
import com.voicereader.tts.InstalledVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class TtsStatus { INITIALIZING, READY, NO_ENGINE, NO_VOICE_DATA }

data class PlayerState(
    val bookId: String? = null,
    val title: String = "",
    val author: String = "",
    val sentences: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val language: String = "ru",
    val index: Int = 0,
    /** Диапазон произносимого сейчас слова внутри текущего предложения. */
    val wordRange: IntRange? = null,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val tts: TtsStatus = TtsStatus.INITIALIZING,
    val message: String? = null
) {
    val currentSentence: String get() = sentences.getOrElse(index) { "" }
    val chapterTitle: String
        get() = chapters.lastOrNull { it.startIndex <= index }?.title.orEmpty()
}

/**
 * Ядро воспроизведения: держит TextToSpeech, очередь предложений и позицию.
 * Живёт в процессе целиком, UI и уведомление подписаны на общий StateFlow.
 */
object PlayerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PlayerState())
    val state: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

    private val _voices = kotlinx.coroutines.flow.MutableStateFlow<List<InstalledVoice>>(emptyList())
    val voices: kotlinx.coroutines.flow.StateFlow<List<InstalledVoice>> = _voices

    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Номер «поколения» очереди: после перемотки или паузы старые колбэки игнорируются. */
    private var generation = 0
    private var enqueuedUpTo = -1
    private var pausedByFocusLoss = false

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                engine.setOnUtteranceProgressListener(progressListener)
                refreshVoices()
                applyLanguage(_state.value.language)
                _state.value = _state.value.copy(tts = TtsStatus.READY)
            } else {
                _state.value = _state.value.copy(tts = TtsStatus.NO_ENGINE)
            }
        }
    }

    fun refreshVoices() {
        val engine = tts ?: return
        val list = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { v ->
                !v.isNetworkConnectionRequired &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            .map { v ->
                InstalledVoice(
                    name = v.name,
                    locale = v.locale.toLanguageTag(),
                    quality = v.quality,
                    offline = true
                )
            }
            .sortedWith(compareByDescending<InstalledVoice> { it.quality }.thenBy { it.name })
        _voices.value = list

        val lang = _state.value.language
        val forLang = list.filter { it.locale.startsWith(lang, ignoreCase = true) }
        Settings.assignDefaultVoicesOnce((forLang.ifEmpty { list }).map { it.name }.distinct().take(3))
    }

    /** Голоса, подходящие для языка открытой книги (если таких нет — все установленные). */
    fun voicesForCurrentBook(): List<InstalledVoice> {
        val lang = _state.value.language
        val all = _voices.value
        return all.filter { it.locale.startsWith(lang, ignoreCase = true) }.ifEmpty { all }
    }

    private fun applyLanguage(language: String) {
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            _state.value = _state.value.copy(tts = TtsStatus.NO_VOICE_DATA)
        } else if (_state.value.tts == TtsStatus.NO_VOICE_DATA) {
            _state.value = _state.value.copy(tts = TtsStatus.READY)
        }
    }

    fun openBook(book: Book) {
        if (_state.value.bookId == book.id && _state.value.sentences.isNotEmpty()) return
        stopSpeaking()
        _state.value = PlayerState(
            bookId = book.id,
            title = book.title,
            author = book.author,
            loading = true,
            tts = _state.value.tts
        )
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { LibraryStore.content(book) }
            }
            content.onSuccess { c ->
                _state.value = _state.value.copy(
                    sentences = c.sentences,
                    chapters = c.chapters,
                    language = c.language,
                    index = book.position.coerceIn(0, (c.sentences.size - 1).coerceAtLeast(0)),
                    loading = false
                )
                applyLanguage(c.language)
                refreshVoices()
                LibraryStore.touch(book.id)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Не удалось открыть книгу: ${e.message ?: "ошибка чтения"}"
                )
            }
        }
    }

    fun closeBook() {
        stopSpeaking()
        savePosition()
        _state.value = PlayerState(tts = _state.value.tts)
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    fun toggle() = if (_state.value.isPlaying) pause() else play()

    fun play() {
        val s = _state.value
        if (s.sentences.isEmpty() || s.tts == TtsStatus.NO_ENGINE) return
        if (!requestFocus()) return
        generation++
        enqueuedUpTo = s.index - 1
        // Состояние обновляем до запуска службы: она сразу читает его для уведомления.
        _state.value = s.copy(isPlaying = true, message = null)
        PlaybackService.start(app)
        applyVoice()
        pump(FIRST_UTTERANCES, flushFirst = true)
    }

    fun pause() {
        stopSpeaking()
        _state.value = _state.value.copy(isPlaying = false, wordRange = null)
        savePosition()
        abandonFocus()
    }

    private fun stopSpeaking() {
        generation++
        runCatching { tts?.stop() }
    }

    fun seekTo(index: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val target = index.coerceIn(0, s.sentences.size - 1)
        val wasPlaying = s.isPlaying
        stopSpeaking()
        _state.value = s.copy(index = target, wordRange = null, isPlaying = false)
        savePosition()
        if (wasPlaying) play()
    }

    fun nextSentence() = seekTo(_state.value.index + 1)
    fun prevSentence() = seekTo(_state.value.index - 1)

    fun nextChapter() {
        val s = _state.value
        val next = s.chapters.firstOrNull { it.startIndex > s.index } ?: return
        seekTo(next.startIndex)
    }

    fun prevChapter() {
        val s = _state.value
        val currentStart = s.chapters.lastOrNull { it.startIndex <= s.index }?.startIndex ?: 0
        // Первое нажатие — к началу текущей главы, повторное — к предыдущей.
        val target = if (s.index - currentStart > 2) currentStart
        else s.chapters.lastOrNull { it.startIndex < currentStart }?.startIndex ?: 0
        seekTo(target)
    }

    /** Смена голоса на лету: перечитываем текущее предложение выбранным голосом. */
    fun selectVoice(slot: Int) {
        Settings.update { it.copy(activeVoice = slot.coerceIn(0, 2)) }
        if (_state.value.isPlaying) play() else applyVoice()
    }

    fun applyVoiceSettingsNow() {
        applyVoice()
        if (_state.value.isPlaying) play()
    }

    private fun applyVoice() {
        val engine = tts ?: return
        val profile = Settings.state.value.activeProfile
        engine.setPitch(profile.pitch.coerceIn(0.5f, 2.0f))
        engine.setSpeechRate(profile.rate.coerceIn(0.4f, 2.5f))
        val wanted = profile.voiceName
        if (wanted != null) {
            val voice: Voice? = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == wanted }
            if (voice != null) runCatching { engine.voice = voice }
        }
    }

    private const val FIRST_UTTERANCES = 2

    private fun pump(count: Int, flushFirst: Boolean = false) {
        val engine = tts ?: return
        val s = _state.value
        repeat(count) { i ->
            val next = enqueuedUpTo + 1
            if (next >= s.sentences.size) return
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "$generation:$next")
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val mode = if (flushFirst && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(s.sentences[next], mode, params, "$generation:$next")
            enqueuedUpTo = next
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (gen, idx) = parse(utteranceId) ?: return
            scope.launch {
                if (gen != generation) return@launch
                _state.value = _state.value.copy(index = idx, wordRange = null, isPlaying = true)
                savePosition()
            }
        }

        override fun onDone(utteranceId: String?) {
            val (gen, idx) = parse(utteranceId) ?: return
            scope.launch {
                if (gen != generation) return@launch
                val s = _state.value
                if (idx >= s.sentences.size - 1) {
                    _state.value = s.copy(isPlaying = false, wordRange = null, message = "Книга дочитана до конца")
                    abandonFocus()
                } else {
                    pump(1)
                }
            }
        }

        @Deprecated("Требуется базовым классом")
        override fun onError(utteranceId: String?) = onError(utteranceId, -1)

        override fun onError(utteranceId: String?, errorCode: Int) {
            val (gen, _) = parse(utteranceId) ?: return
            scope.launch {
                if (gen != generation) return@launch
                _state.value = _state.value.copy(
                    isPlaying = false,
                    message = "Движок синтеза речи вернул ошибку. Проверьте, что голос установлен и доступен офлайн."
                )
                abandonFocus()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (gen, idx) = parse(utteranceId) ?: return
            scope.launch {
                if (gen != generation || _state.value.index != idx) return@launch
                _state.value = _state.value.copy(wordRange = start until end)
            }
        }

        private fun parse(id: String?): Pair<Int, Int>? {
            val parts = id?.split(':') ?: return null
            if (parts.size != 2) return null
            val gen = parts[0].toIntOrNull() ?: return null
            val idx = parts[1].toIntOrNull() ?: return null
            return gen to idx
        }
    }

    private fun savePosition() {
        val s = _state.value
        s.bookId?.let { LibraryStore.savePosition(it, s.index) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // --- Аудиофокус: пауза на время звонка или чужого воспроизведения ---

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_state.value.isPlaying) { pausedByFocusLoss = true; pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) { pausedByFocusLoss = false; play() }
        }
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        // Фокус уже удержан (перемотка во время чтения) — повторный запрос не нужен.
        if (focusRequest != null) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Открыть системные настройки синтеза речи (выбор движка и загрузка офлайн-голосов). */
    fun ttsSettingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shutdown() {
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) abandonFocus()
    }
}
