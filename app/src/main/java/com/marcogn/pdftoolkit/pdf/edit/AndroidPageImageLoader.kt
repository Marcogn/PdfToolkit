package com.marcogn.pdftoolkit.pdf.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [PageImageLoader] on `ImageDecoder` (API 28+), which also decodes HEIC/HEIF/AVIF where the
 * device supports them and applies the EXIF orientation itself: the sizes it reports and the
 * bitmaps it returns are already the right way up (AOSP `libs/hwui/hwui/ImageDecoder.cpp` swaps
 * width and height for the origins that turn the image). The software allocator is needed because
 * PdfBox reads pixels, which hardware bitmaps don't allow.
 *
 * On Android 8.x (API 26–27) there is no `ImageDecoder`: `BitmapFactory` reads JPEG, PNG, WebP and
 * GIF, the orientation is applied here, and HEIC/HEIF/AVIF files are reported as unreadable.
 */
@Singleton
class AndroidPageImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) : PageImageLoader {

    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override fun probe(uri: String): ImageDimensions? {
        val parsed = uri.toUri()
        val size = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) probeModern(parsed) else probeLegacy(parsed)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return ImageDimensions(size.first, size.second, readDpi(parsed))
    }

    override fun load(uri: String, width: Int, height: Int): LoadedImage {
        try {
            val parsed = uri.toUri()
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeModern(parsed) { size -> if (size.first != width || size.second != height) width to height else null }
            } else {
                decodeLegacy(parsed, width, height)
            }
            return LoadedImage(bitmap, bitmap.hasAlpha())
        } catch (e: RuntimeException) {
            throw IOException("Can't decode $uri", e)
        }
    }

    override fun thumbnail(uri: String, maxSidePx: Int): Bitmap? {
        thumbnails.get(uri)?.let { return it }
        return try {
            val parsed = uri.toUri()
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeModern(parsed) { size -> scaledTo(size, maxSidePx) }
            } else {
                val (width, height) = probeLegacy(parsed) ?: return null
                val target = scaledTo(width to height, maxSidePx) ?: (width to height)
                decodeLegacy(parsed, target.first, target.second)
            }
            bitmap.also { thumbnails.put(uri, it) }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    // --- API 28+: ImageDecoder ---

    @RequiresApi(Build.VERSION_CODES.P)
    private fun probeModern(uri: Uri): Pair<Int, Int>? {
        var size: Pair<Int, Int>? = null
        try {
            // The header listener sees the size before any pixel is decoded; asking for a 1x1
            // target keeps the decode that follows as cheap as the codec allows.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                size = info.size.width to info.size.height
                decoder.setTargetSize(1, 1)
            }
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            // The size is known from the header even if the decode that followed failed.
            if (size == null) return null
        }
        return size
    }

    /** Decodes [uri]; [targetFor] gets the image's own size and returns the size to scale to, or null to keep it. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(uri: Uri, targetFor: (Pair<Int, Int>) -> Pair<Int, Int>?): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            targetFor(info.size.width to info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }

    // --- API 26–27: BitmapFactory + EXIF ---

    private fun openStream(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")

    private fun exifOrientation(uri: Uri): Int = try {
        openStream(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rawBounds(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** Size as it appears: width and height swap for the orientations that turn the image by a quarter. */
    private fun probeLegacy(uri: Uri): Pair<Int, Int>? {
        val (width, height) = rawBounds(uri) ?: return null
        return if (swapsSides(exifOrientation(uri))) height to width else width to height
    }

    private fun decodeLegacy(uri: Uri, width: Int, height: Int): Bitmap {
        val (rawWidth, rawHeight) = rawBounds(uri) ?: throw IOException("Not an image: $uri")
        val orientation = exifOrientation(uri)
        val sideways = swapsSides(orientation)
        // Power-of-two subsampling while the result stays at least as large as the target.
        val (targetRawWidth, targetRawHeight) = if (sideways) height to width else width to height
        var sample = 1
        while (rawWidth / (sample * 2) >= targetRawWidth && rawHeight / (sample * 2) >= targetRawHeight) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Can't decode $uri")
        val matrix = orientationMatrix(orientation)
        // Scale to the exact target in the same step as the rotation: the scale comes after it, so it works on the oriented sides.
        val orientedWidth = if (sideways) decoded.height else decoded.width
        val orientedHeight = if (sideways) decoded.width else decoded.height
        matrix.postScale(width.toFloat() / orientedWidth, height.toFloat() / orientedHeight)
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    // --- Shared ---

    /** DPI from the EXIF resolution tags, if the file has them (JPEG, HEIF); null otherwise. */
    private fun readDpi(uri: Uri): Float? = try {
        openStream(uri).use { stream ->
            val exif = ExifInterface(stream)
            val resolution = exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0)
            when (exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, UNIT_INCH)) {
                UNIT_CM -> (resolution * CM_PER_INCH).toFloat()
                else -> resolution.toFloat()
            }.takeIf { it > 0f }
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun scaledTo(size: Pair<Int, Int>, maxSidePx: Int): Pair<Int, Int>? {
        val long = max(size.first, size.second)
        if (long <= maxSidePx) return null
        val scale = maxSidePx.toFloat() / long
        return max(1, (size.first * scale).roundToInt()) to max(1, (size.second * scale).roundToInt())
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024
        const val UNIT_INCH = 2
        const val UNIT_CM = 3
        const val CM_PER_INCH = 2.54

        fun swapsSides(orientation: Int): Boolean = orientation in
            listOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)

        /** The transform for each EXIF orientation, as in Glide's `TransformationUtils.initializeMatrixForRotation`. */
        fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
    }
}
