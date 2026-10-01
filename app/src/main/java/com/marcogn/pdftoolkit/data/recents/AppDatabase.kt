package com.marcogn.pdftoolkit.data.recents

import androidx.room.Database
import androidx.room.RoomDatabase

/** The app's Room database. `Signature` (spec §8) joins in phase 4, with a migration. */
@Database(entities = [RecentDocument::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao
}
