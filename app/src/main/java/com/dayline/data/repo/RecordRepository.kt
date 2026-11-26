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

package com.dayline.data.repo

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.dayline.backup.Fingerprint
import com.dayline.data.db.AppDatabase
import com.dayline.data.db.RecordEntity
import com.dayline.data.db.TagEntity
import com.dayline.data.media.MediaStore
import com.dayline.domain.TimelineItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class RecordRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val media: MediaStore
) {
    private val recordDao = db.recordDao()
    private val tagDao = db.tagDao()
    private val mediaDao = db.mediaDao()

    /**
     * 自写的 PagingSource 不会像 Room 生成的那样随表变化自动失效，
     * 所以用 InvalidatingPagingSourceFactory + Room InvalidationTracker：
     * records / record_tag / tags 任一表有写入（记一笔、删除、恢复、导入），立刻让当前分页源失效重载。
     */
    /** 当前标签过滤（null = 全部）。改变后让分页源失效，按新条件重载 */
    private val _tagFilter = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val tagFilter: kotlinx.coroutines.flow.StateFlow<String?> = _tagFilter

    fun setTagFilter(tag: String?) {
        if (_tagFilter.value == tag) return
        _tagFilter.value = tag
        timelineSourceFactory.invalidate()
    }

    private val timelineSourceFactory =
        androidx.paging.InvalidatingPagingSourceFactory {
            TimelinePagingSource(recordDao, tagFilter = _tagFilter.value)
        }

    private val tableObserver = object : androidx.room.InvalidationTracker.Observer(
        arrayOf("records", "record_tag", "tags")
    ) {
        override fun onInvalidated(tables: Set<String>) {
            timelineSourceFactory.invalidate()
        }
    }

    init {
        db.invalidationTracker.addObserver(tableObserver)
    }

    val timeline: Flow<PagingData<TimelineItem>> = Pager(
        config = PagingConfig(
            pageSize = TimelinePagingSource.DEFAULT_DAYS_PER_PAGE,
            // 预加载：自己往前多取 2 页（2 x 7 天），配合 UI 侧 600px 触底提前 load
            prefetchDistance = 2 * TimelinePagingSource.DEFAULT_DAYS_PER_PAGE,
            enablePlaceholders = false,
            initialLoadSize = TimelinePagingSource.DEFAULT_DAYS_PER_PAGE
        ),
        pagingSourceFactory = timelineSourceFactory
    ).flow

    val trash: Flow<List<com.dayline.data.db.RecordWithTags>> = recordDao.observeTrash()
    val allTags: Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun load(id: String) = recordDao.findById(id)

    /**
     * 保存一条记录。
     *
     * 分两步走，**事务里只做纯数据库写入**：
     * 1) 事务外：把老路径的 sha256 全部算好（可能要读整个视频文件，1GB 视频几百毫秒到几秒）；
     * 2) 事务内：登记 media_files + 写 records/tags。
     *
     * 取舍说明：之前把 sha 计算放在 `withTransaction {}` 里，等于整个哈希计算期间都持有 SQLite
     * 写锁——用户保存一个带大视频的记录时，后台的过期清理、导入落库全被卡住，甚至 ANR。
     * 哈希是只读计算，没有任何理由占用事务。
     */
    suspend fun save(draft: RecordDraft): SaveOutcome {
        val now = System.currentTimeMillis()

        // ---------- 1) 事务外：解析所有媒体的 sha256 ----------
        val allPaths = draft.imagePaths + draft.videoPaths + draft.videoCoverPaths
        val shas = LinkedHashSet<String>(draft.newFiles.map { it.sha256 })
        val resolvedFiles = ArrayList<com.dayline.data.db.MediaFileEntity>(allPaths.size)
        for (path in allPaths) {
            if (draft.newFiles.any { it.relativePath == path }) continue
            val known = mediaDao.findByPath(path)?.sha256
            if (known != null) {
                shas += known
                continue
            }
            val file = media.resolve(path)
            if (!file.isFile) continue
            val sha = media.sha256Of(file)          // ← 唯一的重 IO，已在事务外
            shas += sha
            resolvedFiles += com.dayline.data.db.MediaFileEntity(
                sha256 = sha,
                relativePath = path,
                kind = com.dayline.backup.Naming.kindOf(path).name.lowercase(),
                sizeBytes = file.length(),
                createdAt = now
            )
        }

        val fingerprint = Fingerprint.of(
            content = draft.content,
            side = draft.side,
            recordDate = draft.recordDate,
            tagNames = draft.tagNames,
            mediaShas = shas
        )

        // ---------- 2) 事务内：只做数据库写入 ----------
        return db.withTransaction {
            val existing = recordDao.findEntityById(draft.id)

            // 本次新落盘的文件（sha256 主键，天然去重）
            for (file in draft.newFiles) {
                mediaDao.insert(
                    com.dayline.data.db.MediaFileEntity(
                        sha256 = file.sha256,
                        relativePath = file.relativePath,
                        kind = file.kind.name.lowercase(),
                        sizeBytes = file.sizeBytes,
                        createdAt = now
                    )
                )
            }
            // 事务外补算出哈希的老媒体
            for (entity in resolvedFiles) mediaDao.insert(entity)

        // 标签按 name 复用
        val tagIds = ArrayList<String>(draft.tagNames.size)
        for (name in draft.tagNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val found = tagDao.findByName(name)
            if (found != null) {
                tagIds += found.id
            } else {
                val id = UUID.randomUUID().toString()
                tagDao.insert(TagEntity(id, name, now))
                tagIds += id
            }
        }

        val entity = RecordEntity(
            id = draft.id,
            content = draft.content,
            side = draft.side,
            recordDate = draft.recordDate,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            isDeleted = false,
            deletedAt = null,
            imagePaths = draft.imagePaths,
            videoPaths = draft.videoPaths,
            videoCoverPaths = draft.videoCoverPaths,
            contentFingerprint = fingerprint
        )
        recordDao.upsert(entity)
        recordDao.clearTagRefs(entity.id)
        if (tagIds.isNotEmpty()) {
            recordDao.insertTagRefs(tagIds.map { com.dayline.data.db.RecordTagCrossRef(entity.id, it) })
        }
        tagDao.pruneOrphans()

        SaveOutcome(id = entity.id, isNew = existing == null)
        }
    }

    /**
     * 软删除：进回收站，可在 SnackBar 里撤销。
     */
    suspend fun softDelete(id: String) {
        db.withTransaction { recordDao.markDeleted(id, System.currentTimeMillis()) }
    }

    /**
     * 恢复：指纹需要媒体 sha256。
     * 与 [save] 同样的取舍：先在**事务外**解析 sha（优先查 media_files 索引，查不到才现算哈希），
     * 事务里只做一次 UPDATE，避免读大视频期间长时间持有写锁。
     */
    suspend fun restore(id: String) {
        val item = recordDao.findById(id) ?: return
        val entity = item.record
        val shas = (entity.imagePaths + entity.videoPaths + entity.videoCoverPaths)
            .mapNotNull { shaOf(it) }
        val fp = Fingerprint.of(
            content = entity.content,
            side = entity.side,
            recordDate = entity.recordDate,
            tagNames = item.tagNames,
            mediaShas = shas
        )
        db.withTransaction { recordDao.markRestored(id, System.currentTimeMillis(), fp) }
    }

    /** 彻底删除：删记录 + 清理不再被引用的媒体文件 */
    suspend fun purge(id: String) {
        val orphans = db.withTransaction {
            val entity = recordDao.findEntityById(id) ?: return@withTransaction emptyList<String>()
            recordDao.hardDelete(id)
            tagDao.pruneOrphans()
            unindexOrphans(entity.imagePaths + entity.videoPaths + entity.videoCoverPaths)
        }
        deleteFiles(orphans)
    }

    suspend fun purgeAllTrash() {
        val orphans = db.withTransaction {
            val trashed = recordDao.trashedRecords()
            val paths = trashed.flatMap { it.imagePaths + it.videoPaths + it.videoCoverPaths }
            trashed.forEach { recordDao.hardDelete(it.id) }
            tagDao.pruneOrphans()
            unindexOrphans(paths)
        }
        deleteFiles(orphans)
    }

    /**
     * 在事务内：找出不再被任何记录引用的候选路径，并从 media_files 索引里移除。
     * 返回需要删除的文件路径——**真正删文件放到事务提交之后**：
     * 若事务回滚，文件还在，不会出现「库里有记录、磁盘上文件没了」的不一致。
     *
     * 只查三列路径（[RecordDao.allMediaRefs]），不把正文 / 指纹等整行读进内存。
     */
    private suspend fun unindexOrphans(candidatePaths: List<String>): List<String> {
        if (candidatePaths.isEmpty()) return emptyList()
        val stillUsed = HashSet<String>()
        for (ref in recordDao.allMediaRefs()) {
            stillUsed += ref.imagePaths
            stillUsed += ref.videoPaths
            stillUsed += ref.videoCoverPaths
        }
        val orphans = candidatePaths.distinct().filter { it !in stillUsed }
        for (path in orphans) {
            mediaDao.findByPath(path)?.let { mediaDao.delete(it.sha256) }
        }
        return orphans
    }

    private suspend fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (path in paths) runCatching { media.resolve(path).delete() }
        }
    }

    /** 路径 → sha256：优先查索引（O(1) 查询），没登记过的老数据才读文件现算 */
    private suspend fun shaOf(path: String): String? =
        mediaDao.findByPath(path)?.sha256
            ?: runCatching { media.resolve(path).takeIf { it.isFile }?.let { media.sha256Of(it) } }.getOrNull()

    /** 回收站过期清理：默认 30 天 */
    suspend fun purgeExpired(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600_000L
        val orphans = db.withTransaction {
            val expired = recordDao.trashedRecords().filter { (it.deletedAt ?: 0L) < cutoff }
            if (expired.isEmpty()) return@withTransaction emptyList<String>()
            val paths = expired.flatMap { it.imagePaths + it.videoPaths + it.videoCoverPaths }
            expired.forEach { recordDao.hardDelete(it.id) }
            tagDao.pruneOrphans()
            unindexOrphans(paths)
        }
        deleteFiles(orphans)
    }

    data class SaveOutcome(val id: String, val isNew: Boolean)
}

/**
 * 编辑弹窗提交的载荷。
 * newFiles 只放"本次新落盘的"文件（用于登记 sha256 索引与计算内容指纹）；
 * 其余路径由 Repository 自行查索引或现算哈希。
 */
data class RecordDraft(
    val id: String,
    val content: String,
    val side: String,
    val recordDate: String,
    val tagNames: List<String>,
    val imagePaths: List<String>,
    val videoPaths: List<String>,
    val videoCoverPaths: List<String>,
    val newFiles: List<MediaStore.ImportedFile> = emptyList()
) {
    companion object {
        fun empty(date: String): RecordDraft = RecordDraft(
            id = UUID.randomUUID().toString(),
            content = "",
            side = "beneficial",
            recordDate = date,
            tagNames = emptyList(),
            imagePaths = emptyList(),
            videoPaths = emptyList(),
            videoCoverPaths = emptyList()
        )
    }
}
