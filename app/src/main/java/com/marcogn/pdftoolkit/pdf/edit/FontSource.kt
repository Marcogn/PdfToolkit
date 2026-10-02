package com.marcogn.pdftoolkit.pdf.edit

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

/**
 * The font text is written with (spec §6.5): Noto Sans Regular, SIL Open Font License 1.1
 * (`assets/fonts/OFL.txt`), embedded in the PDF so accented letters always come out right. The
 * screen draws text overlays with the same file ([ASSET_PATH]).
 */
interface FontSource {
    fun openRegular(): InputStream

    companion object {
        const val ASSET_PATH = "fonts/NotoSans-Regular.ttf"
    }
}

class AssetFontSource @Inject constructor(@ApplicationContext private val context: Context) : FontSource {
    override fun openRegular(): InputStream = context.assets.open(FontSource.ASSET_PATH)
}
