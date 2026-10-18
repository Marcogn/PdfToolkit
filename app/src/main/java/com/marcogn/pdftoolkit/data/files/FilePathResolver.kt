package com.marcogn.pdftoolkit.data.files

import android.content.Context
import android.net.Uri
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
}

/**
 * Android has no API for this: a `FileProvider` URI is opaque. The descriptor the provider opens still
 * points at the file, so its link in `/proc/self/fd` names it; a path is kept only if a stat of it finds
 * the file the descriptor has open.
 */
@Singleton
class ProcFdFilePathResolver @Inject constructor(@ApplicationContext private val context: Context) : FilePathResolver {
    override fun resolve(uri: Uri): File? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val opened = Os.fstat(descriptor.fileDescriptor)
            val link = Os.readlink("/proc/self/fd/${descriptor.fd}")
            candidatePaths(link).map(::File).firstOrNull { isSameFile(it, opened) }
        }
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

private val MNT_USER = Regex("^/mnt/(?:user|pass_through)/\\d+/(.+)$")
private val DATA_MEDIA = Regex("^/data/media/(\\d+)/(.+)$")
private val SDCARD = Regex("^/sdcard/(.+)$")

/**
 * The paths a descriptor's link [target] may be reached by from this app, most likely first. The link
 * is the provider's view: `/storage/emulated/0/…`, or a mount behind it (`/mnt/user/0/…`,
 * `/data/media/0/…`). Empty for what isn't a file (`pipe:[…]`, `socket:[…]`, `anon_inode:…`) or a file
 * deleted since it was opened.
 */
fun candidatePaths(target: String): List<String> {
    if (!target.startsWith("/") || target.endsWith(" (deleted)")) return emptyList()
    val paths = mutableListOf(target)
    MNT_USER.matchEntire(target)?.let { paths += "/storage/${it.groupValues[1]}" }
    DATA_MEDIA.matchEntire(target)?.let { paths += "/storage/emulated/${it.groupValues[1]}/${it.groupValues[2]}" }
    SDCARD.matchEntire(target)?.let { paths += "/storage/emulated/0/${it.groupValues[1]}" }
    return paths.distinct()
}
