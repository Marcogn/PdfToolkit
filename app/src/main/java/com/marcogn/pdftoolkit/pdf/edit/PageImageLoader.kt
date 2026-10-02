package com.marcogn.pdftoolkit.pdf.edit

import android.graphics.Bitmap
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions

/** An image decoded for a page: [bitmap] has the EXIF orientation applied; [hasAlpha] picks lossless over JPEG. */
class LoadedImage(val bitmap: Bitmap, val hasAlpha: Boolean)

/**
 * Reads the images that become pages (spec §6.2). Behind an interface so the editor can be tested
 * with generated bitmaps; the Android implementation decodes with `ImageDecoder`, which handles
 * HEIC/HEIF/AVIF and the EXIF orientation (PdfBox-Android does neither).
 */
interface PageImageLoader {

    /** Size and DPI of the image at [uri], without decoding its pixels; null if it can't be read as an image. */
    fun probe(uri: String): ImageDimensions?

    /**
     * The image decoded at [width] x [height] pixels (its oriented size, or smaller to save memory).
     * @throws java.io.IOException if it can't be decoded.
     */
    fun load(uri: String, width: Int, height: Int): LoadedImage

    /** A small preview whose longer side is about [maxSidePx]; null if it can't be decoded. May be cached. */
    fun thumbnail(uri: String, maxSidePx: Int): Bitmap?
}
