package com.marcogn.pdftoolkit.di

import com.marcogn.pdftoolkit.data.files.AllFilesAccess
import com.marcogn.pdftoolkit.data.files.AndroidAllFilesAccess
import com.marcogn.pdftoolkit.data.files.FilePathResolver
import com.marcogn.pdftoolkit.data.files.ProcFdFilePathResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FilesModule {

    @Binds
    abstract fun bindAllFilesAccess(access: AndroidAllFilesAccess): AllFilesAccess

    @Binds
    abstract fun bindFilePathResolver(resolver: ProcFdFilePathResolver): FilePathResolver
}
