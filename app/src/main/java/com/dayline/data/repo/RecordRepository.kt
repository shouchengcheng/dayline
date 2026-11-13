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

    val timeline: Flow<PagingData<TimelineItem>> = Pager(
        config = PagingConfig(
            pageSize = TimelinePagingSource.DEFAULT_DAYS_PER_PAGE,
            // 预加载：自己往前多取 2 页（2 x 7 天），配合 UI 侧 600px 触底提前 load
            prefetchDistance = 2 * TimelinePagingSource.DEFAULT_DAYS_PER_PAGE,
            enablePlaceholders = false,
            initialLoadSize = TimelinePagingSource.DEFAULT_DAYS_PER_PAGE
        ),
        pagingSourceFactory = { TimelinePagingSource(recordDao) }
    ).flow

    val trash: Flow<List<com.dayline.data.db.RecordWithTags>> = recordDao.observeTrash()
    val allTags: Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun load(id: String) = recordDao.findById(id)

    suspend fun save(draft: RecordDraft): SaveOutcome = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = recordDao.findEntityById(draft.id)

        // 1) 本次新落盘的文件登记进 media_files（sha256 主键，天然去重）
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

        // 2) 老路径也要能算指纹：先查索引，查不到就现算一次并补登记
        val allPaths = draft.imagePaths + draft.videoPaths + draft.videoCoverPaths
        val shas = LinkedHashSet<String>(draft.newFiles.map { it.sha256 })
        for (path in allPaths) {
            if (draft.newFiles.any { it.relativePath == path }) continue
            val known = mediaDao.findByPath(path)?.sha256
            if (known != null) {
                shas += known
            } else {
                val file = media.resolve(path)
                if (file.isFile) {
                    val sha = media.sha256Of(file)
                    shas += sha
                    mediaDao.insert(
                        com.dayline.data.db.MediaFileEntity(
                            sha256 = sha,
                            relativePath = path,
                            kind = com.dayline.backup.Naming.kindOf(path).name.lowercase(),
                            sizeBytes = file.length(),
                            createdAt = now
                        )
                    )
                }
            }
        }

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

        val fingerprint = Fingerprint.of(
            content = draft.content,
            side = draft.side,
            recordDate = draft.recordDate,
            tagNames = draft.tagNames,
            mediaShas = shas
        )

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

    /**
     * 软删除：进回收站，可在 SnackBar 里撤销。
     */
    suspend fun softDelete(id: String) {
        db.withTransaction { recordDao.markDeleted(id, System.currentTimeMillis()) }
    }

    suspend fun restore(id: String) {
        db.withTransaction {
            val entity = recordDao.findEntityById(id) ?: return@withTransaction
            val fp = Fingerprint.of(
                content = entity.content,
                side = entity.side,
                recordDate = entity.recordDate,
                tagNames = recordDao.findById(id)?.tagNames ?: emptyList(),
                mediaShas = (entity.imagePaths + entity.videoPaths + entity.videoCoverPaths)
                    .mapNotNull { runCatching { media.sha256Of(media.resolve(it)) }.getOrNull() }
            )
            recordDao.markRestored(id, System.currentTimeMillis(), fp)
        }
    }

    /** 彻底删除：删记录 + 清理不再被引用的媒体文件 */
    suspend fun purge(id: String) {
        db.withTransaction {
            val entity = recordDao.findEntityById(id) ?: return@withTransaction
            recordDao.hardDelete(id)
            tagDao.pruneOrphans()
            gcMedia(entity.imagePaths + entity.videoPaths + entity.videoCoverPaths)
        }
    }

    suspend fun purgeAllTrash() {
        db.withTransaction {
            val trashed = recordDao.trashedRecords()
            val paths = trashed.flatMap { it.imagePaths + it.videoPaths + it.videoCoverPaths }
            trashed.forEach { recordDao.hardDelete(it.id) }
            tagDao.pruneOrphans()
            gcMedia(paths)
        }
    }

    /** 只删没有被任何记录引用的媒体；covers 跟随其视频是否存在 */
    private suspend fun gcMedia(candidatePaths: List<String>) {
        if (candidatePaths.isEmpty()) return
        val stillUsed = recordDao.allRecords()
            .flatMap { it.imagePaths + it.videoPaths + it.videoCoverPaths }
            .toHashSet()
        for (path in candidatePaths.distinct()) {
            if (path in stillUsed) continue
            val sha = runCatching { media.sha256Of(media.resolve(path)) }.getOrNull()
            if (sha != null) mediaDao.delete(sha)
            media.resolve(path).delete()
        }
    }

    /** 回收站过期清理：默认 30 天 */
    suspend fun purgeExpired(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600_000L
        db.withTransaction {
            val expired = recordDao.trashedRecords().filter { (it.deletedAt ?: 0L) < cutoff }
            val paths = expired.flatMap { it.imagePaths + it.videoPaths + it.videoCoverPaths }
            expired.forEach { recordDao.hardDelete(it.id) }
            tagDao.pruneOrphans()
            gcMedia(paths)
        }
    }

    suspend fun currentSettingsSnapshot() = Unit

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
