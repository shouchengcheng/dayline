package com.dayline.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RecordEntity::class,
        TagEntity::class,
        RecordTagCrossRef::class,
        MediaFileEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun tagDao(): TagDao
    abstract fun mediaDao(): MediaDao

    companion object {
        const val NAME = "dayline.db"
    }
}
