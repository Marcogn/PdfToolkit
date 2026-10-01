package com.marcogn.pdftoolkit.di

import android.content.Context
import androidx.room.Room
import com.marcogn.pdftoolkit.data.recents.AppDatabase
import com.marcogn.pdftoolkit.data.recents.RecentDocumentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pdftoolkit.db").build()

    @Provides
    fun provideRecentDocumentDao(database: AppDatabase): RecentDocumentDao = database.recentDocumentDao()
}
