/*
 * Copyright 2026 The DayLine Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dayline.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    // ---------- 时间轴分页：先按天分页，再取该页内所有记录 ----------

    @Query(
        """
        SELECT DISTINCT recordDate FROM records
        WHERE isDeleted = 0
        ORDER BY recordDate DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun pageDates(limit: Int, offset: Int): List<String>

    @Transaction
    @Query(
        """
        SELECT * FROM records
        WHERE isDeleted = 0 AND recordDate IN (:dates)
        ORDER BY recordDate DESC, createdAt ASC, id ASC
        """
    )
    suspend fun recordsForDates(dates: List<String>): List<RecordWithTags>

    // ---------- 标签过滤版分页：只看带某个标签的记录 ----------

    @Query(
        """
        SELECT DISTINCT r.recordDate FROM records r
        JOIN record_tag rt ON rt.recordId = r.id
        JOIN tags t ON t.id = rt.tagId
        WHERE r.isDeleted = 0 AND t.name = :tag
        ORDER BY r.recordDate DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun pageDatesByTag(tag: String, limit: Int, offset: Int): List<String>

    @Transaction
    @Query(
        """
        SELECT * FROM records
        WHERE isDeleted = 0 AND recordDate IN (:dates)
          AND id IN (SELECT rt.recordId FROM record_tag rt JOIN tags t ON t.id = rt.tagId WHERE t.name = :tag)
        ORDER BY recordDate DESC, createdAt ASC, id ASC
        """
    )
    suspend fun recordsForDatesByTag(dates: List<String>, tag: String): List<RecordWithTags>

    // ---------- 单条 ----------

    @Transaction
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun findById(id: String): RecordWithTags?

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun findEntityById(id: String): RecordEntity?

    @Query("SELECT id FROM records WHERE isDeleted = 0 AND contentFingerprint = :fingerprint LIMIT 1")
    suspend fun findIdByFingerprint(fingerprint: String): String?

    // ---------- 回收站 ----------

    @Transaction
    @Query("SELECT * FROM records WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<RecordWithTags>>

    @Query("SELECT * FROM records WHERE isDeleted = 1")
    suspend fun trashedRecords(): List<RecordEntity>

    // ---------- 写 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<RecordEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagRefs(refs: List<RecordTagCrossRef>)

    @Query("DELETE FROM record_tag WHERE recordId = :recordId")
    suspend fun clearTagRefs(recordId: String)

    @Query("UPDATE records SET isDeleted = 1, deletedAt = :at, updatedAt = :at, contentFingerprint = NULL WHERE id = :id")
    suspend fun markDeleted(id: String, at: Long)

    @Query("UPDATE records SET isDeleted = 0, deletedAt = NULL, updatedAt = :at, contentFingerprint = :fingerprint WHERE id = :id")
    suspend fun markRestored(id: String, at: Long, fingerprint: String?)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun hardDelete(id: String)

    // ---------- 全量（导出用） ----------

    @Transaction
    @Query("SELECT * FROM records ORDER BY recordDate ASC, createdAt ASC")
    suspend fun allRecordsWithTags(): List<RecordWithTags>

    @Query("SELECT * FROM records")
    suspend fun allRecords(): List<RecordEntity>

    /** 媒体回收用：只读三列路径 */
    @Query("SELECT imagePaths, videoPaths, videoCoverPaths FROM records")
    suspend fun allMediaRefs(): List<MediaRefs>

    /** 桌面小组件用：按侧别统计某天记录条数 */
    @Query("SELECT side, COUNT(*) AS cnt FROM records WHERE isDeleted = 0 AND recordDate = :date GROUP BY side")
    suspend fun countsForDate(date: String): List<SideCount>

    @Query("SELECT COUNT(*) FROM records WHERE isDeleted = 0")
    suspend fun activeCount(): Int
}
