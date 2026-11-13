package com.dayline.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_files WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE relativePath = :path LIMIT 1")
    suspend fun findByPath(path: String): MediaFileEntity?

    @Query("SELECT * FROM media_files")
    suspend fun all(): List<MediaFileEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: MediaFileEntity)

    @Query("DELETE FROM media_files WHERE sha256 = :sha256")
    suspend fun delete(sha256: String)

    /** 找出没有被任何记录引用的媒体，供回收站彻底删除后清理 */
    @Query("SELECT * FROM media_files")
    suspend fun allForGc(): List<MediaFileEntity>
}
