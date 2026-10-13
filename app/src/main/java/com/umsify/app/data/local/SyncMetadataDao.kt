package com.umsify.app.data.local

import androidx.room.*
import com.umsify.app.data.model.SyncMetadataEntity

@Dao
interface SyncMetadataDao {

    @Query("SELECT * FROM sync_metadata WHERE userId = :userId AND syncType = :syncType LIMIT 1")
    suspend fun getMetadata(userId: String, syncType: String): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE userId = :userId AND documentHash = :documentHash LIMIT 1")
    suspend fun getByDocumentHash(userId: String, documentHash: String): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: SyncMetadataEntity)

    @Query("DELETE FROM sync_metadata WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
