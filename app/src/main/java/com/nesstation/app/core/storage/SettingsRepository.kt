package com.nesstation.app.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

object SettingsRepository {
    private lateinit var appContext: Context

    fun init(context: Context) { appContext = context.applicationContext }

    private val keyTvMode         = booleanPreferencesKey("tv_mode")
    private val keyRegion         = intPreferencesKey("region") // 0 = NTSC
    private val keySampleRate     = intPreferencesKey("sample_rate")
    private val keyFilter         = intPreferencesKey("video_filter")
    private val keyShowScanlines  = booleanPreferencesKey("show_scanlines")
    private val keyScreenPad      = booleanPreferencesKey("show_screen_pad")
    private val keyFastForwardTap = booleanPreferencesKey("fast_forward_on_tap")
    private val keyLastRomPath    = stringPreferencesKey("last_rom_path")
    private val keyControllerMap  = stringPreferencesKey("controller_map_json")
    private val keyTheme          = stringPreferencesKey("theme") // system/light/dark
    private val keyAudioVolume    = intPreferencesKey("audio_volume") // 0..100

    val tvMode: Flow<Boolean>         = appContext.dataStore.data.map { it[keyTvMode] ?: false }
    val region: Flow<Int>             = appContext.dataStore.data.map { it[keyRegion] ?: 0 }
    val sampleRate: Flow<Int>         = appContext.dataStore.data.map { it[keySampleRate] ?: 44100 }
    val videoFilter: Flow<Int>        = appContext.dataStore.data.map { it[keyFilter] ?: 0 }
    val showScanlines: Flow<Boolean>  = appContext.dataStore.data.map { it[keyShowScanlines] ?: false }
    val showScreenPad: Flow<Boolean>  = appContext.dataStore.data.map { it[keyScreenPad] ?: true }
    val fastForwardOnTap: Flow<Boolean> = appContext.dataStore.data.map { it[keyFastForwardTap] ?: false }
    val lastRomPath: Flow<String?>    = appContext.dataStore.data.map { it[keyLastRomPath] }
    val controllerMapJson: Flow<String?> = appContext.dataStore.data.map { it[keyControllerMap] }
    val theme: Flow<String>           = appContext.dataStore.data.map { it[keyTheme] ?: "system" }
    val audioVolume: Flow<Int>        = appContext.dataStore.data.map { it[keyAudioVolume] ?: 90 }

    suspend fun setTvMode(v: Boolean) = appContext.dataStore.edit { it[keyTvMode] = v }
    suspend fun setRegion(v: Int) = appContext.dataStore.edit { it[keyRegion] = v }
    suspend fun setSampleRate(v: Int) = appContext.dataStore.edit { it[keySampleRate] = v }
    suspend fun setVideoFilter(v: Int) = appContext.dataStore.edit { it[keyFilter] = v }
    suspend fun setShowScanlines(v: Boolean) = appContext.dataStore.edit { it[keyShowScanlines] = v }
    suspend fun setShowScreenPad(v: Boolean) = appContext.dataStore.edit { it[keyScreenPad] = v }
    suspend fun setFastForwardOnTap(v: Boolean) = appContext.dataStore.edit { it[keyFastForwardTap] = v }
    suspend fun setLastRomPath(v: String?) = appContext.dataStore.edit { it[keyLastRomPath] = v }
    suspend fun setControllerMapJson(v: String?) = appContext.dataStore.edit { it[keyControllerMap] = v }
    suspend fun setTheme(v: String) = appContext.dataStore.edit { it[keyTheme] = v }
    suspend fun setAudioVolume(v: Int) = appContext.dataStore.edit { it[keyAudioVolume] = v }
}
