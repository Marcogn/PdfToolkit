package com.marcogn.pdftoolkit.di

import com.marcogn.pdftoolkit.pdf.edit.AndroidPageImageLoader
import com.marcogn.pdftoolkit.pdf.edit.PageImageLoader
import com.marcogn.pdftoolkit.pdf.edit.PdfBoxEditor
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
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
}
