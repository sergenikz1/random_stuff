package com.voicereader

import com.voicereader.tts.SystemVoiceNames
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemVoiceNamesTest {

    @Test
    fun namesAreHumanStableAndUniquePerLanguage() {
        val voices = listOf(
            "ru-ru-x-ruf-local" to "ru-RU",
            "ru-ru-x-dfc-local" to "ru-RU",
            "en-us-x-sfg-local" to "en-US"
        )
        val names = SystemVoiceNames.assign(voices)
        assertEquals("Саша", names["ru-ru-x-dfc-local"])
        assertEquals("Женя", names["ru-ru-x-ruf-local"])
        assertEquals("Alex", names["en-us-x-sfg-local"])
        // Порядок, в котором движок отдал голоса, на имена не влияет.
        assertEquals(names, SystemVoiceNames.assign(voices.reversed()))
    }

    @Test
    fun manyVoicesGetNumberedNames() {
        val voices = (1..10).map { "ru-voice-%02d".format(it) to "ru" }
        val names = SystemVoiceNames.assign(voices)
        assertEquals(10, names.values.toSet().size)
        assertEquals("Саша 2", names["ru-voice-09"])
    }
}
