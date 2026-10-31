package com.crsmthw.hingegate.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.PostureMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

// Top-level delegate: DataStore must be a single instance per file, and the delegate guarantees that.
val Context.hingegateStore: DataStore<Preferences> by preferencesDataStore(name = "hingegate")

/**
 * Settings, the miss streak, the posture memory and the active crack — one Preferences file shared with
 * [CrackLog] and [WallpaperImages]. Reads are flows (or one-shot loads) with defaults applied here; every write
 * returns a [Result], so a full disk is reported to the caller instead of crashing a binder thread.
 */
class SettingsRepository(private val store: DataStore<Preferences>, private val json: Json) {

    /**
     * An unreadable file (an IOException, which includes DataStore's CorruptionException) reads as "nothing
     * stored", so the app starts on defaults instead of crashing; anything else is a bug and is rethrown.
     */
    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Distinct, because DataStore re-emits the whole file on an edit to ANY key (the log, the posture memory). */
    val settings: Flow<Settings> = prefs.map { p ->
        Settings(
            enabled = p[Keys.ENABLED] ?: true,
            // An unknown stored name (a mode removed in a later version) falls back to the default.
            mode = p[Keys.MODE]?.let { raw -> CrackMode.entries.firstOrNull { it.name == raw } } ?: CrackMode.OVERLAY,
            chancePercent = Settings.clampChance(p[Keys.CHANCE] ?: Settings.CHANCE_DEFAULT),
            guaranteeAfter = Settings.clampGuarantee(p[Keys.GUARANTEE] ?: Settings.GUARANTEE_DEFAULT),
            crackNextUnfold = p[Keys.CRACK_NEXT] ?: false,
        )
    }.distinctUntilChanged()

    val missStreak: Flow<Int> = prefs.map { it[Keys.MISS_STREAK] ?: 0 }.distinctUntilChanged()

    suspend fun loadPostureMemory(): PostureMemory =
        decode(prefs.first()[Keys.POSTURE_MEMORY], PostureMemory.serializer()) ?: PostureMemory()

    suspend fun loadActiveCrack(): ActiveCrack? = decode(prefs.first()[Keys.ACTIVE_CRACK], ActiveCrack.serializer())

    // ── Writes ──────────────────────────────────────────────────────────────

    suspend fun setEnabled(enabled: Boolean): Result<Unit> = write { it[Keys.ENABLED] = enabled }

    suspend fun setMode(mode: CrackMode): Result<Unit> = write { it[Keys.MODE] = mode.name }

    suspend fun setChancePercent(percent: Int): Result<Unit> = write { it[Keys.CHANCE] = Settings.clampChance(percent) }

    suspend fun setGuaranteeAfter(count: Int): Result<Unit> =
        write { it[Keys.GUARANTEE] = Settings.clampGuarantee(count) }

    suspend fun setCrackNextUnfold(enabled: Boolean): Result<Unit> = write { it[Keys.CRACK_NEXT] = enabled }

    suspend fun savePostureMemory(memory: PostureMemory): Result<Unit> =
        write { it[Keys.POSTURE_MEMORY] = json.encodeToString(PostureMemory.serializer(), memory) }

    suspend fun saveActiveCrack(active: ActiveCrack?): Result<Unit> = write { it.putActive(active) }

    /**
     * One unfold's outcome in a single edit, so the streak, the consumed "next unfold" switch and the crack can
     * never be half-written if the process dies in between.
     */
    suspend fun recordUnfold(newMissStreak: Int, consumeCrackNext: Boolean, active: ActiveCrack?): Result<Unit> =
        write {
            it[Keys.MISS_STREAK] = newMissStreak
            if (consumeCrackNext) it[Keys.CRACK_NEXT] = false
            it.putActive(active)
        }

    private fun MutablePreferences.putActive(active: ActiveCrack?) {
        if (active == null) {
            remove(Keys.ACTIVE_CRACK)
        } else {
            this[Keys.ACTIVE_CRACK] = json.encodeToString(ActiveCrack.serializer(), active)
        }
    }

    private suspend fun write(block: (MutablePreferences) -> Unit): Result<Unit> =
        storeResult { store.edit { block(it) } }.map { }

    /** A stored value from an older, incompatible format reads as absent rather than crashing the start-up. */
    private fun <T> decode(raw: String?, serializer: KSerializer<T>): T? {
        raw ?: return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            Log.w(TAG, "Discarding unreadable ${serializer.descriptor.serialName}", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discarding unreadable ${serializer.descriptor.serialName}", e)
            null
        }
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val MODE = stringPreferencesKey("mode")
        val CHANCE = intPreferencesKey("chance_percent")
        val GUARANTEE = intPreferencesKey("guarantee_after")
        val CRACK_NEXT = booleanPreferencesKey("crack_next_unfold")
        val MISS_STREAK = intPreferencesKey("miss_streak")
        val POSTURE_MEMORY = stringPreferencesKey("posture_memory")
        val ACTIVE_CRACK = stringPreferencesKey("active_crack")
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}

/**
 * Runs a storage operation and reports its failure as a [Result]. Only I/O failures are caught: a cancellation
 * must keep propagating (runCatching would swallow it), and any other exception is a bug.
 */
internal suspend fun <T> storeResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    Result.failure(e)
}
