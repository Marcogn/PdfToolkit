package com.marcogn.pdftoolkit.di

import com.marcogn.pdftoolkit.pdf.edit.AndroidPageImageLoader
import com.marcogn.pdftoolkit.pdf.edit.AssetFontSource
import com.marcogn.pdftoolkit.pdf.edit.FontSource
import com.marcogn.pdftoolkit.pdf.edit.PageImageLoader
import com.marcogn.pdftoolkit.pdf.edit.PdfBoxEditor
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import com.marcogn.pdftoolkit.pdf.forms.FormReader
import com.marcogn.pdftoolkit.pdf.forms.PdfBoxFormReader
import com.marcogn.pdftoolkit.pdf.ocr.MlKitTextRecognizerFactory
import com.marcogn.pdftoolkit.pdf.ocr.TextRecognizerFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class EditModule {

    @Binds
    abstract fun bindPdfEditor(editor: PdfBoxEditor): PdfEditor

    @Binds
    abstract fun bindPageImageLoader(loader: AndroidPageImageLoader): PageImageLoader

    @Binds
    abstract fun bindFontSource(source: AssetFontSource): FontSource

    @Binds
    abstract fun bindFormReader(reader: PdfBoxFormReader): FormReader

    @Binds
    abstract fun bindTextRecognizerFactory(factory: MlKitTextRecognizerFactory): TextRecognizerFactory
}
