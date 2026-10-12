package com.marcogn.pdftoolkit.di

import com.marcogn.pdftoolkit.data.update.FileWriteGate
import com.marcogn.pdftoolkit.data.update.WorkManagerFileWriteGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    abstract fun bindFileWriteGate(gate: WorkManagerFileWriteGate): FileWriteGate
}
