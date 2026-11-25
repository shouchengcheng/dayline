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

package com.dayline.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import com.dayline.ui.motion.Motion
import com.dayline.ui.motion.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dayline.domain.RecordSide
import com.dayline.ui.components.ImageThumb
import com.dayline.ui.components.VideoThumb
import com.dayline.ui.theme.SideColors
import com.dayline.ui.theme.WarmPalette
import com.dayline.util.Dates

/**
 * 编辑页状态。做成不可变数据 + 回调修改，ViewModel 只暴露一个 StateFlow，
 * UI 不持有可变状态，保存/失败不会出现半更新。
 */
data class EditorState(
    val id: String,
    val content: String,
    val side: String,
    val recordDate: String,
    val tagNames: List<String>,
    val imagePaths: List<String>,
    val videoPaths: List<String>,
    val videoCoverPaths: List<String>,
    val allTags: List<String>,
    val isNew: Boolean,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val picking: Boolean = false
) {
    val canSave: Boolean
        get() = !saving && !picking && (content.isNotBlank() || imagePaths.isNotEmpty() || videoPaths.isNotEmpty())
}

/**
 * 编辑页。用全屏 Container 而不是 Dialog：
 * 取舍说明：Dialog 在软键盘弹出时会被系统压缩高度，输入长文本体验很差；
 * 而且媒体九宫格在 Dialog 里放不下。全屏页配合 adjustResize 才稳。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    beneficialLabel: String,
    badLabel: String,
    onBack: () -> Unit,
    onChangeContent: (String) -> Unit,
    onChangeSide: (String) -> Unit,
    onChangeDate: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onPickImages: () -> Unit,
    onPickVideos: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveVideo: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var newTag by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移到回收站？") },
            text = { Text("记录会先进入回收站，可在保留期内恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("移到回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (state.isNew) "新记录" else "编辑记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = onSave, enabled = state.canSave) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SideSelector(
                side = state.side,
                beneficialLabel = beneficialLabel,
                badLabel = badLabel,
                onChange = onChangeSide
            )

            Spacer(Modifier.height(12.dp))
            DateRow(date = state.recordDate, onChange = onChangeDate)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.content,
                onValueChange = onChangeContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                placeholder = {
                    Text(
                        if (state.side == RecordSide.BAD.value) {
                            "今天什么让你不太好受？"
                        } else {
                            "今天做了什么让你变好一点？"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(Modifier.height(16.dp))
            Text("图片", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            // 以路径为 key，增删缩略图时其余项平滑让位（stableKeys 兜底重复路径，避免 key 冲突崩溃）
            val imageKeys = remember(state.imagePaths) { stableKeys("img", state.imagePaths) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.imagePaths.size, key = { imageKeys[it] }) { index ->
                    Box(Modifier.animateItem(placementSpec = Motion.placementSpec)) {
                        ImageThumb(relativePath = state.imagePaths[index], modifier = Modifier.size(88.dp))
                        RemoveBadge(onClick = { onRemoveImage(index) })
                    }
                }
                item(key = "add-image") {
                    AddTile(
                        icon = Icons.Default.Image,
                        label = "加图片",
                        onClick = onPickImages,
                        modifier = Modifier.animateItem(placementSpec = Motion.placementSpec)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("视频", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val videoKeys = remember(state.videoPaths) { stableKeys("vid", state.videoPaths) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.videoPaths.size, key = { videoKeys[it] }) { index ->
                    Box(Modifier.animateItem(placementSpec = Motion.placementSpec)) {
                        VideoThumb(
                            coverPath = state.videoCoverPaths.getOrNull(index),
                            modifier = Modifier.size(88.dp)
                        )
                        RemoveBadge(onClick = { onRemoveVideo(index) })
                    }
                }
                item(key = "add-video") {
                    AddTile(
                        icon = Icons.Default.Videocam,
                        label = "加视频",
                        onClick = onPickVideos,
                        modifier = Modifier.animateItem(placementSpec = Motion.placementSpec)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("标签", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (state.allTags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.allTags.forEach { name ->
                        FilterChip(
                            selected = name in state.tagNames,
                            onClick = { onToggleTag(name) },
                            label = { Text("#$name") }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it.take(16) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("新标签") },
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {
                        val t = newTag.trim()
                        if (t.isNotEmpty()) {
                            onAddTag(t)
                            newTag = ""
                        }
                    },
                    label = { Text("添加") },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (state.isNew) "记下来" else "保存修改")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SideSelector(
    side: String,
    beneficialLabel: String,
    badLabel: String,
    onChange: (String) -> Unit
) {
    val dark = MaterialTheme.colorScheme.background == WarmPalette.BackgroundDark
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SideOption(
            label = beneficialLabel,
            colors = WarmPalette.side(RecordSide.BENEFICIAL.value, dark),
            selected = side == RecordSide.BENEFICIAL.value,
            onClick = { onChange(RecordSide.BENEFICIAL.value) },
            modifier = Modifier.weight(1f)
        )
        SideOption(
            label = badLabel,
            colors = WarmPalette.side(RecordSide.BAD.value, dark),
            selected = side == RecordSide.BAD.value,
            onClick = { onChange(RecordSide.BAD.value) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SideOption(
    label: String,
    colors: SideColors,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 选中态切换时颜色 / 描边渐变，而不是瞬间跳变
    val spec = tween<Color>(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)
    val container by animateColorAsState(
        if (selected) colors.container else MaterialTheme.colorScheme.surfaceVariant, spec, label = "sideContainer"
    )
    val borderColor by animateColorAsState(
        if (selected) colors.accent else MaterialTheme.colorScheme.outlineVariant, spec, label = "sideBorder"
    )
    val textColor by animateColorAsState(
        if (selected) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant, spec, label = "sideText"
    )
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(Motion.SHORT), label = "sideBorderW")
    val interaction = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        modifier = modifier
            .pressScale(interaction)
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(width = borderWidth, color = borderColor),
        interactionSource = interaction
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        }
    }
}

@Composable
private fun DateRow(date: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${Dates.subtitle(date)}（${Dates.title(date)}）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onChange(Dates.shift(date, -1)) }) { Text("前一天") }
        TextButton(onClick = {
            val next = Dates.shift(date, 1)
            if (next <= Dates.today()) onChange(next)
        }) { Text("后一天") }
    }
}

@Composable
private fun RemoveBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun AddTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(88.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 路径 → 列表 key；同一路径第 n 次出现时追加序号，保证 key 唯一且对增删稳定 */
private fun stableKeys(prefix: String, paths: List<String>): List<String> {
    val seen = HashMap<String, Int>(paths.size)
    return paths.map { p ->
        val n = seen.merge(p, 1, Int::plus)!!
        if (n == 1) "$prefix:$p" else "$prefix:$p#$n"
    }
}
