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

package com.dayline.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * 图片缩略图。
 *
 * 取舍说明：
 * - `size(px)` 显式给出解码尺寸，否则 Coil 会按原图解码再缩，
 *   一张 4000x3000 的照片就吃掉几十 MB 峰值内存。
 * - memoryCacheKey 用「相对路径 @ 尺寸」，同一张图在列表/编辑器两种尺寸下各自缓存，
 *   不会互相踢掉。
 */
@Composable
fun ImageThumb(
    relativePath: String,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val file = remember(relativePath) { File(context.filesDir, relativePath) }
    val px = remember { 300 }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                .size(px)
                .crossfade(true)
                .memoryCacheKey("thumb:$relativePath@$px")
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 视频缩略图：**永远只显示封面帧 + 播放角标**，列表里不自动播放。
 * 封面为 null（首帧提取失败/老数据没有封面）时显示占位图标，点一下仍能播。
 */
@Composable
fun VideoThumb(
    coverPath: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val file = remember(coverPath) { coverPath?.let { File(context.filesDir, it) } }
    val px = remember { 300 }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (file != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .size(px)
                    .crossfade(true)
                    .memoryCacheKey("cover:${coverPath}@$px")
                    .build(),
                contentDescription = "视频封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        // 播放角标：半透明黑底圆，保证在任何封面上都看得清
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 私有目录里文件是否存在（不做 IO，只 stat 一次） */
fun mediaFileExists(context: Context, relativePath: String): Boolean =
    File(context.filesDir, relativePath).isFile
