package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.PageSizing
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.domain.edit.SizePt
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * [PdfEditor] on PdfBox-Android (ADR 0002).
 *
 * The open document itself is rearranged and saved (rather than copying pages into a new one), so
 * everything a page needs (fonts, images, annotations, form fields) stays as it was.
 */
class PdfBoxEditor @Inject constructor(private val images: PageImageLoader) : PdfEditor {

    override suspend fun applySession(
        session: EditSession,
        sources: Map<DocRef, File>,
        output: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val file = sources[DocRef.MAIN] ?: throw SaveException(SaveFailure.FAILED)
        // Other PDFs must stay open until the result is saved: imported pages still read their streams.
        val others = mutableMapOf<DocRef, PDDocument>()
        try {
            onProgress(0f)
            load(file).use { document ->
                for (ref in session.extraDocuments) {
                    val extra = sources[ref] ?: throw SaveException(SaveFailure.FAILED)
                    others[ref] = load(extra)
                }
                onProgress(LOADED)
                rearrange(document, session, others)
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
        } finally {
            others.values.forEach { runCatching { it.close() } }
        }
    }

    override suspend fun hasFormFields(open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = open() ?: return@withContext false
            stream.use {
                PDDocument.load(it, MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)).use { document ->
                    document.documentCatalog.acroForm?.fields?.isNotEmpty() == true
                }
            }
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun load(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES))

    /** Detaches every page, then puts back the ones the session keeps, in its order and rotation. */
    private fun rearrange(document: PDDocument, session: EditSession, others: Map<DocRef, PDDocument>) {
        val tree = document.pages
        val originals = List(tree.count) { tree.get(it) }
        // A page can inherit these from a parent /Pages node; once detached it would lose them.
        originals.forEach { materializeInheritedAttributes(it) }

        // Images are decoded one at a time, while their page is built, so only one bitmap is alive.
        // PDPageTree.remove(int) keeps the /Count of the ancestors right.
        for (index in tree.count - 1 downTo 0) tree.remove(index)
        session.pages.forEach { item ->
            val page = when (item) {
                is PageItem.FromPdf -> {
                    if (item.docRef == DocRef.MAIN) {
                        val original = originals.getOrNull(item.pageIndex) ?: throw IOException("No page ${item.pageIndex}")
                        document.addPage(original)
                        original
                    } else {
                        val source = others[item.docRef] ?: throw IOException("Document ${item.docRef.id} not open")
                        // importPage appends and copies inherited attributes into the page.
                        document.importPage(source.getPage(item.pageIndex))
                    }
                }
                is PageItem.Blank -> PDPage(PDRectangle(item.widthPt, item.heightPt)).also { document.addPage(it) }
                is PageItem.FromImage -> imagePage(document, item).also { document.addPage(it) }
            }
            if (item.rotation != 0) page.rotation = Math.floorMod(page.rotation + item.rotation, FULL_TURN)
        }
    }

    private fun imagePage(document: PDDocument, item: PageItem.FromImage): PDPage {
        val pageSize = SizePt(item.widthPt, item.heightPt)
        val page = PDPage(PDRectangle(pageSize.width, pageSize.height))
        val probe = images.probe(item.imageUri) ?: throw IOException("Can't read ${item.imageUri}")
        val (width, height) = PageSizing.decodeSize(probe, item.mode)
        val loaded = images.load(item.imageUri, width, height)
        try {
            val image = if (loaded.hasAlpha) {
                LosslessFactory.createFromImage(document, loaded.bitmap)
            } else {
                JPEGFactory.createFromImage(document, loaded.bitmap, JPEG_QUALITY)
            }
            // Placement uses the pixels actually decoded, so a size mismatch can never stretch the image.
            val box = PageSizing.placement(ImageDimensions(loaded.bitmap.width, loaded.bitmap.height), pageSize)
            PDPageContentStream(document, page).use { it.drawImage(image, box.x, box.y, box.width, box.height) }
        } finally {
            loaded.bitmap.recycle()
        }
        return page
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
        /** Spec §6.2: JPEG quality 85 for photos. */
        const val JPEG_QUALITY = 0.85f
        const val LOADED = 0.15f
        const val REARRANGED = 0.4f
        const val SAVED = 0.9f
        val INHERITABLE = listOf(COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.RESOURCES, COSName.ROTATE)
    }
}
