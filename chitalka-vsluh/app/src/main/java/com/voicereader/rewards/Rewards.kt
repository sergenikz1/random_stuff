package com.voicereader.rewards

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Учёт чтения для наград. Чтение «идёт», пока звучит голос или работает быстрое чтение;
 * пауза запускает минутный отсчёт, после которого серия «без перерыва» сгорает.
 */
object Rewards {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: SharedPreferences

    private val _state = MutableStateFlow(RewardsState())
    val state: StateFlow<RewardsState> = _state

    private val _events = MutableSharedFlow<RewardEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RewardEvent> = _events

    /** Кто сейчас читает: голос и быстрое чтение учитываются независимо. */
    private val readers = HashSet<String>()
    private var ticker: Job? = null
    private var pauseJob: Job? = null
    private var dirtyTicks = 0

    var today: () -> LocalDate = { LocalDate.now() }

    /** Пока открыто окно нового ранга, отсчёт перерыва стоит: серия не сгорит, пока смотрите награду. */
    @Volatile var celebrating = false

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("rewards", Context.MODE_PRIVATE)
        _state.value = RewardsEngine.prune(RewardsEngine.evaluateDay(read(), today()), today())
        save()
    }

    // --- Хуки чтения ---

    fun readingStarted(source: String) {
        readers += source
        pauseJob?.cancel(); pauseJob = null
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                update { s, ev ->
                    val day = today()
                    val evaluated = if (s.dayLog.isNotEmpty() && day.toString() !in s.dayLog) RewardsEngine.evaluateDay(s, day) else s
                    RewardsEngine.tick(evaluated, day, ev)
                }
                if (++dirtyTicks >= 10) { dirtyTicks = 0; save() }
            }
        }
    }

    fun readingStopped(source: String) {
        readers -= source
        if (readers.isNotEmpty()) return
        ticker?.cancel(); ticker = null
        save()
        if (_state.value.sessionSeconds == 0) return
        pauseJob?.cancel()
        _state.value = _state.value.copy(pauseLeft = RewardsEngine.PAUSE_GRACE_SECONDS)
        pauseJob = scope.launch {
            while (isActive && _state.value.pauseLeft != null) {
                delay(1000)
                if (!celebrating) _state.value = RewardsEngine.pauseTick(_state.value)
            }
            save()
        }
    }

    fun sentenceRead() = update { s, ev -> RewardsEngine.sentenceRead(s, ev) }
    fun wordsRead(count: Int) = update { s, ev -> RewardsEngine.wordsRead(s, count, ev) }
    fun chapterFinished() = update { s, ev -> RewardsEngine.chapterFinished(s, ev) }

    // --- Ежедневный экран ---

    fun pendingWelcome(): Welcome? = RewardsEngine.welcomeFor(_state.value, today())

    fun welcomeShown() {
        _state.value = _state.value.copy(welcomeShownDay = today().toString()); save()
    }

    // --- Оформление и переключатели ---

    fun edit(transform: (RewardsState) -> RewardsState) {
        _state.value = transform(_state.value); save()
    }

    private fun update(transform: (RewardsState, MutableList<RewardEvent>) -> RewardsState) {
        if (!::prefs.isInitialized) return
        val events = ArrayList<RewardEvent>()
        _state.value = transform(_state.value, events)
        events.forEach { _events.tryEmit(it) }
        if (events.any { it is RewardEvent.RankUp || it is RewardEvent.DayCounted }) save()
    }

    // --- Хранение ---

    private fun read(): RewardsState {
        val raw = prefs.getString("state", null) ?: return RewardsState()
        return runCatching {
            val o = JSONObject(raw)
            val log = o.optJSONObject("dayLog")
            val days = HashMap<String, Int>()
            log?.keys()?.forEach { days[it] = log.optInt(it) }
            val saved = HashSet<String>()
            o.optJSONArray("savedDays")?.let { a -> for (i in 0 until a.length()) saved += a.getString(i) }
            RewardsState(
                points = o.optLong("points"),
                bestSessionSeconds = o.optInt("best"),
                bestSessionDay = o.optString("bestDay").ifEmpty { null },
                dayLog = days,
                savedDays = saved,
                growthDays = o.optInt("growthDays"),
                lastCountedDay = o.optString("lastCounted").ifEmpty { null },
                drops = o.optInt("drops"),
                wilted = o.optBoolean("wilted"),
                welcomeShownDay = o.optString("welcomeShown").ifEmpty { null },
                wordsCarry = o.optInt("wordsCarry"),
                growKind = enumOr(o.optString("grow"), GrowKind.TREE),
                backgroundId = o.optString("bg").ifEmpty { null },
                textId = o.optString("text").ifEmpty { null },
                font = enumOr(o.optString("font"), ReaderFont.DEFAULT),
                anims = o.optJSONObject("anims")?.let { a ->
                    AnimKind.entries.mapNotNull { k -> a.optString(k.name).ifEmpty { null }?.let { k to it } }.toMap()
                } ?: emptyMap(),
                ribbonEnabled = o.optBoolean("ribbon", true),
                dailyEnabled = o.optBoolean("daily", true),
                celebrations = o.optBoolean("celebrations", true)
            )
        }.getOrDefault(RewardsState())
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)

    fun save() {
        if (!::prefs.isInitialized) return
        val s = _state.value
        val o = JSONObject()
            .put("points", s.points).put("best", s.bestSessionSeconds).put("bestDay", s.bestSessionDay ?: "")
            .put("dayLog", JSONObject(s.dayLog as Map<*, *>)).put("savedDays", JSONArray(s.savedDays.toList()))
            .put("growthDays", s.growthDays).put("lastCounted", s.lastCountedDay ?: "").put("drops", s.drops)
            .put("wilted", s.wilted).put("welcomeShown", s.welcomeShownDay ?: "").put("wordsCarry", s.wordsCarry)
            .put("grow", s.growKind.name).put("bg", s.backgroundId ?: "").put("text", s.textId ?: "")
            .put("font", s.font.name)
            .put("anims", JSONObject(s.anims.mapKeys { it.key.name } as Map<*, *>))
            .put("ribbon", s.ribbonEnabled).put("daily", s.dailyEnabled).put("celebrations", s.celebrations)
        prefs.edit().putString("state", o.toString()).apply()
    }
}
