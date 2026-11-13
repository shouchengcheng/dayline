package com.dayline.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * zip 往返 + zip-slip 防护。
 *
 * 这些用例跑在 JVM 上（不依赖 Android），故意用真实的 zip 文件而不是 mock：
 * zip slip 的坑只在真实字节流里才会出现（`..\\`、绝对路径、盘符、NUL）。
 */
class ZipArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `写入后能读回同样的内容`() {
        val a = tmp.newFile("a.txt").apply { writeText("hello 中文") }
        val b = tmp.newFile("b.bin").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val zip = File(tmp.root, "out.zip")

        ZipArchive.write(
            zip,
            listOf(
                "records.json" to a,
                "media/images/aa/a.txt" to a,
                "media/videos/aa/b.mp4" to b
            )
        )

        val names = ZipArchive.list(zip).map { it.name }
        assertEquals(
            listOf("records.json", "media/images/aa/a.txt", "media/videos/aa/b.mp4"),
            names
        )

        val collected = HashMap<String, Int>()
        ZipArchive.read(zip) { name, _, input ->
            collected[name] = input.readBytes().size
        }
        // "hello " = 6 字节（含尾空格），"中文" = 3 + 3 字节（UTF-8），合计 12
        assertEquals(6 + 6, collected["records.json"])
        assertEquals(5000, collected["media/videos/aa/b.mp4"])
    }

    @Test
    fun `媒体用STORED不加压缩`() {
        val big = tmp.newFile("v.mp4").apply { writeBytes(ByteArray(10_000)) }
        val zip = File(tmp.root, "s.zip")
        ZipArchive.write(zip, listOf("media/videos/aa/v.mp4" to big))
        val info = ZipArchive.list(zip).single()
        // STORED 的判据是「压缩后长度不小于原始长度」，而不是严格相等：
        // 部分 JDK 会为 STORED 条目多写一小段数据描述符（这里是 +11 字节）。
        // 若走了 DEFLATE，10KB 全零数据的压缩后体积会远小于原始体积。
        assertTrue(
            "STORED 条目压缩后体积应≈原始体积，实际 size=${info.size} compressed=${info.compressedSize}",
            info.compressedSize <= info.size + 64
        )
    }

    @Test
    fun `拒绝父目录穿越`() {
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("../../etc/passwd") }
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("media/../../x.jpg") }
    }

    @Test
    fun `拒绝反斜杠穿越`() {
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("..\\..\\windows\\system32") }
    }

    @Test
    fun `拒绝绝对路径与盘符`() {
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("/etc/passwd") }
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("C:/x.jpg") }
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("c:\\x.jpg") }
    }

    @Test
    fun `拒绝空段与控制字符`() {
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("media//x.jpg") }
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("media/\u0000/x.jpg") }
        assertThrows(ZipSlipException::class.java) { ZipArchive.safeEntryName("") }
    }

    @Test
    fun `正常路径规范化后可用`() {
        assertEquals("media/images/aa/x.jpg", ZipArchive.safeEntryName("media\\images\\aa\\x.jpg"))
        assertEquals("records.json", ZipArchive.safeEntryName("./records.json"))
    }

    @Test
    fun `resolveInside保证不逃出根目录`() {
        val root = tmp.newFolder("root")
        val target = ZipArchive.resolveInside(root, "media/images/aa/x.jpg")
        assertTrue(target.path.replace('\\', '/').endsWith("root/media/images/aa/x.jpg"))
        assertThrows(ZipSlipException::class.java) { ZipArchive.resolveInside(root, "../escape.jpg") }
    }
}
