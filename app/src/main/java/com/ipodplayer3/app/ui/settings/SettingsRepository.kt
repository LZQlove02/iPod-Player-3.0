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
    private val bassKey = stringPreferencesKey("bass")
    private val trebleKey = stringPreferencesKey("treble")

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

    val bass: Flow<Int> = context.dataStore.data.map { (it[bassKey] ?: "0").toIntOrNull() ?: 0 }

    val treble: Flow<Int> = context.dataStore.data.map { (it[trebleKey] ?: "0").toIntOrNull() ?: 0 }

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

    suspend fun setBass(value: Int) {
        context.dataStore.edit { it[bassKey] = value.coerceIn(-5, 5).toString() }
    }

    suspend fun setTreble(value: Int) {
        context.dataStore.edit { it[trebleKey] = value.coerceIn(-5, 5).toString() }
    }
}
