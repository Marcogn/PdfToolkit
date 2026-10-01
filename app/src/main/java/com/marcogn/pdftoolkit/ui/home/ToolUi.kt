package com.marcogn.pdftoolkit.ui.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.PdfTool

@StringRes
fun PdfTool.labelRes(): Int = when (this) {
    PdfTool.MERGE -> R.string.tool_merge
    PdfTool.ADD_PAGES -> R.string.tool_add_pages
    PdfTool.INSERT_IMAGES -> R.string.tool_insert_images
    PdfTool.REMOVE_PAGES -> R.string.tool_remove_pages
    PdfTool.REORDER_PAGES -> R.string.tool_reorder_pages
    PdfTool.FILL_AND_SIGN -> R.string.tool_fill_and_sign
    PdfTool.MY_SIGNATURES -> R.string.tool_my_signatures
    PdfTool.SCAN -> R.string.tool_scan
    PdfTool.CLOUD_UPLOAD -> R.string.tool_cloud_upload
    PdfTool.HIGHLIGHT -> R.string.tool_highlight
    PdfTool.DRAW -> R.string.tool_draw
    PdfTool.EXPORT_ODF -> R.string.tool_export_odf
}

fun PdfTool.icon(): ImageVector = when (this) {
    PdfTool.MERGE -> Icons.Outlined.Merge
    PdfTool.ADD_PAGES -> Icons.AutoMirrored.Outlined.NoteAdd
    PdfTool.INSERT_IMAGES -> Icons.Outlined.AddPhotoAlternate
    PdfTool.REMOVE_PAGES -> Icons.Outlined.Delete
    PdfTool.REORDER_PAGES -> Icons.Outlined.SwapVert
    PdfTool.FILL_AND_SIGN -> Icons.Outlined.EditNote
    PdfTool.MY_SIGNATURES -> Icons.Outlined.Draw
    PdfTool.SCAN -> Icons.Outlined.DocumentScanner
    PdfTool.CLOUD_UPLOAD -> Icons.Outlined.CloudUpload
    PdfTool.HIGHLIGHT -> Icons.Outlined.BorderColor
    PdfTool.DRAW -> Icons.Outlined.Gesture
    PdfTool.EXPORT_ODF -> Icons.Outlined.Description
}

/** Le azioni di firma usano l'accento ambra (colore terziario), SPEC §9. */
val PdfTool.isSignatureAction: Boolean
    get() = this == PdfTool.FILL_AND_SIGN || this == PdfTool.MY_SIGNATURES
