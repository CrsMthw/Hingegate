package com.crsmthw.hingegate.swap

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.domain.SwapFlags
import com.crsmthw.hingegate.domain.SwapFrame
import com.crsmthw.hingegate.domain.SwapPrepared
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
 * The inner screen's wallpaper id, read with the panel bit ([inner], what every decision uses) and — on Samsung,
 * for the diagnostics only — without it ([plain], answered for whichever panel the server thinks is in use).
 */
data class IdReadout(val inner: Int, val plain: Int?)

/**
 * Reads the user's current static wallpaper (docs/SWAP-MODE.md §1, §3). Android hands the image only to an app
 * with All files access (`MANAGE_EXTERNAL_STORAGE`); it is used for nothing else. The copy lives in
 * `filesDir/swap/original.img`, private to the app.
 *
 * Which panel: on Samsung every read passes [readWhich] = `FLAG_SYSTEM | FLAG_DISPLAY_PHONE`, which One UI answers
 * for the INNER panel whatever the fold state (proven from the phones' own services.jar, SWAP-MODE §1); a read
 * without the bit is answered for the panel the server THINKS is in use, which lags or leads the app at a fold.
 * Elsewhere [readWhich] is plain `FLAG_SYSTEM` (one wallpaper for both panels; AOSP throws for any other value).
 * The caller still only acts on a read in a settled inner state (§10.24).
 */
class CurrentWallpaperSource(context: Context, private val isSamsung: Boolean) {

    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)
    private val dir = File(appContext.filesDir, DIR)

    /**
     * Computed, never a literal: the panel bit is not one of the `@SetWallpaperFlags` values, so lint must not
     * see it as one (docs/SWAP-MODE.md §1).
     */
    val readWhich: Int = SwapFlags.which(isSamsung, WallpaperManager.FLAG_SYSTEM)

    val originalFile: File = File(dir, ORIGINAL)
    private val candidate = File(dir, CANDIDATE)

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /** The inner screen's home-wallpaper id, or -1 when the system has none (or refuses). */
    fun currentId(): Int = readId(readWhich)

    /** [currentId], plus the panel-less read on Samsung for the log (both forms, while this is new). */
    fun readIds(): IdReadout = IdReadout(
        inner = currentId(),
        plain = if (isSamsung) readId(WallpaperManager.FLAG_SYSTEM) else null,
    )

    private fun readId(which: Int): Int = try {
        wallpaperManager.getWallpaperId(which)
    } catch (e: RuntimeException) {
        // A binder call: a server-side exception is rethrown here. No id means "nothing can be concluded" — which
        // would silently disable every check, so it is logged loudly.
        Log.w(TAG, "Could not read the wallpaper id with which=$which", e)
        NO_ID
    }

    /**
     * Whether the inner screen's home wallpaper is a live one. `getWallpaperInfo(which)` (API 34) names the panel;
     * before that only the panel-less `wallpaperInfo` exists, answered for the panel in use (the caller reads it
     * in a settled inner state). A live wallpaper whose package Hingegate cannot see reads as null: the manifest
     * declares the wallpaper-service query so third-party ones are visible.
     */
    fun isLive(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wallpaperManager.getWallpaperInfo(readWhich) != null
        } else {
            wallpaperManager.wallpaperInfo != null
        }

    /**
     * Copies the current home-screen wallpaper (the CROPPED file, what the launcher shows) into a candidate file,
     * after checking it decodes; [commit] makes it the original, [discard] drops it, so a capture the caller
     * finds out of date never replaces a working original. A live wallpaper has no image: that is the typed
     * failure [SwapFailure.LIVE_WALLPAPER] ("set a static wallpaper first"). The live check comes FIRST: for
     * Samsung's own live wallpapers One UI returns a thumbnail as the "file" (§1), which must never be captured.
     */
    suspend fun capture(): Result<Captured> = withContext(Dispatchers.IO) {
        try {
            if (!isGranted()) throw SwapException(SwapFailure.NO_PERMISSION)
            if (isLive()) throw SwapException(SwapFailure.LIVE_WALLPAPER)
            val id = currentId()
            val descriptor = wallpaperManager.getWallpaperFile(readWhich)
                ?: throw SwapException(SwapFailure.LIVE_WALLPAPER)
            dir.mkdirs()
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                candidate.outputStream().use { input.copyTo(it) }
            }
            // Rejects a file that is not an image before it can replace a working original.
            decode(candidate, VERIFY_SIZE_PX, VERIFY_SIZE_PX).recycle()
            Log.i(TAG, "read: which=$readWhich id=$id bytes=${candidate.length()}")
            Result.success(Captured(id))
        } catch (e: CancellationException) {
            candidate.delete()
            throw e
        } catch (e: SwapException) {
            candidate.delete()
            Log.i(TAG, "read refused: which=$readWhich ${e.failure}")
            Result.failure(e)
        } catch (e: IOException) {
            candidate.delete()
            Log.w(TAG, "read failed: which=$readWhich", e)
            Result.failure(SwapException(SwapFailure.READ_FAILED, e))
        } catch (e: SecurityException) {
            // All files access revoked between the check and the read.
            candidate.delete()
            Log.w(TAG, "read refused by the system: which=$readWhich", e)
            Result.failure(SwapException(SwapFailure.NO_PERMISSION, e))
        } catch (e: RuntimeException) {
            // Any other server-side exception from the binder calls, rethrown here: the wallpaper is unreadable.
            candidate.delete()
            Log.w(TAG, "read failed: which=$readWhich", e)
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

    /** The original file's length and modification time (what a recapture changes), or null without one. */
    fun fingerprint(): Pair<Long, Long>? =
        originalFile.takeIf { it.isFile }?.let { it.length() to it.lastModified() }

    /**
     * Whether the original's own bytes are already the clean copy for [frame] ([SwapPrepared.originalServesAsClean]
     * decides from the file's format and size, read here without decoding the pixels).
     */
    suspend fun originalMatches(frame: SwapFrame): Boolean = withContext(Dispatchers.IO) {
        if (!originalFile.isFile) return@withContext false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originalFile.path, bounds)
        SwapPrepared.originalServesAsClean(bounds.outMimeType, bounds.outWidth, bounds.outHeight, frame)
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
        /** The Real wallpaper mode's diagnostics tag: a failed read must show in Cris's filtered logcat. */
        const val TAG = "HingegateSwap"
        const val DIR = "swap"
        const val ORIGINAL = "original.img"
        const val CANDIDATE = "candidate.img"
        const val VERIFY_SIZE_PX = 64
        const val NO_ID = -1
    }
}
