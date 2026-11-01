package com.crsmthw.hingegate.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.crsmthw.hingegate.domain.ImageSlot
import com.crsmthw.hingegate.domain.WallpaperChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The wallpaper mode's two images (docs/DESIGN.md §12.3). The picked content is COPIED into `filesDir`, so the
 * wallpaper keeps working after the picker's temporary read grant expires and needs no storage permission. A
 * per-slot version in DataStore tells every engine and the UI that an image changed.
 */
class WallpaperImages(context: Context, private val store: DataStore<Preferences>) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)

    /** Slots with an image, mapped to their version; an absent slot has no image. */
    val versions: Flow<Map<ImageSlot, Long>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> ImageSlot.entries.mapNotNull { slot -> prefs[keyOf(slot)]?.let { slot to it } }.toMap() }
        .distinctUntilChanged()

    /** Copies [uri] into the slot (temp file + rename), checks it decodes, then bumps the slot's version. */
    suspend fun import(slot: ImageSlot, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val temp = File(dir, "${slot.fileStem}.tmp")
        try {
            dir.mkdirs()
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("No stream for the picked image")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            // Rejects a file that is not an image before it replaces a working one.
            decodeFile(temp, VERIFY_SIZE_PX, VERIFY_SIZE_PX).recycle()
            if (!temp.renameTo(fileOf(slot))) throw IOException("Could not move the image into place")
            store.edit { it[keyOf(slot)] = System.currentTimeMillis() }
            Result.success(Unit)
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: IOException) {
            temp.delete()
            Result.failure(e)
        } catch (e: SecurityException) {
            // The picker's grant was revoked before the copy finished.
            temp.delete()
            Result.failure(e)
        }
    }

    suspend fun clear(slot: ImageSlot): Result<Unit> = withContext(Dispatchers.IO) {
        storeResult {
            store.edit { it.remove(keyOf(slot)) }
            fileOf(slot).delete()
        }.map { }
    }

    /**
     * The slot's image decoded for a [targetW] × [targetH] surface: the smallest sampled size still covering it,
     * EXIF orientation applied by `ImageDecoder`, a SOFTWARE bitmap (drawn onto a hardware canvas once per frame,
     * and recyclable). Null when there is no image or it no longer decodes.
     */
    suspend fun decode(slot: ImageSlot, targetW: Int, targetH: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileOf(slot)
        if (!file.exists()) return@withContext null
        try {
            decodeFile(file, targetW, targetH)
        } catch (e: IOException) {
            Log.w(TAG, "Could not decode the $slot image", e)
            null
        }
    }

    private fun decodeFile(file: File, targetW: Int, targetH: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(WallpaperChoice.sampleSize(info.size.width, info.size.height, targetW, targetH))
        }

    private fun fileOf(slot: ImageSlot) = File(dir, "${slot.fileStem}.img")

    private fun keyOf(slot: ImageSlot) = when (slot) {
        ImageSlot.MAIN -> KEY_MAIN
        ImageSlot.COVER -> KEY_COVER
    }

    private val ImageSlot.fileStem: String
        get() = name.lowercase()

    private companion object {
        const val TAG = "WallpaperImages"
        const val DIR = "wallpaper"
        const val VERIFY_SIZE_PX = 64
        val KEY_MAIN = longPreferencesKey("image_version_main")
        val KEY_COVER = longPreferencesKey("image_version_cover")
    }
}
