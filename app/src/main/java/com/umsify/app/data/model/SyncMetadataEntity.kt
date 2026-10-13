package com.umsify.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks synchronization status, document hashes, and sync diff stats per user.
 * Matches section 9, 21, and 30.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey
    val id: String,                    // e.g. "$userId:timetable", "$userId:exam", or "$userId:$docHash"
    val userId: String,
    val syncType: String,              // "timetable", "exam", "document"
    val documentHash: String = "",     // SHA-256 of file bytes
    val lastSyncedAt: String = "",
    val insertedCount: Int = 0,
    val updatedCount: Int = 0,
    val deletedCount: Int = 0,
    val unchangedCount: Int = 0
)
