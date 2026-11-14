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

package com.dayline.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 导入合并的纯逻辑：不碰 Room、不碰文件系统，因此可以单测。
 * Room 侧只负责把这里的决策落库。
 */

const val SCHEMA_VERSION = 1

@Serializable
data class BackupDocument(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("exportedAt") val exportedAt: Long,
    @SerialName("settings") val settings: BackupSettings,
    @SerialName("tags") val tags: List<BackupTag> = emptyList(),
    @SerialName("records") val records: List<BackupRecord> = emptyList()
)

@Serializable
data class BackupSettings(
    @SerialName("beneficialLabel") val beneficialLabel: String = "有益",
    @SerialName("badLabel") val badLabel: String = "糟糕",
    @SerialName("beneficialCustom") val beneficialCustom: Boolean = false,
    @SerialName("badCustom") val badCustom: Boolean = false
)

@Serializable
data class BackupTag(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("createdAt") val createdAt: Long
)

@Serializable
data class BackupRecord(
    @SerialName("id") val id: String,
    @SerialName("content") val content: String,
    @SerialName("side") val side: String,
    @SerialName("recordDate") val recordDate: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("isDeleted") val isDeleted: Boolean = false,
    @SerialName("deletedAt") val deletedAt: Long? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    /** zip 内相对路径，如 media/images/ab/abcd.jpg */
    @SerialName("imagePaths") val imagePaths: List<String> = emptyList(),
    @SerialName("videoPaths") val videoPaths: List<String> = emptyList(),
    @SerialName("videoCoverPaths") val videoCoverPaths: List<String> = emptyList(),
    /** 每个媒体路径对应的 sha256，导入时校验用；缺失则重新计算 */
    @SerialName("mediaSha256") val mediaSha256: Map<String, String> = emptyMap()
)

/**
 * 本地视图：导入算法只依赖这几项，便于测试与复用。
 */
data class LocalRecordSnapshot(
    val id: String,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val contentFingerprint: String?
)

/**
 * 内容指纹：content + side + recordDate + 排序后的标签集合 + 排序后的媒体 SHA-256 集合。
 * 取舍说明：图片顺序不参与指纹（用户拖动排序不应让同一张记录"变成新的"），
 * 所以先排序再拼接。标签同理按名称排序。
 */
object Fingerprint {

    fun of(
        content: String,
        side: String,
        recordDate: String,
        tagNames: Collection<String>,
        mediaShas: Collection<String>
    ): String {
        val normalized = buildString {
            append(content.trim().replace(Regex("\\s+"), " "))
            append('\u0001').append(side)
            append('\u0001').append(recordDate)
            append('\u0001').append(tagNames.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(","))
            append('\u0001').append(mediaShas.map { it.lowercase() }.sorted().joinToString(","))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

enum class MergeDecision {
    /** 本地没有该 id，插入 */
    INSERT,

    /** 同 id 且导入更新，覆盖本地（含 isDeleted） */
    UPDATE,

    /** 同 id 且导入不更新（或完全相同），跳过 */
    SKIP_OLDER,

    /** id 不同但内容完全一致（双方均未删除），视为重复 */
    SKIP_DUPLICATE
}

data class MergeVerdict(
    val record: BackupRecord,
    val decision: MergeDecision,
    val reason: String
)

object MergeEngine {

    /**
     * @param localById 本地所有记录（含已删除）的 id 快照
     * @param fingerprintOwner 本地未删除记录里，内容指纹 -> 记录 id 的映射
     */
    fun decide(
        incoming: List<BackupRecord>,
        localById: Map<String, LocalRecordSnapshot>,
        fingerprintOwner: Map<String, String>,
        fingerprintOf: (BackupRecord) -> String?
    ): List<MergeVerdict> {
        val result = ArrayList<MergeVerdict>(incoming.size)
        // 同一批次内部也要查重：zip 自身可能带重复内容
        val batchFingerprints = HashSet<String>()

        for (rec in incoming) {
            val local = localById[rec.id]
            if (local != null) {
                result += if (rec.updatedAt > local.updatedAt) {
                    MergeVerdict(rec, MergeDecision.UPDATE, "导入 updatedAt 更新")
                } else {
                    MergeVerdict(rec, MergeDecision.SKIP_OLDER, "本地不旧于导入")
                }
                // 同 id 的情况不参与内容去重，避免把"同一条记录的旧副本"当成重复内容而误判
                fingerprintOf(rec)?.let { batchFingerprints += it }
                continue
            }

            // 已删除记录只走 id 合并，不参与内容级去重
            if (rec.isDeleted) {
                result += MergeVerdict(rec, MergeDecision.INSERT, "已删除记录仅按 id 合并")
                continue
            }

            val fp = fingerprintOf(rec)
            if (fp != null && (fingerprintOwner.containsKey(fp) || batchFingerprints.contains(fp))) {
                result += MergeVerdict(rec, MergeDecision.SKIP_DUPLICATE, "内容指纹已存在")
                continue
            }

            fp?.let { batchFingerprints += it }
            result += MergeVerdict(rec, MergeDecision.INSERT, "新记录")
        }
        return result
    }
}
