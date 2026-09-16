package com.codex.rssreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerPreferences by preferencesDataStore(name = "reader_preferences")

class ReaderPreferences(private val context: Context) {
    private val fontSizeKey = intPreferencesKey("font_size")
    val fontSize: Flow<Int> = context.readerPreferences.data.map { it[fontSizeKey] ?: 18 }

    suspend fun setFontSize(value: Int) {
        context.readerPreferences.edit { it[fontSizeKey] = value.coerceIn(14, 28) }
    }
}
