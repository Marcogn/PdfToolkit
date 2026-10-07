package com.marcogn.pdftoolkit.domain.annotate

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * A polyline as one string: the first point, then the step to each next one, in hundredths of a
 * point, as integers separated by spaces ("10050 20000 12 -3 ..."). Freehand outlines have
 * hundreds of points and the edit session goes into the saved instance state, whose transaction
 * limit is about 1 MB; this takes about a third of the room of a list of `{"x":..,"y":..}`.
 *
 * Coordinates are rounded to [STEP] points (under 4 µm on paper). Lists written as JSON arrays of
 * points (the 7a format) are still read.
 */
object CompactPolylineSerializer : KSerializer<List<UserPoint>> {

    /** Resolution of the stored coordinates, in points. */
    const val STEP = 0.01f

    private const val SCALE = 100.0
    private val arrayForm = ListSerializer(UserPoint.serializer())

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.marcogn.pdftoolkit.CompactPolyline", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<UserPoint>) = encoder.encodeString(encode(value))

    override fun deserialize(decoder: Decoder): List<UserPoint> {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            return if (element is JsonArray) decoder.json.decodeFromJsonElement(arrayForm, element) else decode(element.jsonPrimitive.content)
        }
        return decode(decoder.decodeString())
    }

    /** [value] rounded to the stored resolution, so that a polyline compares equal after a round trip. */
    fun round(value: Float): Float = ((value * SCALE).roundToLong() / SCALE).toFloat()

    fun encode(points: List<UserPoint>): String {
        val text = StringBuilder(points.size * CHARS_PER_POINT)
        var lastX = 0L
        var lastY = 0L
        points.forEachIndexed { i, point ->
            val x = (point.x * SCALE).roundToLong()
            val y = (point.y * SCALE).roundToLong()
            if (i > 0) text.append(' ')
            text.append(x - lastX).append(' ').append(y - lastY)
            lastX = x
            lastY = y
        }
        return text.toString()
    }

    fun decode(text: String): List<UserPoint> {
        if (text.isBlank()) return emptyList()
        val numbers = text.trim().split(' ')
        if (numbers.size % 2 != 0) throw SerializationException("A polyline needs pairs of numbers")
        var x = 0L
        var y = 0L
        return (0 until numbers.size / 2).map { i ->
            // NumberFormatException is an IllegalArgumentException, as SerializationException is.
            x += numbers[2 * i].toLong()
            y += numbers[2 * i + 1].toLong()
            UserPoint((x / SCALE).toFloat(), (y / SCALE).toFloat())
        }
    }

    private const val CHARS_PER_POINT = 8
}
