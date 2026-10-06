package com.brenninho.streamingservice

import com.brenninho.streamingservice.core.AppSettings
import com.brenninho.streamingservice.core.MemoryKeyValueStore
import com.brenninho.streamingservice.core.QualityPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun startsWithDefaults() {
        val settings = AppSettings(MemoryKeyValueStore(), "localhost:8080")
        assertEquals("localhost:8080", settings.state.value.serverUrl)
        assertEquals(QualityPreset.Medium, settings.state.value.quality)
        assertEquals("", settings.state.value.displayName)
        assertTrue(!settings.state.value.announceOnDiscord)
    }

    @Test
    fun changesSurviveARestart() {
        val store = MemoryKeyValueStore()
        AppSettings(store, "localhost:8080").update {
            it.copy(serverUrl = "stream.example.com", displayName = "Ana", quality = QualityPreset.High, announceOnDiscord = true)
        }

        val reloaded = AppSettings(store, "localhost:8080").state.value
        assertEquals("stream.example.com", reloaded.serverUrl)
        assertEquals("Ana", reloaded.displayName)
        assertEquals(QualityPreset.High, reloaded.quality)
        assertTrue(reloaded.announceOnDiscord)
    }

    @Test
    fun blankSavedServerFallsBackToTheDefault() {
        val store = MemoryKeyValueStore().apply { putString("server_url", "   ") }
        assertEquals("localhost:8080", AppSettings(store, "localhost:8080").state.value.serverUrl)
    }
}
