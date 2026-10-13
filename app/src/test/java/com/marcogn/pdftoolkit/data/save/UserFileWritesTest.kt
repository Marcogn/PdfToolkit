package com.marcogn.pdftoolkit.data.save

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFileWritesTest {

    @Test
    fun `active while any tracked write runs, also when one fails`() = runTest {
        val writes = UserFileWrites()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        assertFalse(writes.active.value)

        val a = async { writes.track { first.await(); 1 } }
        val b = async { runCatching { writes.track { second.await(); error("disk full") } } }
        runCurrent()
        assertTrue(writes.active.value)

        first.complete(Unit)
        assertEquals(1, a.await())
        assertTrue("the second write is still running", writes.active.value)

        second.complete(Unit)
        assertTrue(b.await().isFailure)
        assertFalse(writes.active.value)
    }
}
