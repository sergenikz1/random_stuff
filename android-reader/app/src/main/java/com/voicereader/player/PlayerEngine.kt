package com.voicereader.player

import com.voicereader.AppText
import com.voicereader.R

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voicereader.data.Book
import com.voicereader.data.Chapter
import com.voicereader.data.LibraryStore
import com.voicereader.data.MusicMode
import com.voicereader.data.Settings
import com.voicereader.rewards.Rewards
import com.voicereader.rewards.VoiceManner
import com.voicereader.tts.NeuralTtsBackend
import com.voicereader.tts.NeuralVoice
import com.voicereader.tts.SpeechBackend
import com.voicereader.tts.SpeechListener
import com.voicereader.tts.SystemTts
import com.voicereader.tts.SystemTtsBackend
import com.voicereader.tts.TtsStatus
import com.voicereader.tts.VoiceCatalog
import com.voicereader.tts.VoiceInstaller
import com.voicereader.tts.VoiceKind
import com.voicereader.tts.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Идёт загрузка модели или синтез первой фразы. */
    val preparing: Boolean = false,
    /** Прогресс распаковки нейросетевого голоса, null — распаковка не идёт. */
    val installProgress: Float? = null,
    val message: String? = null
) {
    val currentSentence: String get() = sentences.getOrElse(index) { "" }
    val chapterTitle: String
        get() = chapters.lastOrNull { it.startIndex <= index }?.title.orEmpty()
}

/**
 * Ядро воспроизведения: хранит позицию в книге и управляет источником речи.
 * Сам синтез делегируется движку — встроенной нейросетевой модели или системному TTS.
 */
object PlayerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    val systemTtsStatus: StateFlow<TtsStatus> get() = SystemTts.status

    private lateinit var app: Context
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false

    private var backend: SpeechBackend? = null
    /** Подпись текущего движка: если параметры голоса изменились, движок пересоздаётся. */
    private var backendKey: String? = null

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        SystemTts.init(app)
    }

    fun openBook(book: Book) {
        if (_state.value.bookId == book.id && _state.value.sentences.isNotEmpty()) return
        stopBackend()
        _state.value = PlayerState(bookId = book.id, title = book.title, author = book.author, loading = true)
        scope.launch {
            val content = withContext(Dispatchers.IO) { runCatching { LibraryStore.content(book) } }
            content.onSuccess { c ->
                _state.value = _state.value.copy(
                    sentences = c.sentences,
                    chapters = c.chapters,
                    language = c.language,
                    index = book.position.coerceIn(0, (c.sentences.size - 1).coerceAtLeast(0)),
                    loading = false
                )
                SystemTts.applyLanguage(c.language)
                LibraryStore.touch(book.id)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    message = AppText.get(R.string.err_open_book, e.message ?: AppText.get(R.string.err_read))
                )
            }
        }
    }

    fun closeBook() {
        pause()
        releaseBackend()
        _state.value = PlayerState()
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    fun toggle() = if (_state.value.isPlaying) pause() else play()

    fun play() {
        val snapshot = _state.value
        if (snapshot.sentences.isEmpty()) return
        scope.launch {
            val profile = Settings.state.value.activeProfile
            val neural = neuralVoiceFor(profile, snapshot.language)

            if (neural != null && !VoiceInstaller.isVoiceReady(app, neural)) {
                _state.value = _state.value.copy(installProgress = 0f)
                val installed = runCatching {
                    VoiceInstaller.installVoice(app, neural) { p ->
                        _state.value = _state.value.copy(installProgress = p)
                    }
                }
                _state.value = _state.value.copy(installProgress = null)
                if (installed.isFailure) {
                    _state.value = _state.value.copy(
                        message = AppText.get(R.string.err_prepare_voice, AppText.get(neural.titleRes))
                    )
                    return@launch
                }
            }

            if (!requestFocus()) return@launch
            stopPreview()
            val engine = ensureBackend(profile, neural, snapshot.language)
            _state.value = _state.value.copy(isPlaying = true, message = null)
            Rewards.readingStarted("voice")
            PlaybackService.start(app)
            engine.start(_state.value.sentences, _state.value.index)
        }
    }

    fun pause() {
        stopBackend()
        if (_state.value.isPlaying) Rewards.readingStopped("voice")
        _state.value = _state.value.copy(isPlaying = false, wordRange = null, preparing = false)
        savePosition()
        abandonFocus()
    }

    fun seekTo(index: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val target = index.coerceIn(0, s.sentences.size - 1)
        val wasPlaying = s.isPlaying
        stopBackend()
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
        applyVoiceSettingsNow()
    }

    fun applyVoiceSettingsNow() {
        val wasPlaying = _state.value.isPlaying
        stopBackend()
        releaseBackend()
        if (wasPlaying) play() else _state.value = _state.value.copy(isPlaying = false)
    }

    /** Нужный встроенный голос, если он подходит книге; иначе null — читает системный движок. */
    private fun neuralVoiceFor(profile: VoiceProfile, language: String): NeuralVoice? {
        if (profile.kind != VoiceKind.NEURAL) return null
        val voice = VoiceCatalog.byId(profile.neuralVoiceId) ?: return null
        return voice.takeIf { VoiceCatalog.matchesLanguage(it, language) }
    }

    private fun ensureBackend(profile: VoiceProfile, neural: NeuralVoice?, language: String): SpeechBackend {
        val manner = Rewards.state.value.manner
        val key = "${neural?.id ?: "system"}|${profile.systemVoiceName}|${profile.pitch}|${profile.rate}|$language|$manner"
        backend?.let { if (key == backendKey) return it }
        releaseBackend()
        val created: SpeechBackend = if (neural != null) {
            NeuralTtsBackend(app, neural, speechListener, profile.rate * manner.rate, manner.pauseMs)
        } else {
            if (profile.kind == VoiceKind.NEURAL) {
                _state.value = _state.value.copy(
                    message = AppText.get(R.string.msg_neural_other_language)
                )
            }
            SystemTtsBackend(speechListener, profile.copy(rate = profile.rate * manner.rate), language)
        }
        backend = created
        backendKey = key
        return created
    }

    private fun stopBackend() {
        runCatching { backend?.stop() }
    }

    private fun releaseBackend() {
        runCatching { backend?.release() }
        backend = null
        backendKey = null
    }

    private val speechListener = object : SpeechListener {
        override fun onSentenceStart(index: Int) = post {
            val before = _state.value
            // Очки — за дочитанное предложение и за пройденную главу, перемотка не считается.
            if (before.isPlaying && index == before.index + 1) {
                Rewards.sentenceRead()
                if (before.chapters.any { it.startIndex == index }) Rewards.chapterFinished()
            }
            _state.value = _state.value.copy(index = index, wordRange = null, isPlaying = true)
            savePosition()
        }

        override fun onWordRange(index: Int, range: IntRange) = post {
            if (_state.value.index == index) _state.value = _state.value.copy(wordRange = range)
        }

        override fun onFinished() = post {
            Rewards.sentenceRead(); Rewards.chapterFinished(); Rewards.readingStopped("voice")
            _state.value = _state.value.copy(isPlaying = false, wordRange = null, message = AppText.get(R.string.msg_book_finished))
            abandonFocus()
        }

        override fun onPreparing(preparing: Boolean) = post {
            _state.value = _state.value.copy(preparing = preparing)
        }

        override fun onError(message: String) = post {
            if (_state.value.isPlaying) Rewards.readingStopped("voice")
            _state.value = _state.value.copy(isPlaying = false, preparing = false, message = message)
            abandonFocus()
        }

        private fun post(action: () -> Unit) {
            scope.launch { action() }
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
    // В режиме «Не трогать» фокус не берётся вовсе, поэтому музыка в другом плеере не встаёт на паузу.
    // «Приглушать» берёт временный фокус с разрешением приглушения: система делает музыку тише.

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS ->
                // Пользователь сам включил музыку — в режиме приглушения читаем дальше вместе с ней.
                if (Settings.state.value.musicMode == MusicMode.DUCK) focusRequest = null else pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_state.value.isPlaying) { pausedByFocusLoss = true; pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) { pausedByFocusLoss = false; play() }
        }
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val mode = Settings.state.value.musicMode
        if (mode == MusicMode.MIX) return true
        // Фокус уже удержан (перемотка во время чтения) — повторный запрос не нужен.
        if (focusRequest != null) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val gain = if (mode == MusicMode.DUCK) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        else AudioManager.AUDIOFOCUS_GAIN
        val request = AudioFocusRequest.Builder(gain)
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

    /** Режим музыки сменили во время чтения — перезахватываем фокус в новом виде. */
    fun applyMusicMode() {
        if (!_state.value.isPlaying) return
        abandonFocus()
        requestFocus()
    }

    // --- Прослушивание голоса на экране оформления ---

    private var preview: NeuralTtsBackend? = null

    /** Читает короткую фразу выбранным голосом и манерой, не трогая позицию в книге. */
    fun previewVoice(voiceId: String?, manner: VoiceManner = Rewards.state.value.manner) {
        val voice = com.voicereader.tts.VoiceCatalog.byId(voiceId) ?: return
        if (_state.value.isPlaying) pause()
        stopPreview()
        scope.launch {
            if (!VoiceInstaller.isVoiceReady(app, voice)) {
                _state.value = _state.value.copy(installProgress = 0f)
                val ok = runCatching {
                    VoiceInstaller.installVoice(app, voice) { p -> _state.value = _state.value.copy(installProgress = p) }
                }.isSuccess
                _state.value = _state.value.copy(installProgress = null)
                if (!ok) return@launch
            }
            val silent = object : SpeechListener {
                override fun onSentenceStart(index: Int) {}
                override fun onWordRange(index: Int, range: IntRange) {}
                override fun onFinished() {}
                override fun onPreparing(preparing: Boolean) {}
                override fun onError(message: String) {}
            }
            val rate = Settings.state.value.activeProfile.rate * manner.rate
            preview = NeuralTtsBackend(app, voice, silent, rate, manner.pauseMs).also {
                it.start(listOf(AppText.get(R.string.preview_phrase), AppText.get(R.string.preview_phrase2)), 0)
            }
        }
    }

    private fun stopPreview() {
        preview?.release()
        preview = null
    }

    fun ttsSettingsIntent(): Intent = SystemTts.settingsIntent()
}
