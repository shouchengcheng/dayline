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

import com.dayline.data.db.MediaFileEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Naming {

    /** records_yyyy-MM-dd.zip，日期取导出当天的本地日期 */
    fun baseName(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        return "records_${fmt.format(Date(now))}"
    }

    /**
     * 同一天重复导出：records_2026-11-14.zip -> records_2026-11-14_1.zip -> _2 ...
     * 取舍说明：优先用 _1/_2 序号（用户一眼能看出是第几次导出），
     * 序号超过 99 时才退回 HHmmss，避免文件名无限变长。
     */
    fun uniqueName(
        base: String,
        exists: (String) -> Boolean,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): String {
        val first = "$base.zip"
        if (!exists(first)) return first
        for (i in 1..99) {
            val candidate = "${base}_$i.zip"
            if (!exists(candidate)) return candidate
        }
        val fmt = SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = zone }
        val stamp = fmt.format(Date(now))
        return "${base}_$stamp.zip"
    }

    /**
     * 媒体在 zip 内与在私有目录使用同一相对路径（media/images/xx/sha.jpg），
     * 这样 records.json 的路径既能被导出方写、也能被导入方直接落盘。
     */
    fun zipPathOf(relativePath: String): String = relativePath.replace('\\', '/')

    fun extOf(path: String, fallback: String = ".bin"): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot).lowercase() else fallback
    }

    fun kindOf(relativePath: String): MediaStoreKind = when {
        relativePath.contains("/images/") -> MediaStoreKind.IMAGE
        relativePath.contains("/videos/") -> MediaStoreKind.VIDEO
        relativePath.contains("/covers/") -> MediaStoreKind.COVER
        else -> MediaStoreKind.UNKNOWN
    }

    fun toEntity(file: File, relativePath: String, sha256: String): MediaFileEntity = MediaFileEntity(
        sha256 = sha256,
        relativePath = relativePath,
        kind = kindOf(relativePath).name.lowercase(),
        sizeBytes = file.length(),
        createdAt = System.currentTimeMillis()
    )
}

enum class MediaStoreKind { IMAGE, VIDEO, COVER, UNKNOWN }
