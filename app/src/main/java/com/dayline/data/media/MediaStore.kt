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

package com.dayline.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.dayline.data.db.MediaFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 媒体文件落盘 + 去重 + 视频封面提取。
 *
 * 目录约定（相对 context.filesDir）：
 *   media/images/<sha前2位>/<sha>.jpg
 *   media/videos/<sha前2位>/<sha>.mp4
 *   media/covers/<sha前2位>/<sha>.jpg
 *
 * 取舍说明：用 sha256 做文件名而不是 UUID，好处是同一份文件天然只存一份，
 * 导入时"按哈希复核"也就是"按文件名复核"，不需要额外索引。
 * 代价是文件扩展名要靠 mime 猜，因此统一转成 .jpg/.mp4 这类固定后缀。
 */
class MediaStore(
    private val context: Context
) {

    private val root: File get() = File(context.filesDir, "media")

    fun imagesDir() = File(root, "images").apply { mkdirs() }
    fun videosDir() = File(root, "videos").apply { mkdirs() }
    fun coversDir() = File(root, "covers").apply { mkdirs() }

    fun resolve(relativePath: String): File = File(context.filesDir, relativePath)

    fun exists(relativePath: String): Boolean = resolve(relativePath).isFile

    /**
     * 从系统相册/相册选择器拿到的 Uri 复制进私有目录，返回 (sha256, 相对路径, 大小)。
     */
    suspend fun importFromUri(uri: Uri, kind: Kind): ImportedFile = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import-${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it, DEFAULT_BUFFER) }
            } ?: error("无法读取所选文件：$uri")

            val sha = sha256Of(tmp)
            val ext = when (kind) {
                Kind.IMAGE -> ".jpg"
                Kind.VIDEO -> ".mp4"
                Kind.COVER -> ".jpg"
            }
            val relative = relativePathFor(kind, sha, ext)
            val target = resolve(relative)

            if (!target.isFile) {
                target.parentFile?.mkdirs()
                tmp.copyTo(target, overwrite = true)
            }

            ImportedFile(
                sha256 = sha,
                relativePath = relative,
                sizeBytes = target.length(),
                kind = kind
            )
        } finally {
            tmp.delete()
        }
    }

    /** 把一个已有文件（导入时解压出来的）按哈希收编进私有目录；已存在则直接复用。 */
    suspend fun adopt(src: File, kind: Kind, sha: String, ext: String = ".bin"): ImportedFile =
        withContext(Dispatchers.IO) {
            val relative = relativePathFor(kind, sha, ext)
            val target = resolve(relative)
            if (!target.isFile) {
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
            }
            ImportedFile(sha, relative, target.length(), kind)
        }

    /**
     * 提取视频首帧并缓存成 jpg。返回相对路径；失败返回 null（列表里显示占位图）。
     * 已存在同名封面直接复用，不重复解码。
     */
    suspend fun ensureCover(videoRelativePath: String, videoSha: String): String? =
        withContext(Dispatchers.IO) {
            val relative = relativePathFor(Kind.COVER, videoSha, ".jpg")
            val target = resolve(relative)
            if (target.isFile && target.length() > 0) return@withContext relative

            val video = resolve(videoRelativePath)
            if (!video.isFile) return@withContext null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.absolutePath)
                val frame: Bitmap = retriever.getFrameAtTime(
                    COVER_TIMESTAMP_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: return@withContext null

                val scaled = scaleDown(frame, MAX_COVER_EDGE)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
                }
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                relative
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    suspend fun toEntity(file: ImportedFile): MediaFileEntity =
        MediaFileEntity(
            sha256 = file.sha256,
            relativePath = file.relativePath,
            kind = file.kind.name.lowercase(),
            sizeBytes = file.sizeBytes,
            createdAt = System.currentTimeMillis()
        )

    suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> digest.update(stream) }
        digest.digest().toHex()
    }

    private fun relativePathFor(kind: Kind, sha: String, ext: String): String {
        val folder = when (kind) {
            Kind.IMAGE -> "images"
            Kind.VIDEO -> "videos"
            Kind.COVER -> "covers"
        }
        return "media/$folder/${sha.take(2)}/$sha$ext"
    }

    private fun InputStream.readAllNotUsed() = Unit

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    enum class Kind { IMAGE, VIDEO, COVER }

    data class ImportedFile(
        val sha256: String,
        val relativePath: String,
        val sizeBytes: Long,
        val kind: Kind
    )

    private companion object {
        const val DEFAULT_BUFFER = 64 * 1024
        const val COVER_TIMESTAMP_US = 0L
        const val MAX_COVER_EDGE = 720
        const val COVER_QUALITY = 82
    }
}

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return out.toString()
}

private fun MessageDigest.update(stream: InputStream) {
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = stream.read(buf)
        if (n <= 0) break
        update(buf, 0, n)
    }
}
