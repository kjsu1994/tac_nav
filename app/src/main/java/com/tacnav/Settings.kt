package com.tacnav

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsData by preferencesDataStore("settings")
enum class ThemeMode(val label: String) { SYSTEM("시스템"), LIGHT("주간"), DARK("야간") }
data class AppSettings(
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DD,
    val mgrsDigits: Int = 5,
    val showMils: Boolean = true,
    val northReference: NorthReference = NorthReference.TRUE,
    val theme: ThemeMode = ThemeMode.DARK,
    val trackIntervalSeconds: Int = 5,
    val showGrid: Boolean = true,
    val keepScreenOn: Boolean = false,
    val activeMap: String = BUILT_IN_MAP_ID
)
class SettingsStore(context: Context) {
    private val store = context.settingsData
    private fun <T : Enum<T>> enumValue(values: Array<T>, value: String?, default: T) = values.firstOrNull { it.name == value } ?: default
    private fun decode(p: Preferences): AppSettings = AppSettings(
            enumValue(CoordinateFormat.entries.toTypedArray(), p[stringPreferencesKey("format")], CoordinateFormat.DD),
            (p[intPreferencesKey("digits")] ?: 5).coerceIn(1, 5), p[booleanPreferencesKey("mils")] ?: true,
            enumValue(NorthReference.entries.toTypedArray(), p[stringPreferencesKey("north")], NorthReference.TRUE),
            enumValue(ThemeMode.entries.toTypedArray(), p[stringPreferencesKey("theme")], ThemeMode.DARK),
            (p[intPreferencesKey("interval")] ?: 5).takeIf { it in listOf(1, 5, 10, 30) } ?: 5,
            p[booleanPreferencesKey("grid")] ?: true, p[booleanPreferencesKey("screen")] ?: false,
            p[stringPreferencesKey("map")] ?: BUILT_IN_MAP_ID
        )
    val flow = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map(::decode)
    suspend fun save(s: AppSettings) { store.edit { write(it,s) } }
    suspend fun update(change: (AppSettings)->AppSettings) { store.edit { write(it,change(decode(it))) } }
    private fun write(p: MutablePreferences,s: AppSettings) {
        p[stringPreferencesKey("format")] = s.coordinateFormat.name
        p[intPreferencesKey("digits")] = s.mgrsDigits.coerceIn(1, 5)
        p[booleanPreferencesKey("mils")] = s.showMils
        p[stringPreferencesKey("north")] = s.northReference.name
        p[stringPreferencesKey("theme")] = s.theme.name
        p[intPreferencesKey("interval")] = s.trackIntervalSeconds
        p[booleanPreferencesKey("grid")] = s.showGrid
        p[booleanPreferencesKey("screen")] = s.keepScreenOn
        p[stringPreferencesKey("map")] = s.activeMap
    }
}
