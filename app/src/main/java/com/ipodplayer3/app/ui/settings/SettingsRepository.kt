package com.ipodplayer3.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ipod_settings")

enum class AppLanguage { ZH, EN }
enum class EqPreset { FLAT, POP, ROCK, CLASSICAL, JAZZ, BASS }

class SettingsRepository(private val context: Context) {

    private val langKey = stringPreferencesKey("language")
    private val themeKey = stringPreferencesKey("theme")
    private val soundKey = booleanPreferencesKey("click_sound")
    private val vibrateKey = booleanPreferencesKey("vibrate")
    private val eqEnabledKey = booleanPreferencesKey("eq_enabled")
    private val eqPresetKey = stringPreferencesKey("eq_preset")
    private val eqBandsKey = stringPreferencesKey("eq_bands")
    private val transitionKey = stringPreferencesKey("transition")
    private val lyricsDirKey = stringPreferencesKey("lyrics_dir")
    private val shuffleKey = booleanPreferencesKey("shuffle")
    private val repeatKey = stringPreferencesKey("repeat")

    /** Persisted SAF tree URI for the user-picked lyrics folder, or empty. */
    val lyricsDirUri: Flow<String> = context.dataStore.data.map { it[lyricsDirKey] ?: "" }

    suspend fun setLyricsDirUri(uri: String) {
        context.dataStore.edit { it[lyricsDirKey] = uri }
    }

    /** A = fade, B = slide (default), C = container transform */
    val transitionMode: Flow<String> = context.dataStore.data.map { it[transitionKey] ?: "B" }

    suspend fun setTransitionMode(mode: String) {
        context.dataStore.edit { it[transitionKey] = mode }
    }

    val language: Flow<AppLanguage> = context.dataStore.data.map {
        when (it[langKey]) {
            AppLanguage.EN.name -> AppLanguage.EN
            else -> AppLanguage.ZH
        }
    }

    val themeName: Flow<String> = context.dataStore.data.map { it[themeKey] ?: "silver" }

    val clickSound: Flow<Boolean> = context.dataStore.data.map { it[soundKey] ?: true }

    val vibrate: Flow<Boolean> = context.dataStore.data.map { it[vibrateKey] ?: true }

    val eqEnabled: Flow<Boolean> = context.dataStore.data.map { it[eqEnabledKey] ?: false }

    val eqPreset: Flow<EqPreset> = context.dataStore.data.map {
        runCatching { EqPreset.valueOf(it[eqPresetKey] ?: "FLAT") }.getOrDefault(EqPreset.FLAT)
    }

    /** 10 段增益，逗号分隔（如 "3.0,1.0,0.0,..."）；空串表示从未保存过曲线。 */
    val eqBands: Flow<String> = context.dataStore.data.map { it[eqBandsKey] ?: "" }

    suspend fun setLanguage(lang: AppLanguage) {
        context.dataStore.edit { it[langKey] = lang.name }
    }

    suspend fun setTheme(name: String) {
        context.dataStore.edit { it[themeKey] = name }
    }

    suspend fun setClickSound(enabled: Boolean) {
        context.dataStore.edit { it[soundKey] = enabled }
    }

    suspend fun setVibrate(enabled: Boolean) {
        context.dataStore.edit { it[vibrateKey] = enabled }
    }

    suspend fun setEqEnabled(enabled: Boolean) {
        context.dataStore.edit { it[eqEnabledKey] = enabled }
    }

    suspend fun setEqPreset(preset: EqPreset) {
        context.dataStore.edit { it[eqPresetKey] = preset.name }
    }

    /** 保存 10 段增益曲线（Float.toString 与地区无关，可直接落盘）。 */
    suspend fun setEqBands(gains: List<Float>) {
        context.dataStore.edit { it[eqBandsKey] = gains.joinToString(",") }
    }

    val shuffle: Flow<Boolean> = context.dataStore.data.map { it[shuffleKey] ?: false }

    suspend fun setShuffle(enabled: Boolean) {
        context.dataStore.edit { it[shuffleKey] = enabled }
    }

    /** "OFF" / "ONE" / "ALL"，与 PlayerController.RepeatMode.name 对齐。 */
    val repeatMode: Flow<String> = context.dataStore.data.map { it[repeatKey] ?: "OFF" }

    suspend fun setRepeatMode(name: String) {
        context.dataStore.edit { it[repeatKey] = name }
    }
}
