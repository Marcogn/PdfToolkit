package com.marcogn.pdftoolkit.data.recents

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {

    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    suspend fun find(uri: String): RecentDocument?

    @Upsert
    suspend fun upsert(document: RecentDocument)

    @Query("UPDATE recent_documents SET lastPage = :page WHERE uri = :uri")
    suspend fun updateLastPage(uri: String, page: Int)

    @Query("UPDATE recent_documents SET thumbnailPath = :path WHERE uri = :uri")
    suspend fun updateThumbnail(uri: String, path: String?)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_documents")
    suspend fun deleteAll()

    /** Everything older than the [keep] most recent entries. */
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC LIMIT -1 OFFSET :keep")
    suspend fun olderThan(keep: Int): List<RecentDocument>
}
