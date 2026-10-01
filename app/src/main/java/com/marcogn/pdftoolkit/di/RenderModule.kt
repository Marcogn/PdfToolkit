package com.marcogn.pdftoolkit.di

import android.app.ActivityManager
import android.content.Context
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RenderModule {

    /** Bitmap cache sizes from the device heap class (spec §5). */
    @Provides
    @Singleton
    fun provideRenderBudget(@ApplicationContext context: Context): RenderBudget {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return RenderBudget.forMemoryClass(activityManager.memoryClass)
    }
}
