package com.marcogn.pdftoolkit.domain.fill

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where an overlay sits, in the **PDF user space** of its page (points, origin bottom-left, y up,
 * before `/Rotate`): the space it is written in, which doesn't change when the user turns the
 * page, so an overlay turns with its page like the rest of the content.
 *
 * The box is [width] x [height] points around ([centerX], [centerY]); its own x axis (the
 * direction text runs in) points [angle] degrees counterclockwise from the user-space x axis.
 * The conversions to the display and to the screen are in `pdf/render/OverlayGeometry`.
 */
@Serializable
data class OverlayBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angle: Float = 0f,
) {
    init {
        require(width > 0f && height > 0f) { "Overlay box must be positive: $width x $height" }
    }
}

/** A tick or a cross (spec §6.5), drawn as strokes, not as a glyph. */
enum class MarkKind { CHECK, CROSS }

/**
 * Something the user puts on a page with "Fill and sign" (spec §6.5). It is bound to the
 * [pageId] of a `PageItem`, so it follows the page when pages are moved, and it is written into the
 * page content when saving, never as an annotation.
 */
@Serializable
sealed interface Overlay {
    val id: String
    val pageId: String
    val box: OverlayBox

    fun withBox(box: OverlayBox): Overlay
}

/** Text, dates included: a date is text the user can still change. Lines are split on `\n`. */
@Serializable
@SerialName("text")
data class TextOverlay(
    override val id: String,
    override val pageId: String,
    override val box: OverlayBox,
    val text: String,
    val fontSize: Float,
) : Overlay {
    override fun withBox(box: OverlayBox) = copy(box = box)
}

@Serializable
@SerialName("mark")
data class MarkOverlay(
    override val id: String,
    override val pageId: String,
    override val box: OverlayBox,
    val kind: MarkKind,
) : Overlay {
    override fun withBox(box: OverlayBox) = copy(box = box)
}

/** An image, e.g. a signature: [imageUri] is a file the app owns. */
@Serializable
@SerialName("image")
data class ImageOverlay(
    override val id: String,
    override val pageId: String,
    override val box: OverlayBox,
    val imageUri: String,
) : Overlay {
    override fun withBox(box: OverlayBox) = copy(box = box)
}

/** The value the user gave a form field. Only fields the user changed have one. */
@Serializable
sealed interface FieldValue {
    @Serializable
    @SerialName("text")
    data class Text(val value: String) : FieldValue

    /** A check box: on or off. */
    @Serializable
    @SerialName("toggle")
    data class Toggle(val on: Boolean) : FieldValue

    /** A radio group, combo box or list: the value of the option chosen, null for none. */
    @Serializable
    @SerialName("choice")
    data class Choice(val value: String?) : FieldValue
}

/** What "Fill and sign" adds to an edit session: overlays and form values. */
@Serializable
data class FillContent(
    val overlays: List<Overlay> = emptyList(),
    val fields: Map<String, FieldValue> = emptyMap(),
) {
    val isEmpty: Boolean get() = overlays.isEmpty() && fields.isEmpty()

    /** Spec §6.5: "make final" defaults to on when the document carries a signature. */
    val hasSignature: Boolean get() = overlays.any { it is ImageOverlay }

    fun overlaysOn(pageId: String): List<Overlay> = overlays.filter { it.pageId == pageId }
}
