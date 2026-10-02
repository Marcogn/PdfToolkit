package com.marcogn.pdftoolkit.data.recents

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.marcogn.pdftoolkit.data.signatures.Signature
import com.marcogn.pdftoolkit.data.signatures.SignatureDao

/** The app's Room database: recent documents (version 1) and the signature archive (version 2). */
@Database(entities = [RecentDocument::class, Signature::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao
    abstract fun signatureDao(): SignatureDao

    companion object {
        /** Adds the `signatures` table (phase 4b). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signatures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `fileName` TEXT NOT NULL, `widthPx` INTEGER NOT NULL, " +
                        "`heightPx` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL)",
                )
            }
        }
    }
}
