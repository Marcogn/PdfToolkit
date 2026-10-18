package com.marcogn.pdftoolkit.data.files

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the file in shared storage behind a `content://` URI another app handed over (ADR 0007), so
 * the app can reopen it by path once the temporary grant is gone. Null when there is none: a cloud
 * file, a stream, a path the app can't reach.
 */
interface FilePathResolver {
    fun resolve(uri: Uri): File?

    /**
     * Whether [uri] is a file in shared storage at all, which the access would let the app reach by path.
     * Needs no permission: it only reads where the descriptor points.
     */
    fun isInSharedStorage(uri: Uri): Boolean
}

/**
 * Android has no API for this: a `FileProvider` URI is opaque. The descriptor the provider opens still
 * points at the file, so its link in `/proc/self/fd` names it; a path is kept only if a stat of it finds
 * the file the descriptor has open.
 */
@Singleton
class ProcFdFilePathResolver @Inject constructor(@ApplicationContext private val context: Context) : FilePathResolver {
    override fun resolve(uri: Uri): File? = withDescriptor(uri) { descriptor ->
        val opened = Os.fstat(descriptor.fileDescriptor)
        candidatePaths(Os.readlink("/proc/self/fd/${descriptor.fd}")).map(::File).firstOrNull { isSameFile(it, opened) }
    }

    override fun isInSharedStorage(uri: Uri): Boolean = withDescriptor(uri) { descriptor ->
        candidatePaths(Os.readlink("/proc/self/fd/${descriptor.fd}")).any(::isReachableWithAllFilesAccess)
    } ?: false

    private fun <T> withDescriptor(uri: Uri, block: (ParcelFileDescriptor) -> T?): T? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use(block)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: ErrnoException) {
        null
    }

    private fun isSameFile(file: File, opened: StructStat): Boolean = try {
        val found = Os.stat(file.path)
        // FUSE and the file system under it may report different inodes for one file: size and
        // modification time then stand in for it.
        (found.st_dev == opened.st_dev && found.st_ino == opened.st_ino) ||
            (found.st_size == opened.st_size && found.st_mtime == opened.st_mtime)
    } catch (e: ErrnoException) {
        false
    }
}

/** Where shared storage is reached from an app: the internal one under `emulated/<user>`, SD cards by volume id. */
const val SHARED_STORAGE = "/storage/"

/** Other apps' folders, closed to all files access too (Android 11+). */
private val OTHER_APPS_DIRS = Regex("^/storage/[^/]+(?:/\\d+)?/Android/(?:data|obb)/.*")

/** Whether all files access lets the app open [path]: in shared storage, outside other apps' folders. */
fun isReachableWithAllFilesAccess(path: String): Boolean = path.startsWith(SHARED_STORAGE) && !OTHER_APPS_DIRS.matches(path)

private val MNT_USER = Regex("^/mnt/(?:user|pass_through)/\\d+/(.+)$")
private val MNT_RUNTIME = Regex("^/mnt/runtime/(?:default|read|write|full)/(.+)$")
private val MNT_MEDIA_RW = Regex("^/mnt/media_rw/(.+)$")
private val DATA_MEDIA = Regex("^/data/media/(\\d+)/(.+)$")
private val SDCARD = Regex("^/sdcard/(.+)$")

/**
 * The paths a descriptor's link [target] may be reached by from this app, `/storage/…` forms first (the
 * FileProvider root, so the file can be shared), the link itself last. The link is the provider's view:
 * `/storage/emulated/0/…`, or a mount behind it (`/mnt/user/0/…`, `/mnt/runtime/write/…` on Android 8–9,
 * `/mnt/media_rw/<volume>/…` for an SD card, `/data/media/0/…`). Empty for what isn't a file (`pipe:[…]`,
 * `socket:[…]`, `anon_inode:…`) or a file deleted since it was opened.
 */
fun candidatePaths(target: String): List<String> {
    if (!target.startsWith("/") || target.endsWith(" (deleted)")) return emptyList()
    val paths = mutableListOf<String>()
    MNT_USER.matchEntire(target)?.let { paths += SHARED_STORAGE + it.groupValues[1] }
    MNT_RUNTIME.matchEntire(target)?.let { paths += SHARED_STORAGE + it.groupValues[1] }
    MNT_MEDIA_RW.matchEntire(target)?.let { paths += SHARED_STORAGE + it.groupValues[1] }
    DATA_MEDIA.matchEntire(target)?.let { paths += "${SHARED_STORAGE}emulated/${it.groupValues[1]}/${it.groupValues[2]}" }
    SDCARD.matchEntire(target)?.let { paths += "${SHARED_STORAGE}emulated/0/${it.groupValues[1]}" }
    paths += target
    return paths.distinct()
}
