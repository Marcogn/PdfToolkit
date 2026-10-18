package com.marcogn.pdftoolkit.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePathsTest {

    @Test
    fun aSharedStoragePathIsKeptAsItIs() {
        assertEquals(listOf("/storage/emulated/0/Download/a.pdf"), candidatePaths("/storage/emulated/0/Download/a.pdf"))
    }

    @Test
    fun mountsBehindSharedStorageAlsoGiveTheStoragePath() {
        assertEquals(
            listOf("/mnt/user/0/emulated/0/Download/a.pdf", "/storage/emulated/0/Download/a.pdf"),
            candidatePaths("/mnt/user/0/emulated/0/Download/a.pdf"),
        )
        assertEquals(
            listOf("/mnt/pass_through/0/1234-ABCD/doc.pdf", "/storage/1234-ABCD/doc.pdf"),
            candidatePaths("/mnt/pass_through/0/1234-ABCD/doc.pdf"),
        )
        assertEquals(
            listOf("/data/media/10/Documents/b.pdf", "/storage/emulated/10/Documents/b.pdf"),
            candidatePaths("/data/media/10/Documents/b.pdf"),
        )
        assertEquals(listOf("/sdcard/c.pdf", "/storage/emulated/0/c.pdf"), candidatePaths("/sdcard/c.pdf"))
    }

    @Test
    fun whatIsNotAFileHasNoPath() {
        assertTrue(candidatePaths("pipe:[12345]").isEmpty())
        assertTrue(candidatePaths("socket:[678]").isEmpty())
        assertTrue(candidatePaths("anon_inode:[memfd]").isEmpty())
        assertTrue(candidatePaths("/storage/emulated/0/Download/gone.pdf (deleted)").isEmpty())
    }
}
