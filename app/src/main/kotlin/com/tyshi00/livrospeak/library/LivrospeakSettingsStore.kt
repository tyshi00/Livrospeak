package com.tyshi00.livrospeak.library

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val SETTINGS_FILE_NAME = "livrospeak_settings.json"

data class LivrospeakSettings(
    val invertColors: Boolean = false,
    val showLibbyBooks: Boolean = true,
    val showLocalBooks: Boolean = true,
    val showRssBooks: Boolean = true,
    // Off by default: this gates BOTH whether ListeningSessionStore records anything
    // and whether the Listening Stats entry point shows up on the shelf -- a user who
    // never turns it on should see zero behavior change and zero new data collected.
    val listeningStatsEnabled: Boolean = false,
    // Scoped entirely to the Listening Stats screen's own charts -- unrelated to
    // invertColors/LightThemeController, which governs the rest of the app's
    // monochrome light/dark theme. Off by default to match the LightOS convention of
    // a monochrome default with color as an opt-in.
    val listeningStatsColorful: Boolean = false,
    // Off by default: a consecutive-day streak is an extra, opinionated stat (not part
    // of the original ask) that some people find motivating and others find stressful,
    // so it's opt-in rather than always shown.
    val listeningStreakEnabled: Boolean = false,
)

/**
 * Durable, app-wide settings: the Invert Colors theme toggle and the home screen
 * source toggles. Persisted as a small JSON file in [Context.filesDir] -- this is
 * intentionally its own flat file rather than a SharedPreferences bag, since it's a
 * handful of booleans that are easiest to reason about (and hand-edit/back up) as one
 * plain settings document.
 */
object LivrospeakSettingsStore {
    private lateinit var appContext: Context
    private val mutableSettings = MutableStateFlow(LivrospeakSettings())
    val settings: StateFlow<LivrospeakSettings> = mutableSettings.asStateFlow()

    /** Must be called once during app startup, before [current] or [settings] are read. */
    fun init(context: Context) {
        appContext = context.applicationContext
        mutableSettings.value = readFromDisk()
    }

    val current: LivrospeakSettings
        get() = mutableSettings.value

    fun setInvertColors(enabled: Boolean) = update { it.copy(invertColors = enabled) }
    fun setShowLibbyBooks(enabled: Boolean) = update { it.copy(showLibbyBooks = enabled) }
    fun setShowLocalBooks(enabled: Boolean) = update { it.copy(showLocalBooks = enabled) }
    fun setShowRssBooks(enabled: Boolean) = update { it.copy(showRssBooks = enabled) }
    fun setListeningStatsEnabled(enabled: Boolean) = update { it.copy(listeningStatsEnabled = enabled) }
    fun setListeningStatsColorful(enabled: Boolean) = update { it.copy(listeningStatsColorful = enabled) }
    fun setListeningStreakEnabled(enabled: Boolean) = update { it.copy(listeningStreakEnabled = enabled) }

    private fun update(transform: (LivrospeakSettings) -> LivrospeakSettings) {
        val updated = transform(mutableSettings.value)
        if (updated == mutableSettings.value) return
        mutableSettings.value = updated
        writeToDisk(updated)
    }

    private fun settingsFile(): File = File(appContext.filesDir, SETTINGS_FILE_NAME)

    private fun readFromDisk(): LivrospeakSettings {
        val file = settingsFile()
        if (!file.exists()) return LivrospeakSettings()
        return try {
            val json = JSONObject(file.readText())
            LivrospeakSettings(
                invertColors = json.optBoolean("invertColors", false),
                showLibbyBooks = json.optBoolean("showLibbyBooks", true),
                showLocalBooks = json.optBoolean("showLocalBooks", true),
                showRssBooks = json.optBoolean("showRssBooks", true),
                listeningStatsEnabled = json.optBoolean("listeningStatsEnabled", false),
                listeningStatsColorful = json.optBoolean("listeningStatsColorful", false),
                listeningStreakEnabled = json.optBoolean("listeningStreakEnabled", false),
            )
        } catch (_: Exception) {
            LivrospeakSettings()
        }
    }

    private fun writeToDisk(settings: LivrospeakSettings) {
        val json = JSONObject()
            .put("invertColors", settings.invertColors)
            .put("showLibbyBooks", settings.showLibbyBooks)
            .put("showLocalBooks", settings.showLocalBooks)
            .put("showRssBooks", settings.showRssBooks)
            .put("listeningStatsEnabled", settings.listeningStatsEnabled)
            .put("listeningStatsColorful", settings.listeningStatsColorful)
            .put("listeningStreakEnabled", settings.listeningStreakEnabled)
        runCatching { settingsFile().writeText(json.toString()) }
    }
}
