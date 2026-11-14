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

package com.dayline.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dayline.ui.theme.WarmPalette
import com.dayline.util.Dates

/**
 * 日期头。**是真实的列表项**，不是 stickyHeader。
 *
 * 取舍说明：
 * - 用 stickyHeader 时，吸顶头会浮在卡片之上，而"左右分栏"要求中轴连续，
 *   浮层会把轴线切断；且 stickyHeader 不参与 Paging 的 itemCount，
 *   越往上翻越容易和分页边界打架。
 * - 这里让它成为普通 item，靠横向贯穿的轴线 + 中间的日期胶囊保持视觉连续。
 */
@Composable
internal fun DayHeaderRow(date: String) {
    val axis = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左半段轴线
        Box(
            Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(axis)
        )

        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = Dates.title(date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = Dates.subtitle(date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(axis)
        )
    }
}

/**
 * 中轴：在每一条记录行中间画一条竖线，并在靠近卡片顶部处画一个连接点。
 *
 * 取舍说明：竖线用 drawBehind 而不是 Canvas，是为了不额外引入一层布局节点——
 * 时间轴一屏可能有 20+ 行，每行少一个节点，滚动掉帧会少一截。
 */
@Composable
internal fun TimelineAxis(
    modifier: Modifier = Modifier,
    dotColor: androidx.compose.ui.graphics.Color,
    showDot: Boolean = true
) {
    val lineColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier.drawBehind {
            val cx = size.width / 2f
            drawLine(
                color = lineColor,
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (showDot) {
                drawCircle(color = dotColor, radius = 4.dp.toPx(), center = Offset(cx, 40.dp.toPx()))
            }
        }
    )
}

/** 空态 */
@Composable
internal fun EmptyTimeline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(64.dp)
                .height(64.dp)
                .background(WarmPalette.SurfaceVariant, RoundedCornerShape(32.dp))
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "今天想记点什么？",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "左边的留给让你变好的事，右边的留给让你难受的事。\n点右下角的 + 开始。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {}
    }
}
