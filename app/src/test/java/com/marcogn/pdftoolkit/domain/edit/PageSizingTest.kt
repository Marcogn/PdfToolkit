package com.marcogn.pdftoolkit.domain.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizingTest {

    private val a4 = SizePt(595f, 842f)
    private val letter = SizePt(612f, 792f)

    private fun pdf(id: String) = PageItem.FromPdf(id, DocRef.MAIN, 0)

    // --- Reference size: the page before the insertion point, or the first one at the start ---

    private val sizes = mapOf("a" to a4, "b" to letter, "c" to SizePt(200f, 100f))
    private val pages = listOf(pdf("a"), pdf("b"), pdf("c"))
    private val sizeOf: (PageItem) -> SizePt? = { sizes[it.id] }

    @Test
    fun `the reference is the page before the insertion point`() {
        assertEquals(a4, PageSizing.referenceSize(pages, 1, sizeOf))
        assertEquals(letter, PageSizing.referenceSize(pages, 2, sizeOf))
        assertEquals(SizePt(200f, 100f), PageSizing.referenceSize(pages, 3, sizeOf)) // at the end
    }

    @Test
    fun `at the start the reference is the next page`() {
        assertEquals(a4, PageSizing.referenceSize(pages, 0, sizeOf))
    }

    @Test
    fun `no usable neighbour falls back to A4`() {
        assertEquals(PageSizing.A4, PageSizing.referenceSize(emptyList(), 0, sizeOf))
        assertEquals(PageSizing.A4, PageSizing.referenceSize(pages, 1) { null })
    }

    @Test
    fun `a rotated page is seen with its sides swapped`() {
        assertEquals(SizePt(842f, 595f), a4.rotated(90))
        assertEquals(SizePt(842f, 595f), a4.rotated(270))
        assertEquals(a4, a4.rotated(180))
        assertEquals(a4, a4.rotated(0))
    }

    @Test
    fun `mixed sizes are detected, rounding away fractions`() {
        assertTrue(PageSizing.hasMixedSizes(listOf(a4, letter)))
        assertFalse(PageSizing.hasMixedSizes(listOf(a4, SizePt(595.2f, 841.9f))))
        assertFalse(PageSizing.hasMixedSizes(emptyList()))
    }

    // --- Fit to page: the reference size, turned to follow the image ---

    @Test
    fun `a landscape photo on an A4 portrait document makes a landscape A4 page`() {
        assertEquals(SizePt(842f, 595f), PageSizing.fitPageSize(ImageDimensions(4000, 3000), a4))
    }

    @Test
    fun `a portrait photo on a landscape document makes a portrait page`() {
        assertEquals(SizePt(595f, 842f), PageSizing.fitPageSize(ImageDimensions(3000, 4000), SizePt(842f, 595f)))
    }

    @Test
    fun `a square image keeps the orientation of the reference`() {
        assertEquals(a4, PageSizing.fitPageSize(ImageDimensions(1000, 1000), a4))
        assertEquals(SizePt(842f, 595f), PageSizing.fitPageSize(ImageDimensions(1000, 1000), SizePt(842f, 595f)))
    }

    @Test
    fun `fit uses the size of the reference, Letter included`() {
        assertEquals(SizePt(792f, 612f), PageSizing.fitPageSize(ImageDimensions(1600, 900), letter))
    }

    // --- Original size: pixels to points through the DPI ---

    @Test
    fun `without DPI the original size uses 150`() {
        val page = PageSizing.originalPageSize(ImageDimensions(1500, 3000))
        assertEquals(720f, page.width, 0.01f) // 1500 px / 150 dpi = 10 in
        assertEquals(1440f, page.height, 0.01f)
    }

    @Test
    fun `a sensible DPI from the file is used`() {
        val page = PageSizing.originalPageSize(ImageDimensions(600, 300, dpi = 300f))
        assertEquals(144f, page.width, 0.01f)
        assertEquals(72f, page.height, 0.01f)
    }

    @Test
    fun `the 72 DPI cameras write is ignored, so a 4000 px photo isn't a 1,4 m page`() {
        assertEquals(PageSizing.DEFAULT_DPI, PageSizing.effectiveDpi(72f), 0f)
        assertEquals(PageSizing.DEFAULT_DPI, PageSizing.effectiveDpi(null), 0f)
        assertEquals(PageSizing.DEFAULT_DPI, PageSizing.effectiveDpi(0f), 0f)
        assertEquals(PageSizing.DEFAULT_DPI, PageSizing.effectiveDpi(Float.NaN), 0f)
        assertEquals(PageSizing.DEFAULT_DPI, PageSizing.effectiveDpi(96f), 0f) // below the 100 DPI threshold
        assertEquals(300f, PageSizing.effectiveDpi(300f), 0f)
    }

    @Test
    fun `page size for each mode`() {
        val image = ImageDimensions(3000, 2000)
        assertEquals(SizePt(842f, 595f), PageSizing.pageSizeFor(image, ImageFit.FIT_PAGE, a4))
        assertEquals(PageSizing.originalPageSize(image), PageSizing.pageSizeFor(image, ImageFit.ORIGINAL_SIZE, a4))
    }

    // --- Placement: scaled to fit, centred, never cropped ---

    @Test
    fun `an image with the page's proportions fills it`() {
        val box = PageSizing.placement(ImageDimensions(2000, 1000), SizePt(842f, 421f))
        assertEquals(0f, box.x, 0.01f)
        assertEquals(0f, box.y, 0.01f)
        assertEquals(842f, box.width, 0.01f)
        assertEquals(421f, box.height, 0.01f)
    }

    @Test
    fun `a wide image on a portrait page is centred vertically`() {
        val box = PageSizing.placement(ImageDimensions(2000, 1000), a4)
        assertEquals(0f, box.x, 0.01f)
        assertEquals(595f, box.width, 0.01f)
        assertEquals(297.5f, box.height, 0.01f)
        assertEquals((842f - 297.5f) / 2f, box.y, 0.01f)
    }

    @Test
    fun `a tall image on a landscape page is centred horizontally`() {
        val box = PageSizing.placement(ImageDimensions(1000, 2000), SizePt(842f, 595f))
        assertEquals(595f / 2f, box.width, 0.01f)
        assertEquals(595f, box.height, 0.01f)
        assertEquals((842f - 297.5f) / 2f, box.x, 0.01f)
        assertEquals(0f, box.y, 0.01f)
    }

    @Test
    fun `the placement never leaves the page`() {
        for ((w, h) in listOf(100 to 5000, 5000 to 100, 3 to 3, 4032 to 3024)) {
            val box = PageSizing.placement(ImageDimensions(w, h), a4)
            assertTrue(box.x >= -0.01f && box.y >= -0.01f)
            assertTrue(box.x + box.width <= a4.width + 0.01f)
            assertTrue(box.y + box.height <= a4.height + 0.01f)
        }
    }

    // --- Decoding size: 3000 px long side in fit mode ---

    @Test
    fun `fit mode scales a 12 megapixel photo down to 3000 px`() {
        assertEquals(3000 to 2250, PageSizing.decodeSize(ImageDimensions(4000, 3000), ImageFit.FIT_PAGE))
        assertEquals(2250 to 3000, PageSizing.decodeSize(ImageDimensions(3000, 4000), ImageFit.FIT_PAGE))
    }

    @Test
    fun `fit mode leaves smaller images alone`() {
        assertEquals(1200 to 800, PageSizing.decodeSize(ImageDimensions(1200, 800), ImageFit.FIT_PAGE))
        assertEquals(3000 to 1000, PageSizing.decodeSize(ImageDimensions(3000, 1000), ImageFit.FIT_PAGE))
    }

    @Test
    fun `original size keeps the resolution up to the safety cap`() {
        assertEquals(4000 to 3000, PageSizing.decodeSize(ImageDimensions(4000, 3000), ImageFit.ORIGINAL_SIZE))
        assertEquals(8000 to 4000, PageSizing.decodeSize(ImageDimensions(16000, 8000), ImageFit.ORIGINAL_SIZE))
    }

    @Test
    fun `a very thin image keeps at least one pixel on its short side`() {
        assertEquals(3000 to 1, PageSizing.decodeSize(ImageDimensions(30000, 3), ImageFit.FIT_PAGE))
    }

    // --- Insertion point ---

    @Test
    fun `insertion point to index`() {
        assertEquals(0, InsertionPoint(InsertionPoint.Kind.START).toIndex(10))
        assertEquals(10, InsertionPoint.END_OF_DOCUMENT.toIndex(10))
        assertEquals(2, InsertionPoint(InsertionPoint.Kind.BEFORE_PAGE, 3).toIndex(10))
        assertEquals(3, InsertionPoint(InsertionPoint.Kind.AFTER_PAGE, 3).toIndex(10))
    }

    @Test
    fun `a page number out of range is clamped`() {
        assertEquals(0, InsertionPoint(InsertionPoint.Kind.BEFORE_PAGE, 0).toIndex(10))
        assertEquals(10, InsertionPoint(InsertionPoint.Kind.BEFORE_PAGE, 99).toIndex(10))
        assertEquals(10, InsertionPoint(InsertionPoint.Kind.AFTER_PAGE, 99).toIndex(10))
        assertEquals(0, InsertionPoint(InsertionPoint.Kind.AFTER_PAGE, -5).toIndex(10))
    }
}
