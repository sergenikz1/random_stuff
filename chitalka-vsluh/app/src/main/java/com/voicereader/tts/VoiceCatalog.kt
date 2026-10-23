package com.voicereader.tts

import androidx.annotation.StringRes
import com.voicereader.R

/**
 * Встроенные нейросетевые голоса (VITS, обученные в проекте Piper),
 * запускаются локально через sherpa-onnx. Модели лежат в assets и распаковываются
 * во внутреннюю память при первом использовании.
 */
data class NeuralVoice(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val locale: String,
    /** Папка внутри assets/ и внутри filesDir/tts/ */
    val dir: String,
    val modelFile: String,
    /** Размер модели, нужен только для индикатора прогресса распаковки. */
    val approxBytes: Long
)

object VoiceCatalog {

    /** Общие данные фонемизатора espeak-ng, одни на все голоса. */
    const val ESPEAK_DIR = "espeak-ng-data"
    const val ESPEAK_APPROX_BYTES = 18_000_000L

    const val ASSET_ROOT = "tts"

    val neural = listOf(
        NeuralVoice(
            id = "irina",
            titleRes = R.string.voice_irina,
            descriptionRes = R.string.voice_irina_desc,
            locale = "ru",
            dir = "irina",
            modelFile = "ru_RU-irina-medium.onnx",
            approxBytes = 63_201_422L
        ),
        NeuralVoice(
            id = "denis",
            titleRes = R.string.voice_denis,
            descriptionRes = R.string.voice_denis_desc,
            locale = "ru",
            dir = "denis",
            modelFile = "ru_RU-denis-medium.onnx",
            approxBytes = 63_201_422L
        ),
        NeuralVoice(
            id = "ruslan",
            titleRes = R.string.voice_ruslan,
            descriptionRes = R.string.voice_ruslan_desc,
            locale = "ru",
            dir = "ruslan",
            modelFile = "ru_RU-ruslan-medium.onnx",
            approxBytes = 63_201_422L
        ),
        // Награда за звание: модель Piper «dmitri» под гендерно-нейтральным именем.
        NeuralVoice(
            id = "sima",
            titleRes = R.string.voice_sima,
            descriptionRes = R.string.voice_sima_desc,
            locale = "ru",
            dir = "sima",
            modelFile = "ru_RU-dmitri-medium.onnx",
            approxBytes = 63_201_422L
        )
    )

    fun byId(id: String?): NeuralVoice? = neural.firstOrNull { it.id == id }

    /** Подходит ли встроенный голос для книги на этом языке. */
    fun matchesLanguage(voice: NeuralVoice, language: String): Boolean =
        language.take(2).equals(voice.locale, ignoreCase = true)
}
