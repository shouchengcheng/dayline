package com.dayline.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/**
 * 合并决策的单元测试。
 *
 * 覆盖点（每一条都对应一个真实的用户场景）：
 * 1. 本地没有 → 插入
 * 2. 同 id，导入更新 → 覆盖
 * 3. 同 id，导入更旧 → 跳过（不能用旧数据覆盖新编辑）
 * 4. 同 id，时间相同 → 跳过（避免无意义的写放大）
 * 5. 不同 id、内容一致 → 视为重复跳过
 * 6. 批量内部自己重复 → 只保留第一条
 * 7. 已删除记录只按 id 合并，不参与内容去重
 *     （否则"我在 A 机删了它"会被"B 机还没删的同内容记录"复活，或反过来）
 */
class MergeEngineTest {

    private fun rec(
        id: String,
        content: String = "文本",
        updatedAt: Long = 1_000L,
        isDeleted: Boolean = false,
        tags: List<String> = emptyList()
    ) = BackupRecord(
        id = id,
        content = content,
        side = "beneficial",
        recordDate = "2026-11-14",
        createdAt = 500L,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        tags = tags
    )

    private val fp: (BackupRecord) -> String? = { r ->
        if (r.isDeleted) null
        else Fingerprint.of(r.content, r.side, r.recordDate, r.tags, emptyList())
    }

    @Test
    fun `本地没有时插入`() {
        val verdicts = MergeEngine.decide(listOf(rec("a")), emptyMap(), emptyMap(), fp)
        assertEquals(MergeDecision.INSERT, verdicts.single().decision)
    }

    @Test
    fun `同id导入更新则覆盖`() {
        val local = mapOf("a" to LocalRecordSnapshot("a", 1_000L, false, null))
        val verdicts = MergeEngine.decide(listOf(rec("a", updatedAt = 2_000L)), local, emptyMap(), fp)
        assertEquals(MergeDecision.UPDATE, verdicts.single().decision)
    }

    @Test
    fun `同id导入更旧则跳过`() {
        val local = mapOf("a" to LocalRecordSnapshot("a", 5_000L, false, null))
        val verdicts = MergeEngine.decide(listOf(rec("a", updatedAt = 2_000L)), local, emptyMap(), fp)
        assertEquals(MergeDecision.SKIP_OLDER, verdicts.single().decision)
    }

    @Test
    fun `同id且时间相同也跳过`() {
        val local = mapOf("a" to LocalRecordSnapshot("a", 3_000L, false, null))
        val verdicts = MergeEngine.decide(listOf(rec("a", updatedAt = 3_000L)), local, emptyMap(), fp)
        assertEquals(MergeDecision.SKIP_OLDER, verdicts.single().decision)
    }

    @Test
    fun `不同id但内容一致视为重复`() {
        val fpOwner = mapOf(fp(rec("local"))!! to "local")
        val verdicts = MergeEngine.decide(listOf(rec("remote")), emptyMap(), fpOwner, fp)
        assertEquals(MergeDecision.SKIP_DUPLICATE, verdicts.single().decision)
    }

    @Test
    fun `同一批次内部重复只保留第一条`() {
        val verdicts = MergeEngine.decide(listOf(rec("x"), rec("y")), emptyMap(), emptyMap(), fp)
        assertEquals(MergeDecision.INSERT, verdicts[0].decision)
        assertEquals(MergeDecision.SKIP_DUPLICATE, verdicts[1].decision)
    }

    @Test
    fun `已删除记录只按id合并参与插入`() {
        // 本地有一条同内容的未删除记录，导入的是"已删除"的副本，id 不同：
        // 不应该被内容指纹去重掉，否则删除状态就丢了
        val fpOwner = mapOf(fp(rec("live"))!! to "live")
        val verdicts = MergeEngine.decide(listOf(rec("dead", isDeleted = true)), emptyMap(), fpOwner, fp)
        assertEquals(MergeDecision.INSERT, verdicts.single().decision)
    }

    @Test
    fun `已删除记录的更新同样按updatedAt比较`() {
        val local = mapOf("d" to LocalRecordSnapshot("d", 1_000L, false, null))
        val verdicts = MergeEngine.decide(listOf(rec("d", isDeleted = true, updatedAt = 9_000L)), local, emptyMap(), fp)
        assertEquals(MergeDecision.UPDATE, verdicts.single().decision)
    }

    @Test
    fun `标签顺序不同但内容相同视为重复`() {
        val a = rec("a", tags = listOf("读书", "运动"))
        val b = rec("b", tags = listOf("运动", "读书"))
        assertEquals(fp(a), fp(b))
        val verdicts = MergeEngine.decide(listOf(b), emptyMap(), mapOf(fp(a)!! to "a"), fp)
        assertEquals(MergeDecision.SKIP_DUPLICATE, verdicts.single().decision)
    }

    @Test
    fun `空白差异不影响指纹`() {
        val a = rec("a", content = "今天  跑了 步")
        val b = rec("b", content = " 今天 跑了 步 ")
        assertEquals(fp(a), fp(b))
    }

    @Test
    fun `侧别不同指纹不同`() {
        val a = rec("a")
        val b = a.copy(side = "bad")
        assertNotEquals(fp(a), fp(b))
    }

    @Test
    fun `日期不同指纹不同`() {
        val a = rec("a")
        val b = a.copy(recordDate = "2026-11-13")
        assertNotEquals(fp(a), fp(b))
    }
}

/**
 * 文件命名规则。
 * 场景：同一天连续导出多次，文件名不能互相覆盖；超过 99 次退回时间戳。
 */
class NamingTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val noon = 1_764_000_000_000L // 2025-11-24 09:20 UTC+8 附近，仅用于往返一致性

    @Test
    fun `第一次导出没有后缀`() {
        val name = Naming.uniqueName("records_2026-11-14", exists = { false }, now = noon, zone = zone)
        assertEquals("records_2026-11-14.zip", name)
    }

    @Test
    fun `重名依次追加序号`() {
        val taken = setOf("records_2026-11-14.zip", "records_2026-11-14_1.zip")
        val name = Naming.uniqueName("records_2026-11-14", exists = { it in taken }, now = noon, zone = zone)
        assertEquals("records_2026-11-14_2.zip", name)
    }

    @Test
    fun `序号用尽后退回时间戳`() {
        val all = (0..99).map { if (it == 0) "records_2026-11-14.zip" else "records_2026-11-14_$it.zip" }.toSet()
        val name = Naming.uniqueName("records_2026-11-14", exists = { it in all }, now = noon, zone = zone)
        assertTrue(name.startsWith("records_2026-11-14_"))
        assertTrue(name.endsWith(".zip"))
        assertFalse(name in all)
    }

    @Test
    fun `扩展名解析`() {
        assertEquals(".jpg", Naming.extOf("media/images/ab/abcd.jpg"))
        assertEquals(".mp4", Naming.extOf("media/videos/ab/abcd.MP4"))
        assertEquals(".bin", Naming.extOf("media/videos/ab/abcd"))
    }

    @Test
    fun `路径类别识别`() {
        assertEquals(MediaStoreKind.IMAGE, Naming.kindOf("media/images/aa/x.jpg"))
        assertEquals(MediaStoreKind.VIDEO, Naming.kindOf("media/videos/aa/x.mp4"))
        assertEquals(MediaStoreKind.COVER, Naming.kindOf("media/covers/aa/x.jpg"))
        assertEquals(MediaStoreKind.UNKNOWN, Naming.kindOf("records.json"))
    }

    @Test
    fun `zip 内路径统一正斜杠`() {
        assertEquals("media/images/aa/x.jpg", Naming.zipPathOf("media\\images\\aa\\x.jpg"))
    }
}
