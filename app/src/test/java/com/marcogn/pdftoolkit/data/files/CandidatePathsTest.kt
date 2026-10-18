package com.marcogn.pdftoolkit.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePathsTest {

    @Test
    fun aSharedStoragePathIsKeptAsItIs() {
        assertEquals(listOf("/storage/emulated/0/Download/a.pdf"), candidatePaths("/storage/emulated/0/Download/a.pdf"))
    }

    @Test
    fun mountsBehindSharedStorageGiveTheStoragePathFirst() {
        assertEquals(
            listOf("/storage/emulated/0/Download/a.pdf", "/mnt/user/0/emulated/0/Download/a.pdf"),
            candidatePaths("/mnt/user/0/emulated/0/Download/a.pdf"),
        )
        assertEquals(
            listOf("/storage/1234-ABCD/doc.pdf", "/mnt/pass_through/0/1234-ABCD/doc.pdf"),
            candidatePaths("/mnt/pass_through/0/1234-ABCD/doc.pdf"),
        )
        assertEquals(
            listOf("/storage/emulated/0/x.pdf", "/mnt/runtime/write/emulated/0/x.pdf"),
            candidatePaths("/mnt/runtime/write/emulated/0/x.pdf"),
        )
        assertEquals(listOf("/storage/1234-ABCD/y.pdf", "/mnt/media_rw/1234-ABCD/y.pdf"), candidatePaths("/mnt/media_rw/1234-ABCD/y.pdf"))
        assertEquals(
            listOf("/storage/emulated/10/Documents/b.pdf", "/data/media/10/Documents/b.pdf"),
            candidatePaths("/data/media/10/Documents/b.pdf"),
        )
        assertEquals(listOf("/storage/emulated/0/c.pdf", "/sdcard/c.pdf"), candidatePaths("/sdcard/c.pdf"))
    }

    @Test
    fun anAppsPrivateFileIsNotInSharedStorage() {
        assertEquals(listOf("/data/user/0/com.example.mail/cache/a.pdf"), candidatePaths("/data/user/0/com.example.mail/cache/a.pdf"))
        assertTrue(candidatePaths("/data/user/0/com.example.mail/cache/a.pdf").none { it.startsWith(SHARED_STORAGE) })
    }

    @Test
    fun otherAppsFoldersAreOutOfReach() {
        assertTrue(isReachableWithAllFilesAccess("/storage/emulated/0/Download/a.pdf"))
        assertTrue(isReachableWithAllFilesAccess("/storage/1234-ABCD/Docs/a.pdf"))
        assertFalse(isReachableWithAllFilesAccess("/storage/emulated/0/Android/data/com.example.mail/cache/a.pdf"))
        assertFalse(isReachableWithAllFilesAccess("/storage/emulated/0/Android/obb/com.example/a.pdf"))
        assertFalse(isReachableWithAllFilesAccess("/data/user/0/com.example.mail/cache/a.pdf"))
    }

    @Test
    fun whatIsNotAFileHasNoPath() {
        assertTrue(candidatePaths("pipe:[12345]").isEmpty())
        assertTrue(candidatePaths("socket:[678]").isEmpty())
        assertTrue(candidatePaths("anon_inode:[memfd]").isEmpty())
        assertTrue(candidatePaths("/storage/emulated/0/Download/gone.pdf (deleted)").isEmpty())
    }
}
