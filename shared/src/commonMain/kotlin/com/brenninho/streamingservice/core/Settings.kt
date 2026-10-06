package com.brenninho.streamingservice.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Minimal persistent string storage; each platform backs it with its native preferences. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Volatile store for tests and previews. */
class MemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

enum class QualityPreset(val config: CaptureConfig) {
    Low(CaptureConfig(fps = 5, maxWidth = 960, jpegQuality = 0.6f)),
    Medium(CaptureConfig(fps = 10, maxWidth = 1280, jpegQuality = 0.7f)),
    High(CaptureConfig(fps = 15, maxWidth = 1920, jpegQuality = 0.8f)),
}

data class SettingsState(
    val serverUrl: String,
    val displayName: String = "",
    val quality: QualityPreset = QualityPreset.Medium,
    val announceOnDiscord: Boolean = false,
)

/** User settings, loaded from and saved to a [KeyValueStore] on every change. */
class AppSettings(private val store: KeyValueStore, defaultServerUrl: String) {
    private val _state = MutableStateFlow(
        SettingsState(
            serverUrl = store.getString(KEY_SERVER)?.takeIf { it.isNotBlank() } ?: defaultServerUrl,
            displayName = store.getString(KEY_NAME).orEmpty(),
            quality = store.getString(KEY_QUALITY)?.let { saved -> QualityPreset.entries.firstOrNull { it.name == saved } }
                ?: QualityPreset.Medium,
            announceOnDiscord = store.getString(KEY_ANNOUNCE) == "true",
        ),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun update(transform: (SettingsState) -> SettingsState) {
        _state.update(transform)
        val s = _state.value
        store.putString(KEY_SERVER, s.serverUrl)
        store.putString(KEY_NAME, s.displayName)
        store.putString(KEY_QUALITY, s.quality.name)
        store.putString(KEY_ANNOUNCE, s.announceOnDiscord.toString())
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_NAME = "display_name"
        const val KEY_QUALITY = "quality"
        const val KEY_ANNOUNCE = "announce_discord"
    }
}
