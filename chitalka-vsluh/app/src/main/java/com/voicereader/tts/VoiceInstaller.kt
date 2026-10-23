package com.voicereader.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Нейросетевые модели лежат в assets, но espeak-ng и onnxruntime читают их
 * обычными файловыми операциями, поэтому при первом использовании голос
 * распаковывается во внутреннюю память приложения.
 */
object VoiceInstaller {

    private const val READY_MARKER = ".ready"

    fun root(context: Context): File = File(context.filesDir, "tts")

    fun dirFor(context: Context, name: String): File = File(root(context), name)

    fun isInstalled(context: Context, name: String): Boolean =
        File(dirFor(context, name), READY_MARKER).exists()

    fun installedBytes(context: Context, name: String): Long {
        val dir = dirFor(context, name)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Всё ли готово для работы конкретного голоса: и модель, и данные фонемизатора. */
    fun isVoiceReady(context: Context, voice: NeuralVoice): Boolean =
        isInstalled(context, voice.dir) && isInstalled(context, VoiceCatalog.ESPEAK_DIR)

    /**
     * Распаковывает голос и общие данные espeak-ng. Прогресс — доля от 0 до 1,
     * считается по байтам относительно ожидаемого объёма.
     */
    suspend fun installVoice(
        context: Context,
        voice: NeuralVoice,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val needEspeak = !isInstalled(context, VoiceCatalog.ESPEAK_DIR)
        val total = voice.approxBytes + if (needEspeak) VoiceCatalog.ESPEAK_APPROX_BYTES else 0L
        var copied = 0L

        val report: (Long) -> Unit = { delta ->
            copied += delta
            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
        }

        if (needEspeak) {
            copyAssetDir(context, "${VoiceCatalog.ASSET_ROOT}/${VoiceCatalog.ESPEAK_DIR}",
                dirFor(context, VoiceCatalog.ESPEAK_DIR), report)
        }
        if (!isInstalled(context, voice.dir)) {
            copyAssetDir(context, "${VoiceCatalog.ASSET_ROOT}/${voice.dir}",
                dirFor(context, voice.dir), report)
        }
        onProgress(1f)
    }

    fun uninstallVoice(context: Context, voice: NeuralVoice): Boolean =
        dirFor(context, voice.dir).deleteRecursively()

    private fun copyAssetDir(
        context: Context,
        assetPath: String,
        target: File,
        onBytes: (Long) -> Unit
    ) {
        // Незавершённая распаковка не должна выглядеть как готовый голос.
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyRecursively(context, assetPath, target, onBytes)
        File(target, READY_MARKER).writeText(System.currentTimeMillis().toString())
    }

    private fun copyRecursively(
        context: Context,
        assetPath: String,
        target: File,
        onBytes: (Long) -> Unit
    ) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyRecursively(context, "$assetPath/$child", File(target, child), onBytes)
        }
    }
}
