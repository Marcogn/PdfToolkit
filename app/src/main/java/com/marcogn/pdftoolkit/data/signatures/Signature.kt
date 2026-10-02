package com.marcogn.pdftoolkit.data.signatures

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.marcogn.pdftoolkit.domain.signature.SignatureType

/**
 * A saved signature (spec §8). The picture is a PNG with transparency in `filesDir/signatures/`,
 * named [fileName]; the name is kept apart from the path so the folder can move without a migration.
 */
@Entity(tableName = "signatures")
data class Signature(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SignatureType,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
    val isDefault: Boolean = false,
)
