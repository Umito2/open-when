package com.openwhen.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Re-encoding removes the original file's EXIF/GPS metadata and bounds Firestore payload size. */
class SparkPhotoCodec @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun prepare(localUri: String): String = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            val uri = Uri.parse(localUri)
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Choose a photo using the photo picker." }
            val source = readSource(uri)
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) {
                "This photo is too large or unsupported. Choose a smaller photo."
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > SparkPhotoPolicy.MAX_EDGE * 2) sample *= 2
            bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }) ?: throw IOException("This photo could not be opened. Choose another photo.")
            currentCoroutineContext().ensureActive()

            val orientation = runCatching {
                ByteArrayInputStream(source).use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = orientationMatrix(orientation)
            if (matrix != null) {
                val current = checkNotNull(bitmap)
                val turned = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                bitmap = turned
                if (turned !== current) current.recycle()
            }
            var current = checkNotNull(bitmap)
            if (max(current.width, current.height) > SparkPhotoPolicy.MAX_EDGE) {
                val ratio = SparkPhotoPolicy.MAX_EDGE.toDouble() / max(current.width, current.height)
                val resized = Bitmap.createScaledBitmap(current, (current.width * ratio).roundToInt().coerceAtLeast(1),
                    (current.height * ratio).roundToInt().coerceAtLeast(1), true)
                bitmap = resized
                if (resized !== current) current.recycle()
            }
            current = checkNotNull(bitmap)
            if (current.hasAlpha()) {
                val opaque = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(current, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                bitmap = opaque
                current.recycle()
            }

            repeat(8) {
                currentCoroutineContext().ensureActive()
                current = checkNotNull(bitmap)
                for (quality in listOf(84, 74, 64)) {
                    currentCoroutineContext().ensureActive()
                    val output = ByteArrayOutputStream()
                    check(current.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "This photo could not be prepared." }
                    if (output.size() <= SparkPhotoPolicy.MAX_BYTES) return@withContext SparkPhotoPolicy.encode(output.toByteArray())
                }
                val smaller = Bitmap.createScaledBitmap(current, (current.width * .8).roundToInt().coerceAtLeast(1),
                    (current.height * .8).roundToInt().coerceAtLeast(1), true)
                bitmap = smaller
                if (smaller !== current) current.recycle()
            }
            throw IOException("This photo could not be made small enough. Choose another photo.")
        } catch (_: OutOfMemoryError) {
            throw IOException("Your phone could not prepare this photo. Choose a smaller photo.")
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun readSource(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The selected photo is no longer available.")
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = it.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_SOURCE_BYTES) throw IOException("Choose a photo smaller than 20 MB.")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
        const val MAX_SOURCE_PIXELS = 120_000_000L
    }
}
