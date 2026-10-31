package com.crsmthw.hingegate.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.crsmthw.hingegate.domain.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The detection log (docs/DESIGN.md §9.3): the newest [LogEntry.CAPACITY] entries, newest first, as one JSON
 * string in the shared Preferences file. Small enough that rewriting it on every append costs nothing.
 */
class CrackLog(private val store: DataStore<Preferences>, private val json: Json) {

    private val serializer = ListSerializer(LogEntry.serializer())

    val entries: Flow<List<LogEntry>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[KEY]) }
        .distinctUntilChanged()

    suspend fun append(entry: LogEntry): Result<Unit> = storeResult {
        store.edit { prefs ->
            val updated = (listOf(entry) + decode(prefs[KEY])).take(LogEntry.CAPACITY)
            prefs[KEY] = json.encodeToString(serializer, updated)
        }
    }.map { }

    suspend fun clear(): Result<Unit> = storeResult { store.edit { it.remove(KEY) } }.map { }

    /** A log from an incompatible older format is dropped rather than kept half-read. */
    private fun decode(raw: String?): List<LogEntry> {
        raw ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            Log.w(TAG, "Discarding unreadable log", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discarding unreadable log", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "CrackLog"
        val KEY = stringPreferencesKey("detection_log")
    }
}
