package com.marcogn.pdftoolkit.pdf.render

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderSchedulerTest {

    private fun page(index: Int) = PageKey(index, width = 10, height = 10, scale = 1f) // 400 bytes

    private val rendered = mutableListOf<RenderKey>()

    private fun TestScope.scheduler(
        pageCacheBytes: Long = 10_000,
        render: suspend (RenderKey) -> String? = { "bitmap of $it" },
    ): RenderScheduler<String> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RenderScheduler(
            scope = backgroundScope,
            dispatcher = dispatcher,
            budget = RenderBudget(pageCacheBytes, tileCacheBytes = 10_000, maxPageBitmapBytes = 10_000),
            bytesOf = { 400L },
            render = { key -> rendered += key; render(key) },
        )
    }

    @Test
    fun rendersRequestedKeysInOrderAndCachesThem() = runTest {
        val scheduler = scheduler()
        scheduler.request(listOf(page(3), page(1), page(2)))
        runCurrent()
        assertEquals(listOf(page(3), page(1), page(2)), rendered)
        assertNotNull(scheduler[page(1)])
        assertEquals(3L, scheduler.revision.value)

        // Already cached: nothing new to render.
        scheduler.request(listOf(page(1), page(2)))
        runCurrent()
        assertEquals(3, rendered.size)
    }

    @Test
    fun newRequestReplacesTheOldOneBetweenRenders() = runTest {
        val gate = CompletableDeferred<Unit>()
        val scheduler = scheduler(render = { key -> if (key == page(0)) gate.await(); "ok" })
        scheduler.request(listOf(page(0), page(1), page(2)))
        runCurrent() // page 0 in progress
        scheduler.request(listOf(page(7), page(8)))
        gate.complete(Unit)
        runCurrent()
        // Pages 1 and 2 scrolled away before their turn: never rendered.
        assertEquals(listOf(page(0), page(7), page(8)), rendered)
        assertNotNull(scheduler[page(0)]) // the render in progress is kept
    }

    @Test
    fun listLargerThanTheCacheIsRenderedOncePerPass() = runTest {
        // Room for two bitmaps, three wanted: without the per-pass guard this would loop forever.
        val scheduler = scheduler(pageCacheBytes = 800)
        scheduler.request(listOf(page(0), page(1), page(2)))
        runCurrent()
        assertEquals(listOf(page(0), page(1), page(2)), rendered)
        assertNull(scheduler[page(0)])
    }

    @Test
    fun failedRenderIsSkippedNotRetriedInALoop() = runTest {
        val scheduler = scheduler(render = { key -> if (key == page(1)) throw IllegalStateException("boom") else "ok" })
        scheduler.request(listOf(page(0), page(1), page(2)))
        runCurrent()
        assertEquals(listOf(page(0), page(1), page(2)), rendered)
        assertNull(scheduler[page(1)])
        assertNotNull(scheduler[page(2)])
    }
}
