package com.cokkles.gpos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CanonicalSnapshotDao {
    @Query("SELECT * FROM canonical_snapshot_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun read(cacheKey: String = CanonicalSnapshotEntity.PRIMARY_CACHE_KEY): CanonicalSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: CanonicalSnapshotEntity)

    @Query("DELETE FROM canonical_snapshot_cache")
    suspend fun clear()
}
