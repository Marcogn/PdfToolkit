package com.marcogn.pdftoolkit.data.save

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The copy-to-temp, write, copy-to-destination flow of spec §6.7, with a fake editor and file:// URIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfSaverTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var source: File

    private class FakeEditor(private val result: ByteArray? = null, private val failure: SaveFailure? = null) : PdfEditor {
        var sourceSeen: ByteArray? = null

        override suspend fun applySession(session: EditSession, sources: Map<DocRef, File>, output: File, onProgress: (Float) -> Unit) {
            sourceSeen = sources.getValue(DocRef.MAIN).readBytes()
            onProgress(0.5f)
            if (failure != null) {
                output.writeBytes(byteArrayOf(1, 2, 3)) // half-written output
                throw SaveException(failure)
            }
            output.writeBytes(result!!)
        }
    }

    @Before
    fun setUp() {
        source = folder.newFile("source.pdf").apply { writeBytes("ORIGINAL".toByteArray()) }
    }

    private fun request(destination: Uri) = SaveRequest(
        sourceUri = source.toUri().toString(),
        destinationUri = destination.toString(),
        sourcePageCount = 3,
        pages = EditSession.of(3).remove(setOf("p0")).encode(),
    )

    private fun workFiles() = PdfSaver.workDir(context).listFiles().orEmpty().toList()

    @Test
    fun `writes the new file to the destination and cleans up`() = runBlocking {
        val destination = File(folder.root, "copy.pdf")
        val editor = FakeEditor(result = "EDITED".toByteArray())
        PdfSaver(context, editor).save(request(destination.toUri()))

        assertEquals("EDITED", destination.readText())
        assertEquals("ORIGINAL", source.readText())
        assertEquals("ORIGINAL", String(editor.sourceSeen!!))
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `overwriting replaces the original completely, even with a shorter file`() = runBlocking {
        PdfSaver(context, FakeEditor(result = "NEW".toByteArray())).save(request(source.toUri()))
        assertEquals("NEW", source.readText())
    }

    @Test
    fun `when the editor fails the destination is not touched`() = runBlocking {
        val destination = File(folder.root, "existing.pdf").apply { writeText("KEEP ME") }
        try {
            PdfSaver(context, FakeEditor(failure = SaveFailure.FAILED)).save(request(destination.toUri()))
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
        assertEquals("KEEP ME", destination.readText())
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `a missing source is reported as such`() = runBlocking {
        source.delete()
        try {
            PdfSaver(context, FakeEditor(result = ByteArray(0))).save(request(File(folder.root, "out.pdf").toUri()))
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.SOURCE_UNREADABLE, e.failure)
        }
    }

    @Test
    fun `a pages string that doesn't match the document fails cleanly`() = runBlocking {
        val bad = request(File(folder.root, "out.pdf").toUri()).copy(pages = "p0,0,9,0")
        try {
            PdfSaver(context, FakeEditor(result = ByteArray(0))).save(bad)
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
    }

    @Test
    fun `cleanup only removes stale files`() {
        val dir = PdfSaver.workDir(context)
        val fresh = File(dir, "fresh").apply { writeText("x") }
        val stale = File(dir, "stale").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        }
        PdfSaver.cleanWorkDir(context)
        assertTrue(fresh.exists())
        assertTrue(!stale.exists())
        fresh.delete()
        assertArrayEquals(emptyArray<File>(), dir.listFiles())
    }
}
