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

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * zip 读写。刻意**不依赖 Android**，因此可以在 JVM 单测里跑真实的 zip 往返。
 *
 * 取舍说明：
 * - 用 `java.util.zip` 而不是 Zip4j：后者要多一个依赖，而我们只需要 STORED 足够
 *   的功能（媒体文件已经是压缩格式，再套一层 deflate 反而浪费 CPU 和时间）。
 * - 媒体（jpg/mp4）用 STORED，json 用 DEFLATE。
 * - 解压时强制做 zip-slip 校验：entry 名里带 `..` 或绝对路径一律拒绝。
 *   这是唯一一处"宁可失败也不能放行"的地方——历史上大量 App 栽在这里。
 */
object ZipArchive {

    const val ENTRY_RECORDS_JSON = "records.json"

    /** 这些后缀认为是已压缩格式，直接 STORED */
    private val STORED_EXTS = setOf(".jpg", ".jpeg", ".png", ".webp", ".mp4", ".m4v", ".heic")

    fun isStored(path: String): Boolean {
        val lower = path.lowercase()
        return STORED_EXTS.any { lower.endsWith(it) }
    }

    /**
     * 写 zip。
     * @param entries 顺序敏感的 (zip 内路径, 源文件)。records.json 由调用方以临时文件形式传入，
     *                保证它在第一项（有些解压工具按顺序流式读取）。
     */
    fun write(target: File, entries: List<Pair<String, File>>, bufferSize: Int = 64 * 1024) {
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target), bufferSize)).use { zos ->
            zos.setLevel(6)
            for ((name, src) in entries) {
                val entry = ZipEntry(safeEntryName(name))
                if (isStored(name)) {
                    // 已压缩格式用 STORED，避免二次压缩白烧 CPU。
                    //
                    // 取舍说明：STORED 必须在 putNextEntry 之前把 size/crc 填好，所以这里会先拷进
                    // cacheDir 下的临时文件并同时算 CRC，再从那一个副本写进 zip——**只读源文件一遍**。
                    // 早期版本是先扫一遍源文件算 CRC、再读一遍拷贝，1GB 视频等于 2GB 读 IO。
                    val tmp = File.createTempFile("zipstored-", ".tmp", target.parentFile ?: File("."))
                    try {
                        val crc = java.util.zip.CRC32()
                        var size = 0L
                        java.io.BufferedOutputStream(FileOutputStream(tmp), bufferSize).use { out ->
                            src.inputStream().use { input ->
                                val buf = ByteArray(bufferSize)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    crc.update(buf, 0, n)
                                    out.write(buf, 0, n)
                                    size += n
                                }
                            }
                        }
                        entry.method = ZipEntry.STORED
                        entry.size = size
                        entry.compressedSize = size
                        entry.crc = crc.value
                        zos.putNextEntry(entry)
                        tmp.inputStream().use { it.copyTo(zos, bufferSize) }
                    } finally {
                        tmp.delete()
                    }
                } else {
                    entry.method = ZipEntry.DEFLATED
                    zos.putNextEntry(entry)
                    src.inputStream().use { it.copyTo(zos, bufferSize) }
                }
                zos.closeEntry()
            }
        }
    }

    /**
     * 读 zip 的条目清单（不解压全部）。用于导入前的预检与进度估算。
     * @throws ZipSlipException entry 名非法
     */
    fun list(zip: File): List<ZipEntryInfo> {
        val out = ArrayList<ZipEntryInfo>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val safe = safeEntryName(entry.name)
                out += ZipEntryInfo(name = safe, size = entry.size, compressedSize = entry.compressedSize)
                zis.closeEntry()
            }
        }
        return out
    }

    /**
     * 流式解压：每个 entry 交给回调处理，**不一次性把所有文件展开到内存**。
     * 回调拿到的是一个已校验过名字的 entry 名 + 输入流。
     *
     * 取舍说明：用回调而不是"解压到临时目录再处理"，是为了在导入大视频时
     * 峰值磁盘占用只多一份临时文件，而不是整个 zip 的解压副本。
     */
    fun read(zip: File, onEntry: (name: String, size: Long, input: ZipInputStream) -> Unit) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val safe = safeEntryName(entry.name)
                onEntry(safe, entry.size, zis)
                zis.closeEntry()
            }
        }
    }

    /**
     * zip-slip 防护：
     * 1) 反斜杠统一成正斜杠（Windows 打的包可能是 `\\`）
     * 2) 拒绝绝对路径、盘符、`..` 段、空段
     * 3) 拒绝控制字符
     */
    fun safeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.isEmpty()) throw ZipSlipException("空条目名")
        if (normalized.startsWith("/")) throw ZipSlipException("拒绝绝对路径：$raw")
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) throw ZipSlipException("拒绝盘符路径：$raw")
        val segments = normalized.split('/')
        for (seg in segments) {
            // 注意：seg == "." 只在中间段才非法；开头或结尾的 "."（如 "./records.json"）
            // 是合法的相对写法，直接跳过即可——很多打包工具都会这样写。
            if (seg == ".") {
                if (segments.size == 1) throw ZipSlipException("非法路径段 '.'：$raw")
                continue
            }
            if (seg.isEmpty() || seg == "..") throw ZipSlipException("非法路径段 '$seg'：$raw")
            if (seg.any { it.code < 0x20 }) throw ZipSlipException("含控制字符：$raw")
        }
        return segments.filter { it != "." }.joinToString("/")
    }

    /**
     * 把 entry 名解析到 root 下的绝对 File，并再校验一次最终路径确实在 root 内。
     * 这是第二道防线：即使 safeEntryName 有漏洞，规范化后的路径也出不了 root。
     */
    fun resolveInside(root: File, entryName: String): File {
        val safe = safeEntryName(entryName)
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, safe).canonicalFile
        if (target != rootCanonical && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw ZipSlipException("路径逃逸：$entryName -> ${target.path}")
        }
        return target
    }

    data class ZipEntryInfo(val name: String, val size: Long, val compressedSize: Long)
}

class ZipSlipException(message: String) : Exception(message)
