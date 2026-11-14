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

package com.dayline.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dayline.data.db.RecordWithTags
import com.dayline.ui.theme.WarmPalette
import com.dayline.util.Dates

/**
 * 回收站。
 * 取舍说明：删除分两级——List 里的滑动/长按是"软删除"，进这里；
 * 这里才有"彻底删除"。彻底删除会连带删掉没有被引用的媒体文件，
 * 所以必须二次确认，且文案明确说明不可恢复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    items: List<RecordWithTags>,
    retentionDays: Int,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onPurgeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var purgeTarget by remember { mutableStateOf<String?>(null) }
    var confirmPurgeAll by remember { mutableStateOf(false) }

    if (purgeTarget != null) {
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("彻底删除？") },
            text = { Text("这条记录和它独占的图片/视频将被永久删除，无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    purgeTarget?.let(onPurge)
                    purgeTarget = null
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text("取消") } }
        )
    }

    if (confirmPurgeAll) {
        AlertDialog(
            onDismissRequest = { confirmPurgeAll = false },
            title = { Text("清空回收站？") },
            text = { Text("共 ${items.size} 条记录将被永久删除，无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmPurgeAll = false
                    onPurgeAll()
                }) { Text("全部删除") }
            },
            dismissButton = { TextButton(onClick = { confirmPurgeAll = false }) { Text("取消") } }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { confirmPurgeAll = true }) { Text("清空") }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = "保留 $retentionDays 天后自动清理；期间可随时恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("回收站是空的", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.record.id }) { item ->
                        TrashCard(
                            item = item,
                            onRestore = { onRestore(item.record.id) },
                            onPurge = { purgeTarget = item.record.id }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashCard(
    item: RecordWithTags,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.record.recordDate.let { d -> "${Dates.subtitle(d)}" },
                    style = MaterialTheme.typography.labelMedium,
                    color = WarmPalette.side(item.record.side, false).accent
                )
                Spacer(Modifier.weight(1f))
                item.record.deletedAt?.let {
                    Text(
                        text = "删除于 ${Dates.time(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.record.content.ifBlank { "（无文字，仅媒体）" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRestore) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(0.dp))
                    Text("  恢复")
                }
                TextButton(onClick = onPurge) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(16.dp)
                    )
                    Text("  彻底删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
