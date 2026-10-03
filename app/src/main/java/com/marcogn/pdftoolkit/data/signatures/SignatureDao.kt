package com.marcogn.pdftoolkit.data.signatures

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureDao {

    /** The favourite first, then the newest. */
    @Query("SELECT * FROM signatures ORDER BY isDefault DESC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<Signature>>

    @Query("SELECT * FROM signatures WHERE id = :id")
    suspend fun find(id: Long): Signature?

    @Query("SELECT COUNT(*) FROM signatures")
    suspend fun count(): Int

    @Insert
    suspend fun insert(signature: Signature): Long

    @Query("UPDATE signatures SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE signatures SET isDefault = 0 WHERE isDefault = 1")
    suspend fun clearDefault()

    @Query("UPDATE signatures SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    /** At most one favourite: the old one is cleared in the same transaction. */
    @Transaction
    suspend fun setDefault(id: Long, default: Boolean) {
        clearDefault()
        if (default) markDefault(id)
    }
}
