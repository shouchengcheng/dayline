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

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 记录表。所有媒体只存相对路径（相对 context.filesDir），不存字节流。
 */
@Entity(
    tableName = "records",
    indices = [
        Index("recordDate"),
        Index("isDeleted"),
        Index("updatedAt"),
        Index("contentFingerprint")
    ]
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val content: String,
    /** beneficial | bad */
    val side: String,
    /** 天粒度，yyyy-MM-dd */
    val recordDate: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val imagePaths: List<String> = emptyList(),
    val videoPaths: List<String> = emptyList(),
    val videoCoverPaths: List<String> = emptyList(),
    /** 内容级去重指纹；仅未删除记录维护，删除时置 null */
    val contentFingerprint: String? = null
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "record_tag",
    primaryKeys = ["recordId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class RecordTagCrossRef(
    val recordId: String,
    val tagId: String
)

/**
 * 媒体索引表：sha256 作为主键，导入时据此复用同一份文件。
 */
@Entity(
    tableName = "media_files",
    indices = [Index(value = ["relativePath"], unique = true)]
)
data class MediaFileEntity(
    @PrimaryKey val sha256: String,
    /** 如 media/images/ab12cd34.jpg */
    val relativePath: String,
    /** image | video | cover */
    val kind: String,
    val sizeBytes: Long,
    val createdAt: Long
)

data class RecordWithTags(
    @Embedded val record: RecordEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecordTagCrossRef::class,
            parentColumn = "recordId",
            entityColumn = "tagId"
        )
    )
    val tags: List<TagEntity>
) {
    val tagNames: List<String> get() = tags.map { it.name }
}

/** 轻量投影，只用于素材/导出等不需要标签的场景。 */
data class RecordHead(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "recordDate") val recordDate: String,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long
)

/**
 * 只含[recordDate] 的投影，给「有哪些天」分页用。
 * 取舍说明：不用 SELECT DISTINCT recordDate 直接映射 String，
 * 因为 Room 对单列投影的映射比整行实体更省一次 Cursor 建对象。
 */
data class DateRow(
    @ColumnInfo(name = "recordDate") val recordDate: String
)

/** 只取媒体路径三列，供媒体回收时判断「是否仍被引用」，不把正文等整行读进内存 */
data class MediaRefs(
    @ColumnInfo(name = "imagePaths") val imagePaths: List<String>,
    @ColumnInfo(name = "videoPaths") val videoPaths: List<String>,
    @ColumnInfo(name = "videoCoverPaths") val videoCoverPaths: List<String>
)
