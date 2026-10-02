package com.marcogn.pdftoolkit.data.signatures

import android.content.Context
import android.graphics.Bitmap
import com.marcogn.pdftoolkit.domain.signature.SignatureType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signature archive (spec §6.5, §8): PNG files with transparency in `filesDir/signatures/`
 * (private to the app, excluded from backup) and their metadata in Room.
 */
@Singleton
class SignatureRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SignatureDao,
) {

    val signatures: Flow<List<Signature>> = dao.observeAll()

    fun file(signature: Signature): File = File(directory(context), signature.fileName)

    /**
     * Saves [bitmap] as a new signature. The first one becomes the favourite. Returns null if the
     * picture can't be written.
     */
    suspend fun save(bitmap: Bitmap, name: String, type: SignatureType, now: Long = System.currentTimeMillis()): Signature? =
        withContext(Dispatchers.IO) {
            val fileName = UUID.randomUUID().toString() + ".png"
            val target = File(directory(context), fileName)
            try {
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) { "PNG compression failed" } }
            } catch (e: IOException) {
                target.delete()
                return@withContext null
            } catch (e: IllegalStateException) {
                target.delete()
                return@withContext null
            }
            val first = dao.count() == 0
            val draft = Signature(
                name = name, type = type, fileName = fileName,
                widthPx = bitmap.width, heightPx = bitmap.height, createdAt = now, isDefault = first,
            )
            draft.copy(id = dao.insert(draft))
        }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name)

    suspend fun setDefault(id: Long, default: Boolean) = dao.setDefault(id, default)

    suspend fun delete(signature: Signature) {
        dao.delete(signature.id)
        withContext(Dispatchers.IO) { file(signature).delete() }
    }

    /** "Firma 3": the next free number, for a signature saved without a name. */
    suspend fun suggestedName(prefix: String): String = "$prefix ${dao.count() + 1}"

    companion object {
        private const val DIRECTORY = "signatures"
        private const val PNG_QUALITY = 100

        /** `filesDir/signatures/`, the folder the backup rules exclude. */
        fun directory(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }
    }
}
