package com.codex.rssreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerPreferences by preferencesDataStore(name = "reader_preferences")

class ReaderPreferences(private val context: Context) {
    private val fontSizeKey = intPreferencesKey("font_size")
    private val lastUpdateCheckAtKey = longPreferencesKey("last_update_check_at")
    val fontSize: Flow<Int> = context.readerPreferences.data.map { it[fontSizeKey] ?: 18 }
    val lastUpdateCheckAt: Flow<Long> = context.readerPreferences.data.map { it[lastUpdateCheckAtKey] ?: 0L }

    suspend fun setFontSize(value: Int) {
        context.readerPreferences.edit { it[fontSizeKey] = value.coerceIn(14, 28) }
    }

    suspend fun setLastUpdateCheckAt(value: Long) {
        context.readerPreferences.edit { it[lastUpdateCheckAtKey] = value.coerceAtLeast(0L) }
    }
}
