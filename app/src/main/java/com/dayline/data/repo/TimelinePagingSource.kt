package com.dayline.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.dayline.data.db.RecordDao
import com.dayline.domain.TimelineItem

/**
 * 以「天」为分页单位：
 * 1) 先查一页日期（DISTINCT recordDate，倒序）
 * 2) 再一次性取这些日期下的所有记录
 * 3) 在内存里把 DayHeader / Entry 交错拼好
 *
 * 为什么不是按记录分页：日期吸顶头需要知道「上一条是不是同一天」，
 * 按记录分页会让跨页边界上的组头无法稳定生成；按天分页天然没有这个问题。
 */
class TimelinePagingSource(
    private val dao: RecordDao,
    private val daysPerPage: Int = DEFAULT_DAYS_PER_PAGE
) : PagingSource<Int, TimelineItem>() {

    override val jumpingSupported: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineItem> {
        val page = params.key ?: 0
        return try {
            val dates = dao.pageDates(limit = daysPerPage, offset = page * daysPerPage)
            if (dates.isEmpty()) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }

            val records = dao.recordsForDates(dates)
            val byDate = records.groupBy { it.record.recordDate }

            val items = ArrayList<TimelineItem>(dates.size + records.size)
            for (date in dates) {
                items += TimelineItem.DayHeader(date)
                byDate[date]?.forEach { items += TimelineItem.Entry(it) }
            }

            LoadResult.Page(
                data = items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (dates.size < daysPerPage) null else page + 1
            )
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, TimelineItem>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
    }

    companion object {
        const val DEFAULT_DAYS_PER_PAGE = 7
    }
}
