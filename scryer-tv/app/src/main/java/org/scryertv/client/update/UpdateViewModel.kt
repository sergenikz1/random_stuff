package org.scryertv.client.update

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.scryertv.client.BuildConfig
import java.io.File

data class UpdateState(
    val message: String = "Проверяю обновления…",
    val update: UpdateMetadata? = null,
    val busy: Boolean = false,
    val progress: Int? = null,
    val file: File? = null
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    var initialized = false
        private set

    fun restore(update: UpdateMetadata) {
        if (initialized || update.versionCode <= BuildConfig.VERSION_CODE) return
        val file = File(getApplication<Application>().cacheDir, "updates/update-${update.versionCode}.apk")
        if (!file.isFile) return
        initialized = true
        mutable.value = UpdateState("Обновление скачано. Можно установить.", update, file = file)
    }

    fun check() {
        job?.cancel()
        val request = ++generation
        initialized = true
        mutable.value = UpdateState("Проверяю релизы Scryer TV на GitHub…", busy = true)
        job = viewModelScope.launch {
            try {
                check(BuildConfig.APPLICATION_ID == UpdateMetadata.PACKAGE) { "Для отладочной версии обновления не устанавливаются. Используйте релизный APK." }
                val update = UpdateRepository.check(BuildConfig.VERSION_CODE.toLong())
                if (request != generation) return@launch
                mutable.value = UpdateState(when {
                    update == null -> "Установлена актуальная версия. Новых обновлений нет."
                    update.minSdk > Build.VERSION.SDK_INT -> "Найдена версия ${update.versionName}, но для неё нужен более новый Android."
                    else -> "Доступна версия ${update.versionName} · ${update.size / (1024 * 1024)} МБ. Настройки и история сохранятся."
                }, update)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (request == generation) mutable.value = UpdateState("Не удалось проверить обновления. ${e.message.orEmpty()}") }
        }
    }

    fun download() {
        val update = mutable.value.update ?: return
        if (mutable.value.busy || update.minSdk > Build.VERSION.SDK_INT) return
        val request = ++generation
        mutable.value = UpdateState("Скачиваю версию ${update.versionName}…", update, busy = true, progress = 0)
        job = viewModelScope.launch {
            try {
                val file = UpdateRepository.download(getApplication(), update) { percent ->
                    if (generation == request) mutable.value = mutable.value.copy(progress = percent)
                }
                if (request == generation) mutable.value = UpdateState("Обновление скачано и проверено.", update, file = file)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (request == generation) mutable.value = UpdateState("Не удалось скачать обновление. ${e.message.orEmpty()}", update) }
        }
    }

    fun cancel() {
        job?.cancel(); generation++
        mutable.value = mutable.value.copy(message = "Загрузка отменена. Можно попробовать ещё раз.", busy = false, progress = null)
    }
}
