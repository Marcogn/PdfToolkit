package com.marcogn.pdftoolkit.ui.ocr

import com.marcogn.pdftoolkit.data.ocr.OcrProgress
import com.marcogn.pdftoolkit.data.ocr.OcrQueue
import com.marcogn.pdftoolkit.data.ocr.OcrRequest
import com.marcogn.pdftoolkit.data.ocr.OcrResult
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OcrRunnerTest {

    private class FakeQueue : OcrQueue {
        val progress = MutableStateFlow<OcrProgress?>(null)
        var cancelled: UUID? = null
        val id: UUID = UUID.randomUUID()
        override suspend fun enqueue(request: OcrRequest): UUID = id
        override fun observe(id: UUID): Flow<OcrProgress?> = progress
        override fun cancel(id: UUID) {
            cancelled = id
        }
    }

    private val request = OcrRequest("file:///source.pdf", "file:///out.pdf")

    @Test
    fun `follows the progress to the end and tells whether a file was written`() = runTest(UnconfinedTestDispatcher()) {
        val queue = FakeQueue()
        val runner = OcrRunner(backgroundScope, queue)
        var written: Boolean? = null

        assertTrue(runner.start(request, overwrite = false) { written = it })
        assertEquals(OcrUiState.Running(0f, cancellable = true), runner.state.value)

        queue.progress.value = OcrProgress.Running(0.5f)
        assertEquals(OcrUiState.Running(0.5f, cancellable = true), runner.state.value)
        queue.progress.value = OcrProgress.Running(0.97f)
        assertEquals(OcrUiState.Running(0.97f, cancellable = false), runner.state.value)

        queue.progress.value = OcrProgress.Done("file:///out.pdf", OcrResult.WRITTEN)
        assertEquals(OcrUiState.Done("file:///out.pdf", overwrite = false, result = OcrResult.WRITTEN), runner.state.value)
        assertEquals(true, written)
    }

    @Test
    fun `nothing to recognise is a finished run that wrote nothing`() = runTest(UnconfinedTestDispatcher()) {
        val queue = FakeQueue()
        val runner = OcrRunner(backgroundScope, queue)
        var written: Boolean? = null
        runner.start(request, overwrite = true) { written = it }

        queue.progress.value = OcrProgress.Done("file:///out.pdf", OcrResult.NO_TEXT)

        assertEquals(false, written)
        assertEquals(OcrUiState.Done("file:///out.pdf", overwrite = true, result = OcrResult.NO_TEXT), runner.state.value)
    }

    @Test
    fun `a failure is reported with its reason`() = runTest(UnconfinedTestDispatcher()) {
        val queue = FakeQueue()
        val runner = OcrRunner(backgroundScope, queue)
        var written: Boolean? = null
        runner.start(request, overwrite = false) { written = it }

        queue.progress.value = OcrProgress.Failed(SaveFailure.PROTECTED)

        assertEquals(OcrUiState.Failed(SaveFailure.PROTECTED), runner.state.value)
        assertEquals(false, written)
    }

    @Test
    fun `cancelling asks the queue and ends idle once confirmed`() = runTest(UnconfinedTestDispatcher()) {
        val queue = FakeQueue()
        val runner = OcrRunner(backgroundScope, queue)
        var written: Boolean? = null
        runner.start(request, overwrite = false) { written = it }
        queue.progress.value = OcrProgress.Running(0.4f)

        runner.cancel()
        assertEquals(queue.id, queue.cancelled)
        queue.progress.value = OcrProgress.Cancelled

        assertEquals(OcrUiState.Idle, runner.state.value)
        assertEquals(false, written)
    }

    @Test
    fun `the last moment, the copy over the destination, cannot be cancelled`() = runTest(UnconfinedTestDispatcher()) {
        val queue = FakeQueue()
        val runner = OcrRunner(backgroundScope, queue)
        runner.start(request, overwrite = true) {}
        queue.progress.value = OcrProgress.Running(0.97f)

        runner.cancel()

        assertNull(queue.cancelled)
    }

    @Test
    fun `only one run at a time, and a result is dismissed`() = runTest(UnconfinedTestDispatcher()) {
        val queue = FakeQueue()
        val runner = OcrRunner(backgroundScope, queue)
        assertTrue(runner.start(request, overwrite = false) {})
        assertFalse(runner.start(request, overwrite = false) {})
        runner.dismiss()
        assertTrue(runner.state.value is OcrUiState.Running)

        queue.progress.value = OcrProgress.Done("d", OcrResult.WRITTEN)
        runner.dismiss()
        assertEquals(OcrUiState.Idle, runner.state.value)
    }
}
