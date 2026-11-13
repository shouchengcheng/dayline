package com.dayline.data.repo

import com.dayline.backup.BackupRecord
import com.dayline.backup.BackupSettings
import com.dayline.backup.BackupTag
import com.dayline.backup.Fingerprint
import com.dayline.backup.LocalRecordSnapshot
import com.dayline.backup.MergeDecision
import com.dayline.backup.MergeEngine
import com.dayline.backup.MergeVerdict
import com.dayline.data.db.AppDatabase
import com.dayline.data.db.RecordEntity
import com.dayline.data.db.RecordTagCrossRef
import com.dayline.data.db.TagEntity

/**
 * 把 MergeEngine 的纯决策落库。所有写操作必须在外层 transaction 里调用。
 * 这里不处理媒体文件（文件由 ImportService 在事务外落盘后再进来），
 * 但会写入 media_files 索引与 records 的相对路径。
 */
class MergeApplier(
    private val db: AppDatabase,
    private val dao: com.dayline.data.db.RecordDao = db.recordDao(),
    private val tagDao: com.dayline.data.db.TagDao = db.tagDao(),
    private val mediaDao: com.dayline.data.db.MediaDao = db.mediaDao()
) {

    /** 本地快照：id -> (updatedAt, isDeleted, fingerprint)，以及未删除记录的指纹 -> id */
    suspend fun localSnapshot(): Pair<Map<String, LocalRecordSnapshot>, Map<String, String>> {
        val all = dao.allRecords()
        val byId = all.associate {
            it.id to LocalRecordSnapshot(it.id, it.updatedAt, it.isDeleted, it.contentFingerprint)
        }
        val fpOwner = all.asSequence()
            .filter { !it.isDeleted && it.contentFingerprint != null }
            .associate { it.contentFingerprint!! to it.id }
        return byId to fpOwner
    }

    /**
     * @param resolvedPaths 已落盘的媒体：zip 内路径 -> 应用内相对路径
     * @param shaOfPath zip 内路径 -> sha256
     */
    suspend fun apply(
        incoming: List<BackupRecord>,
        incomingTags: List<BackupTag>,
        verdicts: List<MergeVerdict>,
        resolvedPaths: Map<String, String>,
        shaOfPath: Map<String, String>
    ): ApplyResult {
        // 1) 标签：按 name 去重，复用本地同名标签
        val tagIdByName = HashMap<String, String>()
        tagDao.all().forEach { tagIdByName[it.name] = it.id }
        for (tag in incomingTags) {
            if (tagIdByName.containsKey(tag.name)) continue
            tagDao.insert(TagEntity(tag.id, tag.name, tag.createdAt))
            tagIdByName[tag.name] = tag.id
        }

        var inserted = 0
        var updated = 0
        var restored = 0
        var skippedDup = 0
        var skippedOlder = 0

        for (verdict in verdicts) {
            when (verdict.decision) {
                MergeDecision.SKIP_DUPLICATE -> { skippedDup++; continue }
                MergeDecision.SKIP_OLDER -> { skippedOlder++; continue }
                MergeDecision.UPDATE -> updated++
                MergeDecision.INSERT -> inserted++
            }

            val rec = verdict.record
            val localExists = dao.findEntityById(rec.id) != null
            val images = rec.imagePaths.map { resolvedPaths[it] ?: it }
            val videos = rec.videoPaths.map { resolvedPaths[it] ?: it }
            val covers = rec.videoCoverPaths.map { resolvedPaths[it] ?: it }

            val mediaShas = (rec.imagePaths + rec.videoPaths + rec.videoCoverPaths)
                .mapNotNull { shaOfPath[it] }
                .distinct()

            val fingerprint = if (rec.isDeleted) null else Fingerprint.of(
                content = rec.content,
                side = rec.side,
                recordDate = rec.recordDate,
                tagNames = rec.tags,
                mediaShas = mediaShas
            )

            dao.upsert(
                RecordEntity(
                    id = rec.id,
                    content = rec.content,
                    side = rec.side,
                    recordDate = rec.recordDate,
                    createdAt = rec.createdAt,
                    updatedAt = rec.updatedAt,
                    isDeleted = rec.isDeleted,
                    deletedAt = rec.deletedAt,
                    imagePaths = images,
                    videoPaths = videos,
                    videoCoverPaths = covers,
                    contentFingerprint = fingerprint
                )
            )

            // 标签关联：先清后建（覆盖式）
            dao.clearTagRefs(rec.id)
            val refs = rec.tags.mapNotNull { name ->
                tagIdByName[name]?.let { RecordTagCrossRef(rec.id, it) }
            }
            if (refs.isNotEmpty()) dao.insertTagRefs(refs)

            if (!localExists && rec.isDeleted) restored++
        }

        tagDao.pruneOrphans()

        return ApplyResult(
            inserted = inserted,
            updated = updated,
            skippedDuplicate = skippedDup,
            skippedOlder = skippedOlder,
            restoredDeleted = restored,
            failed = 0
        )
    }

    suspend fun plan(
        incoming: List<BackupRecord>,
        fingerprintOf: (BackupRecord) -> String?
    ): List<MergeVerdict> {
        val (byId, fpOwner) = localSnapshot()
        return MergeEngine.decide(incoming, byId, fpOwner, fingerprintOf)
    }

    data class ApplyResult(
        val inserted: Int,
        val updated: Int,
        val skippedDuplicate: Int,
        val skippedOlder: Int,
        val restoredDeleted: Int,
        val failed: Int
    )
}

/** 备份里的 settings -> 本地设置仓库需要的最小结构 */
fun BackupSettings.toSnapshot() = com.dayline.data.prefs.SettingsRepository.SettingsSnapshot(
    beneficialLabel = beneficialLabel,
    badLabel = badLabel,
    beneficialCustom = beneficialCustom,
    badCustom = badCustom
)
