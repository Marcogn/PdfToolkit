package com.marcogn.pdftoolkit.data.recents

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentsRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: RecentsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RecentsRepository(context, database.recentDocumentDao(), ThumbnailStore(context))
    }

    @After
    fun tearDown() = database.close()

    private fun uri(n: Int) = "content://docs/$n"

    @Test
    fun firstOpeningStartsAtPageZero() = runBlocking {
        assertEquals(0, repository.recordOpened(uri(1), "a.pdf", 1_000, pageCount = 10, now = 1))
        val saved = repository.recents.first().single()
        assertEquals("a.pdf", saved.displayName)
        assertEquals(10, saved.pageCount)
    }

    @Test
    fun reopeningKeepsTheLastPageAndMovesToTheTop() = runBlocking {
        repository.recordOpened(uri(1), "a.pdf", null, 10, now = 1)
        repository.recordOpened(uri(2), "b.pdf", null, 10, now = 2)
        database.recentDocumentDao().updateLastPage(uri(1), 7)

        assertEquals(7, repository.recordOpened(uri(1), "a.pdf", null, 10, now = 3))
        assertEquals(listOf(uri(1), uri(2)), repository.recents.first().map { it.uri })
    }

    @Test
    fun lastPageIsClampedWhenTheDocumentGotShorter() = runBlocking {
        repository.recordOpened(uri(1), "a.pdf", null, 50, now = 1)
        database.recentDocumentDao().updateLastPage(uri(1), 40)
        assertEquals(9, repository.recordOpened(uri(1), "a.pdf", null, 10, now = 2))
    }

    @Test
    fun onlyTheTenMostRecentAreKept() = runBlocking {
        for (n in 1..13) repository.recordOpened(uri(n), "$n.pdf", null, 5, now = n.toLong())
        val kept = repository.recents.first().map { it.uri }
        assertEquals(RecentsRepository.MAX_RECENTS, kept.size)
        assertEquals(uri(13), kept.first())
        assertFalse(uri(1) in kept)
        assertFalse(uri(3) in kept)
        assertTrue(uri(4) in kept)
    }

    @Test
    fun removeAndClear() = runBlocking {
        repository.recordOpened(uri(1), "a.pdf", null, 5, now = 1)
        repository.recordOpened(uri(2), "b.pdf", null, 5, now = 2)
        repository.remove(uri(1))
        assertEquals(listOf(uri(2)), repository.recents.first().map { it.uri })
        assertFalse(repository.contains(uri(1)))
        repository.clear()
        assertTrue(repository.recents.first().isEmpty())
        assertNull(database.recentDocumentDao().find(uri(2)))
    }

    @Test
    fun aMissingFileIsNotAccessible() = runBlocking {
        assertFalse(repository.isAccessible("content://nobody.here/none.pdf"))
        assertFalse(repository.isAccessible("file:///no/such/file.pdf"))
    }
}
