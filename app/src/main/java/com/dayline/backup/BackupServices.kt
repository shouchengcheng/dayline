package com.dayline.backup

import android.content.Context
import androidx.room.withTransaction
import com.dayline.data.db.AppDatabase
import com.dayline.data.db.MediaFileEntity
import com.dayline.data.media.MediaStore
import com.dayline.data.prefs.SettingsRepository
import com.dayline.data.repo.MergeApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 导出 zip。
 *
 * 输出结构（与导入方共用同一套相对路径，导入时零映射）：
 * ```
 * records_2026-11-14.zip
 *  ├─ records.json                     # 全量记录（含已删除）+ 标签 + 设置
 *  ├─ media/images/ab/abcd….jpg
 *  ├─ media/videos/ab/abcd….mp4
 *  └─ media/covers/ab/abcd….jpg
 * ```
 *
 * 取舍说明：
 * 1. **导出全量，包括回收站里的已删除记录**。否则"换机后回收站空了"或者
 *    "导出的删除不该在别的设备上复活"都不好解释；靠 isDeleted + updatedAt 在合并时说话。
 * 2. json 先写临时文件再进 zip，避免大库时把整份 json 拼在内存里。
 * 3. 输出到 cacheDir/backup/，再由 FileProvider 分享；不留常驻副本。
 */
class ExportService(
    private val context: Context,
    private val db: AppDatabase,
    private val media: MediaStore,
    private val settings: SettingsRepository
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun export(onProgress: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dao = db.recordDao()
        val tagDao = db.tagDao()

        onProgress("正在读取记录…")
        val records = dao.allRecordsWithTags()
        val tags = tagDao.all()
        val settingsState = settings.snapshot()

        // 收集所有被引用的媒体相对路径（去重）
        val mediaPaths = LinkedHashSet<String>()
        records.forEach {
            mediaPaths += it.record.imagePaths
            mediaPaths += it.record.videoPaths
            mediaPaths += it.record.videoCoverPaths
        }

        // 媒体 sha256：优先查索引，查不到现算（只在文件存在时）
        onProgress("正在核对媒体文件…")
        val mediaSha = HashMap<String, String>(mediaPaths.size)
        for (path in mediaPaths) {
            val known = db.mediaDao().findByPath(path)?.sha256
            if (known != null) {
                mediaSha[path] = known
                continue
            }
            val file = media.resolve(path)
            if (!file.isFile) continue
            val sha = media.sha256Of(file)
            mediaSha[path] = sha
            db.mediaDao().insert(
                MediaFileEntity(sha, path, Naming.kindOf(path).name.lowercase(), file.length(), System.currentTimeMillis())
            )
        }

        val document = BackupDocument(
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                beneficialLabel = settingsState.beneficialLabel,
                badLabel = settingsState.badLabel,
                beneficialCustom = settingsState.beneficialCustom,
                badCustom = settingsState.badCustom
            ),
            tags = tags.map { BackupTag(it.id, it.name, it.createdAt) },
            records = records.map { r ->
                val paths = r.record.imagePaths + r.record.videoPaths + r.record.videoCoverPaths
                BackupRecord(
                    id = r.record.id,
                    content = r.record.content,
                    side = r.record.side,
                    recordDate = r.record.recordDate,
                    createdAt = r.record.createdAt,
                    updatedAt = r.record.updatedAt,
                    isDeleted = r.record.isDeleted,
                    deletedAt = r.record.deletedAt,
                    tags = r.tagNames,
                    imagePaths = r.record.imagePaths,
                    videoPaths = r.record.videoPaths,
                    videoCoverPaths = r.record.videoCoverPaths,
                    mediaSha256 = paths.mapNotNull { p -> mediaSha[p]?.let { p to it } }.toMap()
                )
            }
        )

        val work = File(context.cacheDir, "backup").apply { mkdirs() }
        work.listFiles()?.forEach { it.deleteRecursively() }

        val jsonFile = File(work, "records.json")
        jsonFile.writeText(json.encodeToString(BackupDocument.serializer(), document), Charsets.UTF_8)

        val entries = ArrayList<Pair<String, File>>(mediaSha.size + 1)
        entries += ZipArchive.ENTRY_RECORDS_JSON to jsonFile
        val missing = ArrayList<String>()
        mediaSha.forEach { (path, _) ->
            val file = media.resolve(path)
            if (file.isFile) entries += Naming.zipPathOf(path) to file else missing += path
        }

        val base = Naming.baseName()
        val exists: (String) -> Boolean = { name ->
            File(context.cacheDir, "backup/$name").exists() || File(context.getExternalFilesDir(null) ?: work, name).exists()
        }
        val zipName = Naming.uniqueName(base, exists)
        val target = File(work, zipName)

        onProgress("正在打包 ${entries.size - 1} 个媒体文件…")
        ZipArchive.write(target, entries)

        jsonFile.delete()
        if (missing.isNotEmpty()) {
            File(work, "missing-media.txt").writeText(missing.joinToString("\n"), Charsets.UTF_8)
        }
        target
    }
}

/**
 * 导入合并。
 *
 * 事务边界：
 * - 媒体文件**在事务外**先落盘（adopt 到私有目录，按 sha256 命名，天然幂等）；
 * - 数据库写入**在一个事务里**完成，任何一步失败整体回滚，不会留下半条记录。
 *
 * 取舍说明：媒体先落盘、再入库。反过来（先入库再落盘）会在落盘中失败时让数据库
 * 指向不存在的文件；而先落盘后入库即使数据库回滚，也只是多出几个没人引用的文件，
 * 由 gcMedia 兜底清理，最坏情况是"多占点空间"，不是"数据损坏"。
 */
class ImportService(
    private val context: Context,
    private val db: AppDatabase,
    private val media: MediaStore,
    private val settings: SettingsRepository,
    private val applier: MergeApplier
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun import(zip: File, onProgress: (String) -> Unit = {}): ImportReport = withContext(Dispatchers.IO) {
        onProgress("正在读取备份包…")

        // ---------- 1) 解析 json ----------
        var document: BackupDocument? = null
        ZipArchive.read(zip) { name, _, input ->
            if (name == ZipArchive.ENTRY_RECORDS_JSON) {
                val text = input.readBytes().toString(Charsets.UTF_8)
                document = runCatching { json.decodeFromString(BackupDocument.serializer(), text) }.getOrNull()
            }
        }
        val doc = document ?: return@withContext ImportReport.failed("备份包里没有可解析的 records.json")

        // ---------- 2) 媒体落盘（事务外） ----------
        val resolved = HashMap<String, String>()
        val shaOfPath = HashMap<String, String>()
        val badEntries = ArrayList<String>()
        val tmpRoot = File(context.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }

        try {
            onProgress("正在解包媒体…")
            // ZipArchive.read 的回调是**非挂起**的（java.util.zip 的流式读取里不能切协程）,
            // 所以这里分两步：回调里只把字节落到临时文件并算 sha；拷贝到私有目录放在循环之后做。
            val pending = ArrayList<Pair<String, File>>()
            ZipArchive.read(zip) { name, _, input ->
                if (name == ZipArchive.ENTRY_RECORDS_JSON) return@read
                // 只解包认得出的媒体目录，其余静默忽略（未来扩展字段不打断老版本导入）
                if (!name.startsWith("media/")) return@read
                val tmp = ZipArchive.resolveInside(tmpRoot, name.substringAfterLast('/'))
                try {
                    tmp.outputStream().use { input.copyTo(it) }
                    pending += name to tmp
                } catch (t: Throwable) {
                    badEntries += "$name（${t.message}）"
                }
            }

            for ((name, tmp) in pending) {
                val kind = when {
                    name.contains("/images/") -> MediaStore.Kind.IMAGE
                    name.contains("/videos/") -> MediaStore.Kind.VIDEO
                    name.contains("/covers/") -> MediaStore.Kind.COVER
                    else -> MediaStore.Kind.IMAGE
                }
                try {
                    val sha = media.sha256Of(tmp)
                    val adopted = media.adopt(tmp, kind, sha, Naming.extOf(name))
                    resolved[name] = adopted.relativePath
                    shaOfPath[name] = sha
                    db.mediaDao().insert(
                        MediaFileEntity(sha, adopted.relativePath, kind.name.lowercase(), adopted.sizeBytes, System.currentTimeMillis())
                    )
                } catch (t: Throwable) {
                    badEntries += "$name（${t.message}）"
                } finally {
                    tmp.delete()
                }
            }

            // ---------- 3) 合并决策（纯函数） ----------
            onProgress("正在比对 ${doc.records.size} 条记录…")
            val verdicts = applier.plan(doc.records) { rec ->
                if (rec.isDeleted) {
                    null
                } else {
                    val shas = (rec.imagePaths + rec.videoPaths + rec.videoCoverPaths)
                        .mapNotNull { shaOfPath[it] ?: rec.mediaSha256[it] }
                    Fingerprint.of(rec.content, rec.side, rec.recordDate, rec.tags, shas)
                }
            }

            // ---------- 4) 单事务落库 ----------
            onProgress("正在写入数据库…")
            val result: MergeApplier.ApplyResult = db.withTransaction {
                applier.apply(doc.records, doc.tags, verdicts, resolved, shaOfPath)
            }

            // 设置合并放在事务外（DataStore 有自己的写入机制，不参与 Room 事务）
            settings.mergeFromImport(
                SettingsRepository.SettingsSnapshot(
                    beneficialLabel = doc.settings.beneficialLabel,
                    badLabel = doc.settings.badLabel,
                    beneficialCustom = doc.settings.beneficialCustom,
                    badCustom = doc.settings.badCustom
                )
            )

            ImportReport(
                ok = true,
                total = doc.records.size,
                inserted = result.inserted,
                updated = result.updated,
                skippedDuplicate = result.skippedDuplicate,
                skippedOlder = result.skippedOlder,
                restoredDeleted = result.restoredDeleted,
                mediaImported = resolved.size,
                failedEntries = badEntries,
                message = null
            )
        } catch (t: Throwable) {
            ImportReport.failed(t.message ?: t::class.java.simpleName, badEntries)
        } finally {
            tmpRoot.deleteRecursively()
        }
    }
}

/** 导入结果。用可读中文汇报给用户，而不是给一串英文枚举。 */
data class ImportReport(
    val ok: Boolean,
    val total: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val skippedDuplicate: Int = 0,
    val skippedOlder: Int = 0,
    val restoredDeleted: Int = 0,
    val mediaImported: Int = 0,
    val failedEntries: List<String> = emptyList(),
    val message: String? = null
) {
    fun summary(): String {
        if (!ok) return "导入失败：${message ?: "未知错误"}"
        val sb = StringBuilder()
        sb.append("导入完成：新增 $inserted 条，更新 $updated 条，去重跳过 $skippedDuplicate 条，本地较新跳过 $skippedOlder 条")
        if (restoredDeleted > 0) sb.append("，恢复已删除 $restoredDeleted 条")
        sb.append("，媒体 $mediaImported 个")
        if (failedEntries.isNotEmpty()) sb.append("；${failedEntries.size} 个文件损坏已跳过")
        return sb.toString()
    }

    companion object {
        fun failed(message: String, failedEntries: List<String> = emptyList()) =
            ImportReport(ok = false, failedEntries = failedEntries, message = message)
    }
}
