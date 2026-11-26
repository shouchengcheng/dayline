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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dayline.data.db.RecordWithTags
import com.dayline.domain.RecordSide
import com.dayline.ui.components.ImageThumb
import com.dayline.ui.components.VideoThumb
import com.dayline.ui.motion.pressScale
import com.dayline.ui.motion.slideFadeIn
import com.dayline.ui.theme.WarmPalette
import com.dayline.util.Dates

private val AXIS_WIDTH = 44.dp

/** 卡片入场时从中轴外侧滑入的距离 */
private val ENTRANCE_OFFSET = 36.dp

/**
 * 一条记录的左右分栏行。
 *
 * 结构：`[左侧 weight=1] [中轴 44dp] [右侧 weight=1]`
 * - 有益 → 卡片放左，右侧留空；
 * - 糟糕 → 卡片放右，左侧留空。
 *
 * 取舍说明：不用 ConstraintLayout / 自定义 Layout，而是两个 weight(1f) 的槽位，
 * 因为 weight 会把中轴精确居中，且卡片宽度自适应屏幕，横竖屏都不用改。
 * 行高用 IntrinsicSize.Min + fillMaxHeight 让中轴竖线恰好贴合卡片高度，
 * 避免"竖线比卡片长一截"的割裂感。
 *
 * 动效：卡片从**自己那一侧**滑向中轴（左卡从左来、右卡从右来），中轴圆点随后弹出；
 * 竖线本身不做动画——轴必须始终连续，这是产品约束。
 * 入场进度只有一个 Animatable，卡片和圆点共用；已入场过的条目零开销（见 [rememberEntranceProgress]）。
 */
@Composable
internal fun TimelineRow(
    entry: RecordWithTags,
    beneficialLabel: String,
    badLabel: String,
    onOpen: () -> Unit,
    onMediaClick: (path: String, isVideo: Boolean) -> Unit,
    entrance: () -> Float = { 1f },
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val side = RecordSide.of(entry.record.side)
    val isLeft = side == RecordSide.BENEFICIAL
    val label = if (isLeft) beneficialLabel else badLabel
    val dark = com.dayline.ui.theme.LocalIsDarkTheme.current
    val colors = WarmPalette.side(entry.record.side, dark)
    val offsetPx = with(LocalDensity.current) { ENTRANCE_OFFSET.toPx() }
    val cardModifier = Modifier.slideFadeIn(entrance, fromPx = if (isLeft) -offsetPx else offsetPx)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 12.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            if (isLeft) {
                RecordCard(
                    entry = entry,
                    label = label,
                    colors = colors,
                    onOpen = onOpen,
                    onMediaClick = onMediaClick,
                    modifier = cardModifier
                )
            }
        }

        TimelineAxis(
            modifier = Modifier
                .width(AXIS_WIDTH)
                .fillMaxHeight(),
            dotColor = colors.accent,
            dotScale = entrance,
            pulse = highlight
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 12.dp),
            contentAlignment = Alignment.TopStart
        ) {
            if (!isLeft) {
                RecordCard(
                    entry = entry,
                    label = label,
                    colors = colors,
                    onOpen = onOpen,
                    onMediaClick = onMediaClick,
                    modifier = cardModifier
                )
            }
        }
    }
}

@Composable
private fun RecordCard(
    entry: RecordWithTags,
    label: String,
    colors: com.dayline.ui.theme.SideColors,
    onOpen: () -> Unit,
    onMediaClick: (path: String, isVideo: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val record = entry.record
    val interaction = remember { MutableInteractionSource() }

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container),
        border = BorderStroke(1.dp, colors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interaction,
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.97f)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // 侧别 + 时刻
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(colors.accent, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Dates.time(record.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (record.content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = record.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onContainer,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (record.imagePaths.isNotEmpty() || record.videoPaths.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MediaStrip(record = record, onMediaClick = onMediaClick)
            }

            if (entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entry.tags.joinToString("  ") { "#${it.name}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 卡片内媒体条：最多显示 3 个缩略图，多余的用 "+N" 角标提示（进编辑页看全）。
 * 取舍说明：卡片本身已经很小，超过 3 个会把卡片撑成正方形，破坏时间轴节奏。
 */
@Composable
private fun MediaStrip(
    record: com.dayline.data.db.RecordEntity,
    onMediaClick: (path: String, isVideo: Boolean) -> Unit
) {
    val total = record.imagePaths.size + record.videoPaths.size
    val shownCount = minOf(total, 3)
    val overflow = total - shownCount

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // 先图片后视频；视频要用「在 videoPaths 里的下标」取文件，而不是在混合列表里的位置
        // （否则「1 图 + 2 视频」时点第一个视频会打开第二个视频）
        for (i in 0 until shownCount) {
            val box = Modifier.size(58.dp)
            if (i < record.imagePaths.size) {
                val path = record.imagePaths[i]
                ImageThumb(
                    relativePath = path,
                    modifier = box,
                    onClick = { onMediaClick(path, false) }
                )
            } else {
                val v = i - record.imagePaths.size
                VideoThumb(
                    coverPath = record.videoCoverPaths.getOrNull(v)?.ifEmpty { null },
                    modifier = box,
                    onClick = { onMediaClick(record.videoPaths[v], true) }
                )
            }
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
