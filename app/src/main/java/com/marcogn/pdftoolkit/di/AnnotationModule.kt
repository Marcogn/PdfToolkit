package com.marcogn.pdftoolkit.di

import com.marcogn.pdftoolkit.pdf.annotations.AnnotationReader
import com.marcogn.pdftoolkit.pdf.annotations.PdfBoxAnnotationReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AnnotationModule {

    @Binds
    abstract fun bindAnnotationReader(reader: PdfBoxAnnotationReader): AnnotationReader
}
