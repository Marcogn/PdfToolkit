package com.marcogn.pdftoolkit.data.recents

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun version2RecentsKeepTheirDataAndCountAsPersisted() = runBlocking {
        // The version 2 tables as in app/schemas/.../2.json.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recent_documents` (`uri` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`sizeBytes` INTEGER, `pageCount` INTEGER NOT NULL, `lastPage` INTEGER NOT NULL, " +
                    "`lastOpenedAt` INTEGER NOT NULL, `thumbnailPath` TEXT, PRIMARY KEY(`uri`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `signatures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `fileName` TEXT NOT NULL, `widthPx` INTEGER NOT NULL, " +
                    "`heightPx` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL)",
            )
            db.execSQL("INSERT INTO recent_documents VALUES ('content://docs/a.pdf', 'a.pdf', 10, 3, 2, 5, NULL)")
            db.version = 2
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val migrated = database.recentDocumentDao().find("content://docs/a.pdf")!!
            assertEquals("a.pdf", migrated.displayName)
            assertEquals(2, migrated.lastPage)
            assertFalse(migrated.temporaryAccess)
        } finally {
            database.close()
        }
    }
}
