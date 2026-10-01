package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * [PdfEditor] on PdfBox-Android (ADR 0002).
 *
 * The open document itself is rearranged and saved (rather than copying pages into a new one), so
 * everything a page needs (fonts, images, annotations, form fields) stays as it was.
 */
class PdfBoxEditor @Inject constructor() : PdfEditor {

    override suspend fun applySession(
        session: EditSession,
        sources: Map<DocRef, File>,
        output: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val file = sources[DocRef.MAIN] ?: throw SaveException(SaveFailure.FAILED)
        if (session.pages.any { it.docRef() != DocRef.MAIN }) {
            // Pages from other PDFs arrive with phase 3.
            throw SaveException(SaveFailure.FAILED, UnsupportedOperationException("Only the main document"))
        }
        try {
            onProgress(0f)
            load(file).use { document ->
                onProgress(LOADED)
                rearrange(document, session)
                onProgress(REARRANGED)
                document.save(output)
            }
            onProgress(SAVED)
            // The file we hand back must be a readable PDF with the pages we expect.
            load(output).use { check ->
                if (check.numberOfPages != session.pageCount) {
                    throw IOException("Expected ${session.pageCount} pages, found ${check.numberOfPages}")
                }
            }
            onProgress(1f)
        } catch (e: SaveException) {
            throw e
        } catch (e: InvalidPasswordException) {
            throw SaveException(SaveFailure.PROTECTED, e)
        } catch (e: OutOfMemoryError) {
            throw SaveException(SaveFailure.OUT_OF_MEMORY, e)
        } catch (e: IOException) {
            throw SaveException(SaveFailure.FAILED, e)
        } catch (e: RuntimeException) {
            throw SaveException(SaveFailure.FAILED, e)
        }
    }

    private fun load(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES))

    private fun PageItem.docRef(): DocRef = when (this) {
        is PageItem.FromPdf -> docRef
    }

    /** Detaches every page, then puts back the ones the session keeps, in its order and rotation. */
    private fun rearrange(document: PDDocument, session: EditSession) {
        val tree = document.pages
        val originals = List(tree.count) { tree.get(it) }
        // A page can inherit these from a parent /Pages node; once detached it would lose them.
        originals.forEach { materializeInheritedAttributes(it) }

        val items = session.pages.map { item ->
            item as PageItem.FromPdf
            val page = originals.getOrNull(item.pageIndex) ?: throw IOException("No page ${item.pageIndex}")
            page to item.rotation
        }
        // PDPageTree.remove(int) keeps the /Count of the ancestors right.
        for (index in tree.count - 1 downTo 0) tree.remove(index)
        items.forEach { (page, extraRotation) ->
            if (extraRotation != 0) page.rotation = Math.floorMod(page.rotation + extraRotation, FULL_TURN)
            document.addPage(page)
        }
    }

    private fun materializeInheritedAttributes(page: PDPage) {
        val dictionary = page.cosObject
        for (key in INHERITABLE) {
            if (dictionary.containsKey(key)) continue
            PDPageTree.getInheritableAttribute(dictionary, key)?.let { dictionary.setItem(key, it) }
        }
    }

    private companion object {
        const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024
        const val FULL_TURN = 360
        const val LOADED = 0.15f
        const val REARRANGED = 0.4f
        const val SAVED = 0.9f
        val INHERITABLE = listOf(COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.RESOURCES, COSName.ROTATE)
    }
}
