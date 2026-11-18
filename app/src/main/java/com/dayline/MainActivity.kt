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

package com.dayline

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.dayline.domain.RecordSide
import com.dayline.ui.DaylineViewModel
import com.dayline.ui.editor.EditorScreen
import com.dayline.ui.settings.SettingsScreen
import com.dayline.ui.theme.DaylineTheme
import com.dayline.ui.theme.WarmPalette
import com.dayline.ui.timeline.TimelineScreen
import com.dayline.ui.trash.TrashScreen
import kotlinx.coroutines.launch

/**
 * 唯一 Activity + 手写路由。
 *
 * 取舍说明：只有 4 个目的地，用 navigation-compose 会引入一个额外的 NavHost
 * 与序列化参数层，收益不明显。这里用一个 sealed Screen 状态机，
 * 配合 BackHandler 处理系统返回键——行为可预测，也便于测试。
 */
sealed interface Screen {
    data object Timeline : Screen
    data object Trash : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: DaylineViewModel = viewModel()
            val dark by vm.darkMode.collectAsStateWithLifecycle()
            DaylineTheme(darkTheme = dark) {
                DaylineRoot(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaylineRoot(vm: DaylineViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val exportFile by vm.exportFile.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle(initialValue = emptyList())

    var screen by remember { mutableStateOf<Screen>(Screen.Timeline) }
    var showSidePicker by remember { mutableStateOf(false) }

    // ---- 一次性消息 -> Snackbar ----
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
        vm.consumeMessage()
    }

    // ---- 导出完成后立刻拉起系统分享 ----
    LaunchedEffect(exportFile) {
        val file = exportFile ?: return@LaunchedEffect
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "保存/发送备份"))
        }.onFailure {
            snackbar.showSnackbar("分享失败，文件仍在缓存目录：${file.name}")
        }
        vm.clearExportFile()
    }

    // ---- 系统返回键 ----
    BackHandler(enabled = editor != null || screen != Screen.Timeline || showSidePicker) {
        when {
            showSidePicker -> showSidePicker = false
            editor != null -> vm.closeEditor()
            else -> screen = Screen.Timeline
        }
    }

    // ---- 图片 / 视频选择 ----
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> vm.importMedia(uris, asVideo = false) }

    val pickVideos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris -> vm.importMedia(uris, asVideo = true) }

    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importBackup(it) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (screen) {
            Screen.Timeline -> TimelineHome(
                vm = vm,
                settings = settings,
                snackbar = snackbar,
                onOpenSettings = { screen = Screen.Settings },
                onNewRecord = { vm.newRecord(RecordSide.BENEFICIAL.value) }
            )

            Screen.Trash -> TrashScreen(
                items = trash,
                retentionDays = settings.trashRetentionDays,
                onBack = { screen = Screen.Timeline },
                onRestore = { vm.undoDelete(it) },
                onPurge = { vm.purge(it) },
                onPurgeAll = { vm.purgeAll() }
            )

            Screen.Settings -> SettingsScreen(
                state = settings,
                darkMode = vm.darkMode.collectAsState().value,
                busy = busy,
                onBack = { screen = Screen.Timeline },
                onSaveLabels = vm::setLabels,
                onToggleDark = vm::toggleDark,
                onRetentionDays = vm::setRetention,
                onExport = vm::export,
                onImport = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) },
                onOpenTrash = { screen = Screen.Trash },
                message = null,
                onMessageShown = {}
            )
        }

        // ---- 编辑页（全屏覆盖，带自己的 Scaffold）----
        // ---- 浏览模式：编辑器打开时隐藏，保存/取消后回到浏览 ----
        if (editor == null) {
            detail?.let { item ->
                com.dayline.ui.detail.DetailScreen(
                    entry = item,
                    beneficialLabel = settings.beneficialLabel,
                    badLabel = settings.badLabel,
                    onBack = vm::closeDetail,
                    onEdit = { vm.editRecord(item.record.id) }
                )
            }
        }

        editor?.let { state ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                EditorScreen(
                    state = state,
                    beneficialLabel = settings.beneficialLabel,
                    badLabel = settings.badLabel,
                    onBack = vm::closeEditor,
                    onChangeContent = { v -> vm.updateEditor { it.copy(content = v) } },
                    onChangeSide = { v -> vm.updateEditor { it.copy(side = v) } },
                    onChangeDate = { v -> vm.updateEditor { it.copy(recordDate = v) } },
                    onToggleTag = vm::toggleTag,
                    onAddTag = vm::addTag,
                    onPickImages = {
                        if (state.imagePaths.size >= 9) {
                            scope.launch { snackbar.showSnackbar("最多 9 张图片") }
                        } else pickImages.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onPickVideos = {
                        pickVideos.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly
                            )
                        )
                    },
                    onRemoveImage = vm::removeImage,
                    onRemoveVideo = vm::removeVideo,
                    onSave = vm::save,
                    onDelete = if (state.isNew) null else {
                        {
                            vm.softDelete(state.id)
                            vm.closeEditor()
                            vm.closeDetail()
                        }
                    }
                )
            }
        }

        // ---- “记在左边还是右边”底部选择 ----
        if (showSidePicker) {
            SidePickerSheet(
                beneficialLabel = settings.beneficialLabel,
                badLabel = settings.badLabel,
                onPick = { side ->
                    showSidePicker = false
                    vm.newRecord(side)
                },
                onDismiss = { showSidePicker = false }
            )
        }

        // 编辑页自带 SnackbarHost 会挡住全屏；这里对外层 snackbar 给一个可见位置
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (editor == null) 72.dp else 0.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineHome(
    vm: DaylineViewModel,
    settings: com.dayline.data.prefs.SettingsState,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onNewRecord: () -> Unit
) {
    val items = vm.timeline.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openMedia(path: String, isVideo: Boolean) {
        runCatching {
            val file = java.io.File(context.filesDir, path)
            if (!file.isFile) {
                scope.launch { snackbar.showSnackbar("文件已丢失：${file.name}") }
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (isVideo) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }.onFailure {
            scope.launch { snackbar.showSnackbar("没有能打开该文件的应用") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DayLine", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${settings.beneficialLabel}  ·  ${settings.badLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { items.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecord,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("记一笔") },
                containerColor = WarmPalette.BeneficialAccent,
                contentColor = androidx.compose.ui.graphics.Color.White
            )
        }
    ) { padding ->
        TimelineScreen(
            items = items,
            beneficialLabel = settings.beneficialLabel,
            badLabel = settings.badLabel,
            onItemClick = { id -> vm.openRecord(id) },
            onMediaClick = { path, isVideo -> openMedia(path, isVideo) },
            modifier = Modifier.padding(padding)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidePickerSheet(
    beneficialLabel: String,
    badLabel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("记在哪一边？", style = MaterialTheme.typography.headlineSmall)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
            Text(
                "左边是你做对的事，右边是让你难受的事。\n两边合起来才是一天。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 20.dp))
            SideButton(
                label = beneficialLabel,
                colors = WarmPalette.side(RecordSide.BENEFICIAL.value, false),
                onClick = { onPick(RecordSide.BENEFICIAL.value) }
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
            SideButton(
                label = badLabel,
                colors = WarmPalette.side(RecordSide.BAD.value, false),
                onClick = { onPick(RecordSide.BAD.value) }
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    colors: com.dayline.ui.theme.SideColors,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .padding(0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = colors.container,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
        )
    }
}
