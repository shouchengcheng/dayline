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

package com.dayline.data.repo

import androidx.paging.PagingSource
import com.dayline.data.db.RecordDao
import com.dayline.data.db.RecordEntity
import com.dayline.data.db.RecordHead
import com.dayline.data.db.RecordTagCrossRef
import com.dayline.data.db.RecordWithTags
import com.dayline.data.db.TagEntity
import com.dayline.domain.TimelineItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴分页组装逻辑。
 *
 * 这是整个 App 最核心的一条设计决策（按天分页），也是**最容易悄悄写错**的地方：
 * 一旦退化成按记录分页，跨页边界会各插一个同一天的日期头，滚动时就能看到重复日期。
 * 所以这里用假 DAO 把 `TimelinePagingSource.load()` 真正跑起来，纯 JVM、不需要 Room/Robolectric。
 *
 * 所测行为：
 * 1. 一页 N 天 = N 个日期头，日期倒序
 * 2. 同一天多条记录只出 1 个日期头
 * 3. **连续翻页时日期集合不重复、不丢失**（核心断言）
 * 4. prevKey / nextKey 正确，末页 nextKey 为 null
 * 5. 已删除记录不出现在时间轴上（由 DAO 过滤，这里验证 PagingSource 不额外引入）
 */
class TimelinePagingSourceTest {

    // ---------------- 假 DAO ----------------

    /**
     * 只实现分页需要的三个方法，其余全部 `TODO()`。
     * 用 `TODO()` 而不是空实现：如果将来 PagingSource 误调用别的方法，测试会立刻炸出来，
     * 而不是静默地返回空列表、让测试假通过。
     */
    private class FakeRecordDao(
        private val records: List<RecordEntity>,
        private val tagsById: Map<String, List<TagEntity>> = emptyMap()
    ) : RecordDao {

        /** 与真实 SQL 语义一致：只取未删除记录的日期，DISTINCT + 倒序 */
        override suspend fun pageDates(limit: Int, offset: Int): List<String> =
            records.asSequence()
                .filter { !it.isDeleted }
                .map { it.recordDate }
                .distinct()
                .sortedDescending()
                .drop(offset)
                .take(limit)
                .toList()

        /** 与真实 SQL 语义一致：recordDate DESC, createdAt ASC, id ASC */
        override suspend fun recordsForDates(dates: List<String>): List<RecordWithTags> {
            val set = dates.toHashSet()
            return records.asSequence()
                .filter { !it.isDeleted && it.recordDate in set }
                .sortedWith(compareByDescending<RecordEntity> { it.recordDate }
                    .thenBy { it.createdAt }
                    .thenBy { it.id })
                .map { RecordWithTags(it, tagsById[it.id].orEmpty()) }
                .toList()
        }

        override suspend fun findById(id: String): RecordWithTags? = TODO("分页逻辑不应调用")
        override suspend fun pageDatesByTag(tag: String, limit: Int, offset: Int): List<String> =
            TODO("未过滤时不应调用")
        override suspend fun recordsForDatesByTag(dates: List<String>, tag: String): List<RecordWithTags> =
            TODO("未过滤时不应调用")
        override suspend fun findEntityById(id: String): RecordEntity? = TODO("分页逻辑不应调用")
        override suspend fun findIdByFingerprint(fingerprint: String): String? = TODO("分页逻辑不应调用")
        override fun observeTrash(): Flow<List<RecordWithTags>> = flowOf(emptyList())
        override suspend fun trashedRecords(): List<RecordEntity> = TODO("分页逻辑不应调用")
        override suspend fun upsert(record: RecordEntity) = TODO("分页逻辑不应调用")
        override suspend fun upsertAll(records: List<RecordEntity>) = TODO("分页逻辑不应调用")
        override suspend fun insertTagRefs(refs: List<RecordTagCrossRef>) = TODO("分页逻辑不应调用")
        override suspend fun clearTagRefs(recordId: String) = TODO("分页逻辑不应调用")
        override suspend fun markDeleted(id: String, at: Long) = TODO("分页逻辑不应调用")
        override suspend fun markRestored(id: String, at: Long, fingerprint: String?) = TODO("分页逻辑不应调用")
        override suspend fun hardDelete(id: String) = TODO("分页逻辑不应调用")
        override suspend fun allRecordsWithTags(): List<RecordWithTags> = TODO("分页逻辑不应调用")
        override suspend fun allRecords(): List<RecordEntity> = TODO("分页逻辑不应调用")
        override suspend fun allMediaRefs(): List<com.dayline.data.db.MediaRefs> = TODO("分页逻辑不应调用")
        override suspend fun countsForDate(date: String): List<com.dayline.data.db.SideCount> = TODO("分页逻辑不应调用")
        override suspend fun activeCount(): Int = TODO("分页逻辑不应调用")
    }

    // ---------------- 造数据 ----------------

    private var seq = 0L

    private fun record(date: String, hourOffset: Long = 0, deleted: Boolean = false): RecordEntity {
        seq += 1
        return RecordEntity(
            id = "id-$seq",
            content = "第 $seq 条",
            side = if (seq % 2 == 0L) "bad" else "beneficial",
            recordDate = date,
            createdAt = 1_000_000L + date.hashCode().toLong() * 100 + hourOffset,
            updatedAt = 2_000_000L,
            isDeleted = deleted,
            deletedAt = if (deleted) 3_000_000L else null,
            contentFingerprint = null
        )
    }

    /**
     * 生成 days 天、每天 perDay 条，日期从 2026-11-14 往前倒推。
     *
     * 必须用 LocalDate 做减法：之前手写 `14 - d` 拼接字符串，在 days > 14 时会溢出到
     * "2026-11--5" 这种非法日期（第一次跑就挂了），也会漏掉跨月的那几天。
     */
    private fun buildDays(days: Int, perDay: Int): List<RecordEntity> {
        val start = java.time.LocalDate.of(2026, 11, 14)
        val out = ArrayList<RecordEntity>(days * perDay)
        for (d in 0 until days) {
            val date = start.minusDays(d.toLong()).toString()
            for (i in 0 until perDay) out += record(date, hourOffset = i.toLong())
        }
        return out
    }

    private fun refresh(key: Int?, size: Int = 7) =
        PagingSource.LoadParams.Refresh<Int>(key = key, loadSize = size, placeholdersEnabled = false)

    private fun append(key: Int, size: Int = 7) =
        PagingSource.LoadParams.Append<Int>(key = key, loadSize = size, placeholdersEnabled = false)

    private fun LoadResultPageOf(result: PagingSource.LoadResult<Int, TimelineItem>): PagingSource.LoadResult.Page<Int, TimelineItem> {
        assertTrue("期望 Page，实际 ${result::class.simpleName}", result is PagingSource.LoadResult.Page)
        return result as PagingSource.LoadResult.Page<Int, TimelineItem>
    }

    // ---------------- 用例 ----------------

    @Test
    fun `一页7天出7个日期头且日期倒序`() = runTest {
        val source = TimelinePagingSource(FakeRecordDao(buildDays(days = 10, perDay = 1)))
        val page = LoadResultPageOf(source.load(refresh(null)))

        val headers = page.data.filterIsInstance<TimelineItem.DayHeader>().map { it.date }
        assertEquals(7, headers.size)
        assertEquals(listOf("2026-11-14", "2026-11-13", "2026-11-12", "2026-11-11", "2026-11-10", "2026-11-09", "2026-11-08"), headers)
    }

    @Test
    fun `同一天多条只出一个日期头`() = runTest {
        val source = TimelinePagingSource(FakeRecordDao(buildDays(days = 1, perDay = 4)))
        val page = LoadResultPageOf(source.load(refresh(null)))

        assertEquals(5, page.data.size)                        // 1 头 + 4 条
        assertEquals(1, page.data.count { it is TimelineItem.DayHeader })
        assertEquals(4, page.data.count { it is TimelineItem.Entry })

        // 列表是 reverseLayout：数据里组头排在当天记录之后，渲染后才显示在这一天的最上方
        assertTrue(page.data.last() is TimelineItem.DayHeader)
        assertEquals("2026-11-14", (page.data.last() as TimelineItem.DayHeader).date)
    }

    @Test
    fun `连续翻页时日期头不重复也不丢`() = runTest {
        val days = 20
        val perDay = 2
        val source = TimelinePagingSource(FakeRecordDao(buildDays(days = days, perDay = perDay)))

        val seenHeaders = ArrayList<String>()
        val seenEntries = ArrayList<String>()
        var key: Int? = null
        var pages = 0

        while (true) {
            val result = source.load(if (key == null) refresh(null) else append(key))
            val page = LoadResultPageOf(result)
            if (page.data.isEmpty()) break
            seenHeaders += page.data.filterIsInstance<TimelineItem.DayHeader>().map { it.date }
            seenEntries += page.data.filterIsInstance<TimelineItem.Entry>().map { it.item.record.id }
            pages += 1
            key = page.nextKey ?: break
            if (pages > 10) break   // 防死循环，正常 20 天 / 每页 7 天 = 3 页
        }

        assertEquals(3, pages)
        assertEquals("日期头不能重复", days, seenHeaders.size)
        assertEquals("日期头不能丢", days, seenHeaders.toSet().size)
        assertEquals("记录不能丢", days * perDay, seenEntries.size)
        assertEquals("记录不能重复", days * perDay, seenEntries.toSet().size)
        // 全局仍是倒序：最后一条日期头应该是最早那天
        assertEquals("2026-10-26", seenHeaders.last())
    }

    @Test
    fun `prevKey和nextKey正确`() = runTest {
        val source = TimelinePagingSource(FakeRecordDao(buildDays(days = 20, perDay = 1)))

        val first = LoadResultPageOf(source.load(refresh(null)))
        assertNull("第一页没有上一页", first.prevKey)
        assertEquals(1, first.nextKey)

        val second = LoadResultPageOf(source.load(append(1)))
        assertEquals(0, second.prevKey)
        assertEquals(2, second.nextKey)
    }

    @Test
    fun `不足一页时nextKey为null`() = runTest {
        val source = TimelinePagingSource(FakeRecordDao(buildDays(days = 3, perDay = 1)))
        val page = LoadResultPageOf(source.load(refresh(null)))
        assertNull(page.nextKey)
        assertEquals(3, page.data.count { it is TimelineItem.DayHeader })
    }

    @Test
    fun `已删除记录不进时间轴`() = runTest {
        val records = buildDays(days = 2, perDay = 2) + record("2026-11-13", deleted = true)
        val source = TimelinePagingSource(FakeRecordDao(records))
        val page = LoadResultPageOf(source.load(refresh(null)))

        assertEquals(2, page.data.count { it is TimelineItem.DayHeader })
        assertEquals(4, page.data.count { it is TimelineItem.Entry })
    }

    @Test
    fun `空库返回空页且无前后页`() = runTest {
        val source = TimelinePagingSource(FakeRecordDao(emptyList()))
        val page = LoadResultPageOf(source.load(refresh(null)))
        assertTrue(page.data.isEmpty())
        assertNull(page.prevKey)
        assertNull(page.nextKey)
    }

    @Test
    fun `无锚点时getRefreshKey返回null`() {
        val source = TimelinePagingSource(FakeRecordDao(emptyList()))
        val state = androidx.paging.PagingState<Int, TimelineItem>(
            pages = emptyList(),
            anchorPosition = null,
            config = androidx.paging.PagingConfig(pageSize = 7),
            leadingPlaceholderCount = 0
        )
        assertNull(source.getRefreshKey(state))
    }
}
