package com.dayline.domain

import com.dayline.data.db.RecordWithTags

/**
 * 时间轴上的两种行：日期吸顶头 + 记录。
 * DayHeader 作为真实列表项参与分页，才能既拿到日期吸顶、又不破坏 Paging 的预加载。
 */
sealed interface TimelineItem {
    val key: String

    data class DayHeader(val date: String) : TimelineItem {
        override val key: String get() = "h:$date"
    }

    data class Entry(val item: RecordWithTags) : TimelineItem {
        override val key: String get() = "r:${item.record.id}"
    }
}

enum class RecordSide(val value: String) {
    BENEFICIAL("beneficial"),
    BAD("bad");

    companion object {
        fun of(value: String?): RecordSide =
            if (value == BAD.value) BAD else BENEFICIAL
    }
}
