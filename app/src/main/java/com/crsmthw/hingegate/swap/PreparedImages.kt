package com.crsmthw.hingegate.swap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import com.crsmthw.hingegate.domain.PreparedImage
import com.crsmthw.hingegate.domain.PreparedSource
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.domain.SwapFrame
import com.crsmthw.hingegate.domain.SwapPrepared
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** How long a prepare took, and how big the file is, for the log. */
data class PrepareTiming(val file: File, val renderMs: Long, val encodeMs: Long, val bytes: Long)

/**
 * The Real wallpaper mode's prepared images on disk (docs/SWAP-MODE.md §10.28): `filesDir/swap/prepared/<name>`,
 * named by [SwapPrepared] after everything their pixels depend on. Each is written to a temporary file and renamed,
 * so a name either does not exist or holds the whole image; the original is fingerprinted before the render and
 * checked after it, so a recapture during a render never leaves a file named for the old capture with the new
 * one's pixels. They are copies of the user's wallpaper, private to the app, and deleted with the capture.
 *
 * Owns its own [renderer]: a prepare can run while the swapper renders a fallback image. Its renders run one at a
 * time ([rendering]): a prepare that was cancelled because the plan changed only stops at its next suspension point,
 * so its render (canvas work does not suspend) can still be running when the next prepare starts drawing with the
 * same, not thread-safe, renderer.
 */
class PreparedImages(
    context: Context,
    private val source: CurrentWallpaperSource,
    private val renderer: SwapRenderer,
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)
    private val rendering = Mutex()

    /**
     * The install this process runs: the APK file is new on every install, also when the version code stays the
     * same, so a renderer changed by a new build never meets an image the old one prepared.
     */
    private val stamp: Long by lazy { File(appContext.applicationInfo.sourceDir).lastModified() }

    /** The key part for the current original, or null when there is none on disk. */
    fun sourceFor(captureId: Int): PreparedSource? =
        source.fingerprint()?.let { (length, modified) -> PreparedSource(stamp, captureId, length, modified) }

    /** The finished file of [name], or null. */
    fun existing(name: String): File? = File(dir, name).takeIf { it.isFile }

    /**
     * Renders [image] for [frame] from the original [key] describes and encodes it (PNG, lossless) under its name.
     * Fails, leaving nothing, if the original changed meanwhile.
     */
    suspend fun prepare(image: PreparedImage, key: PreparedSource, frame: SwapFrame): Result<PrepareTiming> =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val bitmap = try {
                rendering.withLock {
                    // A prepare cancelled while it waited for the renderer does not draw at all.
                    ensureActive()
                    renderer.render(frame, image.spec, image.anchor)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Result.failure(e)
            }.getOrElse { return@withContext Result.failure(it) }
            val rendered = SystemClock.elapsedRealtime()
            val target = File(dir, image.name)
            val temp = File(dir, SwapPrepared.tempName(image.name, Random.nextLong()))
            try {
                // Nor does one cancelled during its render spend seconds encoding it.
                ensureActive()
                dir.mkdirs()
                val written = temp.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                if (!written) throw IOException("PNG encoder refused ${image.name}")
                val encoded = SystemClock.elapsedRealtime()
                // The render read the original the key names, or the result is thrown away.
                if (source.fingerprint() != key.originalLength to key.originalModifiedMs) {
                    throw SwapException(SwapFailure.CHANGED_DURING_READ)
                }
                if (!temp.renameTo(target)) throw IOException("Could not move ${image.name} into place")
                Result.success(PrepareTiming(target, rendered - started, encoded - rendered, target.length()))
            } catch (e: IOException) {
                temp.delete()
                Result.failure(e)
            } catch (e: SwapException) {
                temp.delete()
                Result.failure(e)
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * Deletes every file not in [keep] (a temporary file counts by the name it is for; one left behind by a killed
     * process goes once it is old). Everything in the folder is Hingegate's own.
     */
    suspend fun keepOnly(keep: Set<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (SwapPrepared.shouldDelete(file.name, keep, now - file.lastModified()) && !file.delete()) {
                Log.w(TAG, "Could not delete ${file.name}")
            }
        }
    }

    suspend fun deleteAll() = keepOnly(emptySet())

    /** A prepared (or original) file decoded at full size, for a set that has to fall back to `setBitmap`. */
    suspend fun decodeFull(file: File): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not decode ${file.name}", e)
            null
        }
    }

    private companion object {
        const val TAG = "HingegateSwap"
        const val DIR = "swap/prepared"

        /** Ignored by the PNG encoder (lossless), stated for clarity. */
        const val PNG_QUALITY = 100
    }
}
