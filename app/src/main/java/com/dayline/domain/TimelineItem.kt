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
