package com.marcogn.pdftoolkit.pdf.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One open PDF on top of `android.graphics.pdf.PdfRenderer` (ADR 0001).
 *
 * `PdfRenderer` is not thread safe and allows a single open page at a time, so every access goes
 * through [mutex], rendering runs on `Dispatchers.Default` and the page is always closed after
 * rendering (spec §3.1).
 *
 * [close] never blocks: if a render is in progress the renderer is released by whoever holds the
 * mutex after it, so the native document is never closed under an open page.
 */
class PdfDocumentRenderer private constructor(
    private val renderer: PdfRenderer,
    /** Page sizes read at opening (spec §5: placeholders with the right proportions). */
    val pageSizes: List<PageSize>,
    /** Temporary copy made for a non-seekable source, deleted on release. */
    private val tempFile: File?,
) {
    private val mutex = Mutex()
    private val closeRequested = AtomicBoolean(false)
    private var released = false // guarded by mutex

    val pageCount: Int get() = pageSizes.size

    /**
     * Renders [key] into a new white ARGB_8888 bitmap. Null if the document has been closed or the
     * bitmap doesn't fit in memory.
     */
    suspend fun render(key: RenderKey): Bitmap? = withContext(Dispatchers.Default) {
        val bitmap = mutex.withLock {
            if (released) return@withLock null
            val bitmap = try {
                createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                return@withLock null
            }
            // PdfRenderer draws only the page content: the background would stay transparent.
            bitmap.eraseColor(Color.WHITE)
            // Page points → bitmap pixels, the documented way to render tiles.
            val transform = Matrix().apply {
                setScale(key.scale, key.scale)
                postTranslate(-key.left.toFloat(), -key.top.toFloat())
            }
            renderer.openPage(key.pageIndex).use { page ->
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        }
        releaseIfClosed()
        bitmap
    }

    /** Releases the document now, or as soon as the render in progress finishes. Idempotent. */
    fun close() {
        closeRequested.set(true)
        releaseIfClosed()
    }

    private fun releaseIfClosed() {
        if (!closeRequested.get() || !mutex.tryLock()) return
        try {
            if (!released) {
                released = true
                renderer.close() // also closes the file descriptor
                tempFile?.delete()
            }
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        /** `PdfRenderer` can open password-protected files from Android 15 (API 35). */
        val SUPPORTS_PASSWORD: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        /**
         * Opens [fd], taking ownership of it (closed on failure too), and reads every page size.
         * Throws what `PdfRenderer` throws: `SecurityException` for password-protected files
         * (also for a wrong [password]), `IOException` for damaged or non-PDF files,
         * `IllegalArgumentException` if [fd] is not seekable.
         *
         * [password] needs [SUPPORTS_PASSWORD] (API 35: `PdfRenderer(fd, LoadParams)`); below that
         * it is ignored and protected files fail with `SecurityException`.
         */
        suspend fun open(fd: ParcelFileDescriptor, tempFile: File? = null, password: String? = null): PdfDocumentRenderer =
            withContext(Dispatchers.IO) {
                val renderer = try {
                    if (password != null && SUPPORTS_PASSWORD) {
                        PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())
                    } else {
                        PdfRenderer(fd)
                    }
                } catch (e: Throwable) {
                    // The constructor takes ownership only on success.
                    fd.close()
                    throw e
                }
                try {
                    val sizes = List(renderer.pageCount) { index ->
                        renderer.openPage(index).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
                    }
                    if (sizes.isEmpty()) throw java.io.IOException("Document without pages")
                    PdfDocumentRenderer(renderer, sizes, tempFile)
                } catch (e: Throwable) {
                    renderer.close()
                    throw e
                }
            }
    }
}
