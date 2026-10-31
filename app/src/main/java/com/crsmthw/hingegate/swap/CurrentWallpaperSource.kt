package com.crsmthw.hingegate.swap

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.domain.WallpaperChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A capture or a set that failed for a reason the UI and the log can name. */
class SwapException(val failure: SwapFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/** The captured wallpaper: the id the system gave it when it was read. The image is [CurrentWallpaperSource.originalFile]. */
data class Captured(val wallpaperId: Int)

/**
 * Reads the user's current static wallpaper (docs/SWAP-MODE.md §1, §3). Android hands the image only to an app
 * with All files access (`MANAGE_EXTERNAL_STORAGE`); it is used for nothing else. The copy lives in
 * `filesDir/swap/original.img`, private to the app.
 *
 * The caller makes sure the inner screen is the one in use: on Samsung each panel has its own wallpaper, and the
 * system answers for the panel in use.
 */
class CurrentWallpaperSource(context: Context) {

    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)
    private val dir = File(appContext.filesDir, DIR)

    val originalFile: File = File(dir, ORIGINAL)
    private val candidate = File(dir, CANDIDATE)

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /** The system's id for the current home-screen wallpaper, or -1 when it has none (or the system refuses). */
    fun currentId(): Int = try {
        wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    } catch (e: RuntimeException) {
        // A binder call: a server-side exception is rethrown here. No id means "nothing can be concluded".
        Log.w(TAG, "Could not read the wallpaper id", e)
        NO_ID
    }

    /**
     * Copies the current home-screen wallpaper (the CROPPED file, what the launcher shows) into a candidate file,
     * after checking it decodes; [commit] makes it the original, [discard] drops it, so a capture the caller
     * finds out of date never replaces a working original. A live wallpaper has no image: that is the typed
     * failure [SwapFailure.LIVE_WALLPAPER] ("set a static wallpaper first").
     */
    suspend fun capture(): Result<Captured> = withContext(Dispatchers.IO) {
        try {
            if (!isGranted()) throw SwapException(SwapFailure.NO_PERMISSION)
            if (wallpaperManager.wallpaperInfo != null) throw SwapException(SwapFailure.LIVE_WALLPAPER)
            val id = currentId()
            val descriptor = wallpaperManager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                ?: throw SwapException(SwapFailure.LIVE_WALLPAPER)
            dir.mkdirs()
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                candidate.outputStream().use { input.copyTo(it) }
            }
            // Rejects a file that is not an image before it can replace a working original.
            decode(candidate, VERIFY_SIZE_PX, VERIFY_SIZE_PX).recycle()
            Result.success(Captured(id))
        } catch (e: CancellationException) {
            candidate.delete()
            throw e
        } catch (e: SwapException) {
            candidate.delete()
            Result.failure(e)
        } catch (e: IOException) {
            candidate.delete()
            Result.failure(SwapException(SwapFailure.READ_FAILED, e))
        } catch (e: SecurityException) {
            // All files access revoked between the check and the read.
            candidate.delete()
            Result.failure(SwapException(SwapFailure.NO_PERMISSION, e))
        } catch (e: RuntimeException) {
            // Any other server-side exception from the binder calls, rethrown here: the wallpaper is unreadable.
            candidate.delete()
            Result.failure(SwapException(SwapFailure.READ_FAILED, e))
        }
    }

    /** The last [capture] becomes the original. */
    suspend fun commit(): Result<Unit> = withContext(Dispatchers.IO) {
        if (candidate.renameTo(originalFile)) {
            Result.success(Unit)
        } else {
            candidate.delete()
            Result.failure(SwapException(SwapFailure.READ_FAILED))
        }
    }

    /** The last [capture] is dropped; the original stays as it was. */
    suspend fun discard() = withContext(Dispatchers.IO) {
        if (candidate.exists() && !candidate.delete()) Log.w(TAG, "Could not delete the wallpaper candidate")
    }

    /** Forgets the captured image. */
    suspend fun delete() = withContext(Dispatchers.IO) {
        if (originalFile.exists() && !originalFile.delete()) Log.w(TAG, "Could not delete the captured wallpaper")
    }

    /** The captured image decoded to cover [targetW] × [targetH] (sampled, SOFTWARE), or null without one. */
    suspend fun decodeOriginal(targetW: Int, targetH: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!originalFile.exists()) return@withContext null
        try {
            decode(originalFile, targetW, targetH)
        } catch (e: IOException) {
            Log.w(TAG, "Could not decode the captured wallpaper", e)
            null
        }
    }

    private fun decode(file: File, targetW: Int, targetH: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(WallpaperChoice.sampleSize(info.size.width, info.size.height, targetW, targetH))
        }

    private companion object {
        const val TAG = "CurrentWallpaperSource"
        const val DIR = "swap"
        const val ORIGINAL = "original.img"
        const val CANDIDATE = "candidate.img"
        const val VERIFY_SIZE_PX = 64
        const val NO_ID = -1
    }
}
