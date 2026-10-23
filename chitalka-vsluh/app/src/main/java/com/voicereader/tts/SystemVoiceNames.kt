package com.voicereader.tts

/**
 * Человеческие имена для голосов системного движка вместо кодов вроде «ru-ru-x-ruf-local».
 * Пол голоса Android не сообщает, поэтому имена нейтральные. Имена раздаются по алфавиту
 * технических названий внутри языка — пока набор голосов тот же, у голоса то же имя.
 */
object SystemVoiceNames {

    private val RUSSIAN = listOf("Саша", "Женя", "Валя", "Слава", "Шура", "Ника", "Мика", "Тоша")
    private val OTHER = listOf("Alex", "Sam", "Robin", "Casey", "Jordan", "Taylor", "Morgan", "Riley")

    /** name системного голоса → имя. [voices] — пары (name, языковой тег). */
    fun assign(voices: List<Pair<String, String>>): Map<String, String> {
        val result = HashMap<String, String>()
        voices.groupBy { it.second.take(2).lowercase() }.forEach { (language, group) ->
            val pool = if (language == "ru") RUSSIAN else OTHER
            group.map { it.first }.distinct().sorted().forEachIndexed { i, name ->
                val base = pool[i % pool.size]
                result[name] = if (i < pool.size) base else "$base ${i / pool.size + 1}"
            }
        }
        return result
    }
}
