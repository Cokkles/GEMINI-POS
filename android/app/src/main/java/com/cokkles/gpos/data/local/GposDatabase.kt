package com.cokkles.gpos.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CanonicalSnapshotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GposDatabase : RoomDatabase() {
    abstract fun canonicalSnapshotDao(): CanonicalSnapshotDao
}
