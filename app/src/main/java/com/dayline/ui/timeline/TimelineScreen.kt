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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.dayline.domain.TimelineItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 主时间轴。
 *
 * 预加载有两条路径，缺一不可：
 * 1) **PagingConfig.prefetchDistance**（数据层）——滚动到距末尾 N 条时自动请求下一页；
 * 2) **UI 侧触底提前量**（本文件）——列表剩余可滚动距离 < 600dp 时主动访问末项，
 *    让 Paging 立刻触发 append。第 2 条是给"用户一把甩到底"准备的，
 *    这时 prefetchDistance 往往还没来得及生效。
 *
 * 取舍说明：不用 `items.loadState.append` 当信号去手动 load（它只是个状态快照，
 * 赋值不会触发加载），真正的触发动作是**访问一个尚未加载的位置**，见下面 `items[last]`。
 */
@Composable
fun TimelineScreen(
    items: LazyPagingItems<TimelineItem>,
    beneficialLabel: String,
    badLabel: String,
    onItemClick: (String) -> Unit,
    onMediaClick: (path: String, isVideo: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val prefetchPx = with(LocalDensity.current) { 600.dp.toPx() }

    val shouldLoadMore by remember(prefetchPx) {
        derivedStateOf { computeShouldLoadMore(listState, prefetchPx) }
    }

    LaunchedEffect(items, shouldLoadMore) {
        if (!shouldLoadMore) return@LaunchedEffect
        val state = items.loadState
        if (state.append is LoadState.Loading || state.append is LoadState.Error) return@LaunchedEffect
        if (items.itemCount > 0) {
            // 访问末项 = 告诉 Paging「这里还需要数据」，会触发 append
            items[items.itemCount - 1]
        }
    }

    // 首屏不满一屏时（比如只有 2 条记录），上面那个条件可能只触发一次就不满足了，
    // 这里跟着布局变化再补一次，保证"底部没内容"不会卡住。
    LaunchedEffect(items) {
        snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.visibleItemsInfo.size }
            .distinctUntilChanged()
            .filter { (total, visible) -> total > 0 && visible >= total }
            .collect {
                val state = items.loadState
                if (state.append is LoadState.NotLoading && !state.append.endOfPaginationReached) {
                    items[items.itemCount - 1]
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            items.loadState.refresh is LoadState.Loading && items.itemCount == 0 ->
                LoadingState()

            items.loadState.refresh is LoadState.Error && items.itemCount == 0 ->
                ErrorState(
                    message = (items.loadState.refresh as LoadState.Error).error.message ?: "读取数据库失败",
                    onRetry = { items.retry() }
                )

            items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading ->
                EmptyTimeline()

            else -> LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                // reverseLayout 下首项在最底部：给 FAB 留出空间，今天最后一条不会被挡住
                item(key = "fab-space", contentType = "state") { Spacer(Modifier.height(96.dp)) }
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.key },
                    contentType = items.itemContentType { it::class }
                ) { index ->
                    when (val item = items[index]) {
                        is TimelineItem.DayHeader -> DayHeaderRow(date = item.date)
                        is TimelineItem.Entry -> TimelineRow(
                            entry = item.item,
                            beneficialLabel = beneficialLabel,
                            badLabel = badLabel,
                            onOpen = { onItemClick(item.item.record.id) },
                            onMediaClick = onMediaClick
                        )
                        null -> Spacer(Modifier.height(84.dp))
                    }
                }

                when (val append = items.loadState.append) {
                    is LoadState.Loading -> item(key = "append-loading", contentType = "state") { FooterLoading() }
                    is LoadState.Error -> item(key = "append-error", contentType = "state") {
                        FooterError(
                            message = append.error.message ?: "加载失败",
                            onRetry = { items.retry() }
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** 剩余可滚动距离 < threshold 时返回 true */
internal fun computeShouldLoadMore(state: LazyListState, thresholdPx: Float): Boolean {
    val layout = state.layoutInfo
    if (layout.totalItemsCount == 0) return false
    val last = layout.visibleItemsInfo.lastOrNull() ?: return true
    val pxLeft = layout.viewportEndOffset - (last.offset + last.size)
    val indexLeft = layout.totalItemsCount - 1 - last.index
    return indexLeft <= 2 || pxLeft <= thresholdPx
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = "正在翻你的日记…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "出错了：$message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun FooterLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
        Spacer(Modifier.height(0.dp))
        Text(
            text = "  继续往前翻…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FooterError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "加载失败：$message",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
