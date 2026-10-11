package com.marcogn.pdftoolkit.pdf.ocr

import android.graphics.Bitmap
import android.graphics.Point
import androidx.compose.ui.geometry.Offset
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Text recognition on one image at a time (spec §7.2). Close it when done. */
interface TextRecognizer : Closeable {
    /**
     * The lines of [bitmap], in its pixels (origin top-left, y down). [bitmap] must stay valid
     * until this returns.
     * @throws IOException if recognition fails.
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine>
}

fun interface TextRecognizerFactory {
    fun create(): TextRecognizer
}

/**
 * ML Kit Text Recognition v2, Latin script (covers Italian, spec §7.2), **bundled**: the model is
 * in the APK, so it works offline from the first use and without Play services (decisions,
 * 2026-10-09).
 */
class MlKitTextRecognizerFactory @Inject constructor() : TextRecognizerFactory {
    override fun create(): TextRecognizer = MlKitTextRecognizer()
}

private class MlKitTextRecognizer : TextRecognizer {

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.flatMap { block -> block.lines.mapNotNull(::lineOf) }
    }

    private fun lineOf(line: Text.Line): OcrLine? {
        val box = quadOf(line.cornerPoints) ?: return null
        val words = line.elements.mapNotNull { element -> quadOf(element.cornerPoints)?.let { OcrWord(element.text, it) } }
        return OcrLine(line.text, box, words)
    }

    private fun quadOf(points: Array<Point>?): OcrQuad? =
        points?.let { OcrQuad.of(it.map { point -> Offset(point.x.toFloat(), point.y.toFloat()) }) }

    override fun close() = client.close()
}

/** Waits for a Play services [Task]; a failure comes out as [IOException]. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(IOException("Text recognition failed", it)) }
    addOnCanceledListener { continuation.cancel() }
}
