package com.marcogn.pdftoolkit.di

import com.marcogn.pdftoolkit.pdf.text.PdfBoxTextExtractor
import com.marcogn.pdftoolkit.pdf.text.PdfTextExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TextModule {

    @Binds
    abstract fun bindPdfTextExtractor(extractor: PdfBoxTextExtractor): PdfTextExtractor
}
