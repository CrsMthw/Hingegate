package com.crsmthw.hingegate.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.crsmthw.hingegate.domain.SwapState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The Real wallpaper mode's state (docs/SWAP-MODE.md §3): the capture, the inner panel's frame, what is staged and
 * the ids of Hingegate's own sets, as one JSON value in the shared Preferences file — one edit per change, so a
 * set and the id it returned are never recorded half.
 */
class SwapRepository(private val store: DataStore<Preferences>, private val json: Json) {

    val state: Flow<SwapState> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[KEY]) }
        .distinctUntilChanged()

    suspend fun load(): SwapState = state.first()

    suspend fun save(state: SwapState): Result<Unit> =
        storeResult { store.edit { it[KEY] = json.encodeToString(SwapState.serializer(), state) } }.map { }

    /** A state from an incompatible older format reads as "nothing captured": the user captures again. */
    private fun decode(raw: String?): SwapState {
        raw ?: return SwapState()
        return try {
            json.decodeFromString(SwapState.serializer(), raw)
        } catch (e: SerializationException) {
            Log.w(TAG, "Discarding unreadable swap state", e)
            SwapState()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discarding unreadable swap state", e)
            SwapState()
        }
    }

    private companion object {
        const val TAG = "SwapRepository"
        val KEY = stringPreferencesKey("swap_state")
    }
}
