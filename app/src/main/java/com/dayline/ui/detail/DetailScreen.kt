/*
 * Copyright 2026 The DayLine Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dayline.ui.detail

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import com.dayline.ui.motion.Motion
import com.dayline.ui.motion.rememberLastNonNull
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.dayline.data.db.RecordWithTags
import com.dayline.domain.RecordSide
import com.dayline.ui.components.ImageThumb
import com.dayline.ui.components.VideoThumb
import com.dayline.ui.theme.WarmPalette
import com.dayline.util.Dates
import java.io.File

/**
 * 浏览模式（只读详情）。
 * 取舍说明：默认只读，避免在翻看旧记录时误触改动；必须点右上角「编辑」才进入编辑器。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    entry: RecordWithTags,
    beneficialLabel: String,
    badLabel: String,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val record = entry.record
    val isBad = record.side == RecordSide.BAD.value
    val dark = com.dayline.ui.theme.LocalIsDarkTheme.current
    val colors = WarmPalette.side(record.side, dark)

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var playingVideo by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = viewerIndex == null && playingVideo == null) { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(Dates.title(record.recordDate)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (isBad) badLabel else beneficialLabel, color = colors.accent, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${Dates.subtitle(record.recordDate)} ${Dates.time(record.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (record.content.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Text(record.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            if (record.imagePaths.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("图片 · ${record.imagePaths.size}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.imagePaths.forEachIndexed { i, p ->
                        ImageThumb(relativePath = p, modifier = Modifier.size(104.dp), onClick = { viewerIndex = i })
                    }
                }
            }

            if (record.videoPaths.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("视频 · ${record.videoPaths.size}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.videoPaths.forEachIndexed { i, p ->
                        VideoThumb(
                            coverPath = record.videoCoverPaths.getOrNull(i),
                            modifier = Modifier.size(104.dp),
                            onClick = { playingVideo = p }
                        )
                    }
                }
            }

            if (entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(entry.tags.joinToString("  ") { "#${it.name}" }, color = colors.accent, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    // 看大图 / 播视频：放大淡入、缩小淡出。退出期间保留最后一个值继续绘制。
    val shownViewer = rememberLastNonNull(viewerIndex)
    AnimatedVisibility(visible = viewerIndex != null, enter = Motion.viewerEnter, exit = Motion.viewerExit) {
        shownViewer?.let { start ->
            ImageViewer(paths = record.imagePaths, startIndex = start, onClose = { viewerIndex = null })
        }
    }
    val shownVideo = rememberLastNonNull(playingVideo)
    AnimatedVisibility(visible = playingVideo != null, enter = Motion.viewerEnter, exit = Motion.viewerExit) {
        shownVideo?.let { path ->
            VideoPlayer(relativePath = path, onClose = { playingVideo = null })
        }
    }
}

/** 全屏看图，左右滑动切换。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(paths: List<String>, startIndex: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex) { paths.size }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = File(context.filesDir, paths[page]),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            "${pager.currentPage + 1} / ${paths.size}",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
        }
    }
}

/** 应用内播放视频（系统 VideoView + 播放控制条）。 */
@Composable
private fun VideoPlayer(relativePath: String, onClose: () -> Unit) {
    val file = File(LocalContext.current.filesDir, relativePath)
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (file.isFile) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mc = MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
            )
        } else {
            Text("视频文件已丢失", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
        }
    }
}
