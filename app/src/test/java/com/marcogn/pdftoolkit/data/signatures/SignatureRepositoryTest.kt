package com.marcogn.pdftoolkit.data.signatures

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.data.recents.AppDatabase
import com.marcogn.pdftoolkit.domain.signature.InkColor
import com.marcogn.pdftoolkit.domain.signature.InkPoint
import com.marcogn.pdftoolkit.domain.signature.InkStroke
import com.marcogn.pdftoolkit.domain.signature.SignatureType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignatureRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: SignatureRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = SignatureRepository(context, database.signatureDao())
    }

    @After
    fun tearDown() {
        database.close()
        SignatureRepository.directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun bitmap() = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)

    @Test
    fun savedSignatureIsAPngInTheSignaturesFolderAndTheFirstIsTheFavourite() = runBlocking {
        val first = repository.save(bitmap(), "Firma 1", SignatureType.DRAWN, now = 1)!!
        val second = repository.save(bitmap(), "Firma 2", SignatureType.IMPORTED, now = 2)!!
        assertTrue(first.isDefault)
        assertFalse(second.isDefault)
        assertEquals(SignatureRepository.directory(context), repository.file(first).parentFile)
        assertTrue(repository.file(first).length() > 0)
        assertEquals(40, first.widthPx)
        assertEquals(20, first.heightPx)
        // Favourite first, then the newest.
        assertEquals(listOf(first.id, second.id), repository.signatures.first().map { it.id })
    }

    @Test
    fun onlyOneSignatureIsTheFavourite() = runBlocking {
        val first = repository.save(bitmap(), "a", SignatureType.DRAWN)!!
        val second = repository.save(bitmap(), "b", SignatureType.DRAWN)!!
        repository.setDefault(second.id, true)
        val saved = repository.signatures.first()
        assertEquals(listOf(second.id), saved.filter { it.isDefault }.map { it.id })
        repository.setDefault(second.id, false)
        assertTrue(repository.signatures.first().none { it.isDefault })
        assertNotNull(first)
    }

    @Test
    fun renameAndDeleteRemoveTheRowAndTheFile() = runBlocking {
        val saved = repository.save(bitmap(), "old", SignatureType.DRAWN)!!
        repository.rename(saved.id, "new")
        assertEquals("new", repository.signatures.first().single().name)
        repository.delete(saved)
        assertTrue(repository.signatures.first().isEmpty())
        assertFalse(repository.file(saved).exists())
    }

    @Test
    fun suggestedNameCountsTheSignaturesAlreadySaved() = runBlocking {
        assertEquals("Firma 1", repository.suggestedName("Firma"))
        repository.save(bitmap(), "x", SignatureType.DRAWN)
        assertEquals("Firma 2", repository.suggestedName("Firma"))
    }

    @Test
    fun drawingRendersToABitmapCroppedToTheStrokes() {
        val stroke = InkStroke(InkColor.BLACK, listOf(InkPoint(100f, 200f, 0), InkPoint(300f, 260f, 20), InkPoint(500f, 220f, 40)))
        val bitmap = SignatureRendering.render(listOf(stroke), basePx = 8f)!!
        // Bounds are the strokes plus the widest half-line on each side: about 400 x 60 px.
        assertTrue("width ${bitmap.width}", bitmap.width in 400..430)
        assertTrue("height ${bitmap.height}", bitmap.height in 60..90)
        assertNull(SignatureRendering.render(emptyList(), 8f))
    }

    @Test
    fun hugeDrawingsAreScaledDownToTheMaximumSide() {
        val stroke = InkStroke(InkColor.BLUE, listOf(InkPoint(0f, 0f, 0), InkPoint(6_000f, 100f, 100)))
        val bitmap = SignatureRendering.render(listOf(stroke), basePx = 8f)!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= SignatureRendering.MAX_SIDE_PX + 1)
    }

    @Test
    fun importWithBackgroundRemovalIsTrimmedToTheInk() {
        val source = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            for (x in 40..59) for (y in 20..29) setPixel(x, y, android.graphics.Color.BLACK)
        }
        val out = SignatureRendering.processImport(source, CropFractions.Full, removeBackground = true, threshold = 0.75f)!!
        // 20 x 10 of ink plus the trim margin on each side.
        assertEquals(28, out.width)
        assertEquals(18, out.height)
        assertEquals(0, android.graphics.Color.alpha(out.getPixel(0, 0)))
        assertEquals(255, android.graphics.Color.alpha(out.getPixel(14, 9)))
        // Cropping away the ink leaves nothing to keep.
        assertNull(SignatureRendering.processImport(source, CropFractions(0f, 0f, 0.3f, 0.3f), removeBackground = true, threshold = 0.75f))
    }

    @Test
    fun importWithoutBackgroundRemovalKeepsTheCrop() {
        val source = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val out = SignatureRendering.processImport(source, CropFractions(0.1f, 0.5f, 0.6f, 1f), removeBackground = false, threshold = 0.75f)!!
        assertEquals(50, out.width)
        assertEquals(30, out.height)
        assertEquals(255, android.graphics.Color.alpha(out.getPixel(0, 0)))
    }
}
