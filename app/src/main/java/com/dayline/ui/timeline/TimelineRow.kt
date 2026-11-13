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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dayline.data.db.RecordWithTags
import com.dayline.domain.RecordSide
import com.dayline.ui.components.ImageThumb
import com.dayline.ui.components.VideoThumb
import com.dayline.ui.theme.WarmPalette
import com.dayline.util.Dates

private val AXIS_WIDTH = 44.dp

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
 */
@Composable
internal fun TimelineRow(
    entry: RecordWithTags,
    beneficialLabel: String,
    badLabel: String,
    onOpen: () -> Unit,
    onMediaClick: (path: String, isVideo: Boolean) -> Unit
) {
    val side = RecordSide.of(entry.record.side)
    val isLeft = side == RecordSide.BENEFICIAL
    val label = if (isLeft) beneficialLabel else badLabel
    val dark = MaterialTheme.colorScheme.background == WarmPalette.BackgroundDark
    val colors = WarmPalette.side(entry.record.side, dark)

    Row(
        modifier = Modifier
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
                    onMediaClick = onMediaClick
                )
            }
        }

        TimelineAxis(
            modifier = Modifier
                .width(AXIS_WIDTH)
                .fillMaxHeight(),
            dotColor = colors.accent
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
                    onMediaClick = onMediaClick
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
    onMediaClick: (path: String, isVideo: Boolean) -> Unit
) {
    val record = entry.record

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container),
        border = BorderStroke(1.dp, colors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
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
    val items: List<Pair<String, Boolean>> = buildList {
        record.imagePaths.forEach { add(it to false) }
        record.videoPaths.forEachIndexed { index, path ->
            add((record.videoCoverPaths.getOrNull(index) ?: "") to true)
        }
    }
    val shown = items.take(3)
    val overflow = items.size - shown.size

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEachIndexed { index, (path, isVideo) ->
            val box = Modifier.size(58.dp)
            if (isVideo) {
                VideoThumb(
                    coverPath = path.ifEmpty { null },
                    modifier = box,
                    onClick = { onMediaClick(record.videoPaths[index.coerceAtMost(record.videoPaths.lastIndex)], true) }
                )
            } else {
                ImageThumb(
                    relativePath = path,
                    modifier = box,
                    onClick = { onMediaClick(path, false) }
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
