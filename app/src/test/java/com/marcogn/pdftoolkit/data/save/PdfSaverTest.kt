package com.marcogn.pdftoolkit.data.save

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import com.marcogn.pdftoolkit.pdf.edit.WriteOptions
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
        var sourcesSeen: Map<DocRef, String> = emptyMap()

        override suspend fun applySession(session: EditSession, sources: Map<DocRef, File>, output: File, options: WriteOptions, onProgress: (Float) -> Unit) {
            sourceSeen = sources.getValue(DocRef.MAIN).readBytes()
            sourcesSeen = sources.mapValues { it.value.readText() }
            onProgress(0.5f)
            if (failure != null) {
                output.writeBytes(byteArrayOf(1, 2, 3)) // half-written output
                throw SaveException(failure)
            }
            output.writeBytes(result!!)
        }

        override suspend fun hasFormFields(open: () -> java.io.InputStream?) = false
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
        val bad = request(File(folder.root, "out.pdf").toUri()).copy(pages = "P,p0,0,9,0")
        try {
            PdfSaver(context, FakeEditor(result = ByteArray(0))).save(bad)
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
    }

    private fun mergeRequest(destination: File, vararg extras: ExtraSource) = SaveRequest(
        sourceUri = source.toUri().toString(),
        destinationUri = destination.toUri().toString(),
        sourcePageCount = 2,
        pages = EditSession.of(2).insert(2, listOf(PageItem.FromPdf("n1", DocRef(1), 0))).encode(),
        extraSources = extras.toList(),
    )

    @Test
    fun `added PDFs are copied for the editor and removed afterwards`() = runBlocking {
        val added = folder.newFile("added.pdf").apply { writeText("ADDED") }
        val editor = FakeEditor(result = "MERGED".toByteArray())
        PdfSaver(context, editor).save(mergeRequest(File(folder.root, "out.pdf"), ExtraSource(1, added.toUri().toString(), 3)))

        assertEquals(mapOf(DocRef.MAIN to "ORIGINAL", DocRef(1) to "ADDED"), editor.sourcesSeen)
        assertEquals("MERGED", File(folder.root, "out.pdf").readText())
        assertTrue(workFiles().isEmpty())
        assertEquals("ADDED", added.readText())
    }

    @Test
    fun `an added PDF the pages no longer use is not read`() = runBlocking {
        val editor = FakeEditor(result = "OUT".toByteArray())
        val gone = File(folder.root, "gone.pdf") // never created: reading it would fail
        val request = mergeRequest(File(folder.root, "out.pdf"), ExtraSource(1, source.toUri().toString(), 3), ExtraSource(2, gone.toUri().toString(), 1))
        PdfSaver(context, editor).save(request)
        assertEquals(setOf(DocRef.MAIN, DocRef(1)), editor.sourcesSeen.keys)
    }

    @Test
    fun `an added PDF that can't be read is reported as an unreadable source and nothing is written`() = runBlocking {
        val destination = File(folder.root, "out.pdf").apply { writeText("KEEP ME") }
        val missing = File(folder.root, "missing.pdf")
        try {
            PdfSaver(context, FakeEditor(result = ByteArray(0))).save(mergeRequest(destination, ExtraSource(1, missing.toUri().toString(), 3)))
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.SOURCE_UNREADABLE, e.failure)
        }
        assertEquals("KEEP ME", destination.readText())
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `a page of an added PDF beyond its page count fails cleanly`() = runBlocking {
        val added = folder.newFile("added2.pdf").apply { writeText("ADDED") }
        try {
            PdfSaver(context, FakeEditor(result = ByteArray(0))).save(mergeRequest(File(folder.root, "out.pdf"), ExtraSource(1, added.toUri().toString(), 0)))
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
