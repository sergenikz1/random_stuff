package com.voicereader.tts

/** События синтеза, на которые реагируют плеер и интерфейс. */
interface SpeechListener {
    /** Началось произнесение предложения с этим индексом. */
    fun onSentenceStart(index: Int)
    /** Границы произносимого сейчас слова внутри предложения. */
    fun onWordRange(index: Int, range: IntRange)
    /** Текст закончился. */
    fun onFinished()
    /** Готовится к речи: загружается модель или синтезируется первый фрагмент. */
    fun onPreparing(preparing: Boolean)
    fun onError(message: String)
}

/**
 * Источник речи. Реализации сами решают, как буферизовать:
 * системный движок использует собственную очередь, нейросетевой — синтез на шаг вперёд.
 */
interface SpeechBackend {
    /** Поддерживает ли настройку высоты тона (нейросетевые модели — нет). */
    val supportsPitch: Boolean

    fun start(sentences: List<String>, fromIndex: Int)
    fun stop()
    fun release()
}
