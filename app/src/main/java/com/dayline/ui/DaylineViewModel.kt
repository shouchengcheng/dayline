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

package com.dayline.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.dayline.DaylineApp
import com.dayline.backup.Naming
import com.dayline.data.db.MediaFileEntity
import com.dayline.data.media.MediaStore
import com.dayline.data.prefs.SettingsState
import com.dayline.data.repo.RecordDraft
import com.dayline.domain.TimelineItem
import com.dayline.ui.editor.EditorState
import com.dayline.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 全应用共用一个 ViewModel。
 *
 * 取舍说明：这不是"偷懒"。时间轴、编辑页、回收站、设置页共享同一个 RecordRepository
 * 与 SettingsRepository，拆成四个 ViewModel 只会让"编辑页保存后时间轴不刷新"
 * 这类问题变得更难发现（各持一份 Paging 流）。页面数量少于 10 时，单 VM + 导航
 * 更简单也更好排查。若将来拆分，按 Repository 边界拆，不要按页面拆。
 */
class DaylineViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as DaylineApp).container
    private val repo = container.recordRepository
    private val media = container.media
    private val settingsRepo = container.settings

    /** Paging 流必须 cachedIn，否则每次重组都会重建 Pager、闪回第一页 */
    val timeline: Flow<PagingData<TimelineItem>> = repo.timeline.cachedIn(viewModelScope)

    /**
     * 设置流。用 stateIn 而不是手写 MutableStateFlow + 手动 collect：
     * 手写版本没有初始值语义，首帧会闪一帧"默认名称"。
     */
    val settings: StateFlow<SettingsState> = settingsRepo.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    val trash = repo.trash
    val allTags = repo.allTags
    val tagFilter: StateFlow<String?> = repo.tagFilter

    fun setTagFilter(tag: String?) = repo.setTagFilter(tag)


    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    /** 浏览模式（只读详情）当前展示的记录；默认点开条目进入这里 */
    private val _detail = MutableStateFlow<com.dayline.data.db.RecordWithTags?>(null)
    val detail: StateFlow<com.dayline.data.db.RecordWithTags?> = _detail.asStateFlow()

    fun openRecord(id: String) {
        viewModelScope.launch { _detail.value = repo.load(id) }
    }

    fun closeDetail() {
        _detail.value = null
    }

    private fun refreshDetail() {
        val id = _detail.value?.record?.id ?: return
        viewModelScope.launch {
            val item = repo.load(id)
            _detail.value = item?.takeIf { !it.record.isDeleted }
        }
    }

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _exportFile = MutableStateFlow<File?>(null)
    val exportFile: StateFlow<File?> = _exportFile.asStateFlow()

    // ---------------- 编辑 ----------------

    fun newRecord(side: String, date: String = Dates.today()) {
        viewModelScope.launch {
            val tags = runCatching { repo.allTags.firstValue() }.getOrDefault(emptyList())
            _editor.value = EditorState(
                id = UUID.randomUUID().toString(),
                content = "",
                side = side,
                recordDate = date,
                tagNames = emptyList(),
                imagePaths = emptyList(),
                videoPaths = emptyList(),
                videoCoverPaths = emptyList(),
                allTags = tags.map { it.name },
                isNew = true
            )
        }
    }

    /** 浏览页右上角「编辑」才进入编辑器 */
    fun editRecord(id: String) {
        viewModelScope.launch {
            val item = repo.load(id) ?: return@launch
            val tags = runCatching { repo.allTags.firstValue() }.getOrDefault(emptyList())
            _editor.value = EditorState(
                id = item.record.id,
                content = item.record.content,
                side = item.record.side,
                recordDate = item.record.recordDate,
                tagNames = item.tagNames,
                imagePaths = item.record.imagePaths,
                videoPaths = item.record.videoPaths,
                videoCoverPaths = item.record.videoCoverPaths,
                allTags = tags.map { it.name },
                isNew = false,
                loading = false
            )
        }
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun updateEditor(block: (EditorState) -> EditorState) {
        _editor.update { it?.let(block) }
    }

    fun toggleTag(name: String) = updateEditor { s ->
        if (name in s.tagNames) s.copy(tagNames = s.tagNames - name) else s.copy(tagNames = s.tagNames + name)
    }

    fun addTag(name: String) = updateEditor { s ->
        if (name in s.allTags) s.copy(tagNames = (s.tagNames + name).distinct())
        else s.copy(allTags = s.allTags + name, tagNames = (s.tagNames + name).distinct())
    }

    /**
     * 媒体导入：uri -> 私有目录（sha256 命名）+ 登记索引。
     * 每次导入都可能同一个文件被选两次，MediaStore 内部会按 sha 去重，这里只做去重拼接。
     */
    fun importMedia(uris: List<android.net.Uri>, asVideo: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            updateEditor { it.copy(picking = true) }
            var failed = 0
            for (uri in uris) {
                try {
                    val file = media.importFromUri(
                        uri,
                        if (asVideo) MediaStore.Kind.VIDEO else MediaStore.Kind.IMAGE
                    )
                    container.db.mediaDao().insert(
                        MediaFileEntity(file.sha256, file.relativePath, file.kind.name.lowercase(), file.sizeBytes, System.currentTimeMillis())
                    )
                    if (asVideo) {
                        val cover = media.ensureCover(file.relativePath, file.sha256)
                        updateEditor { s ->
                            s.copy(
                                videoPaths = (s.videoPaths + file.relativePath).distinct(),
                                videoCoverPaths = if (cover != null) (s.videoCoverPaths + cover).distinct() else s.videoCoverPaths
                            )
                        }
                    } else {
                        updateEditor { s -> s.copy(imagePaths = (s.imagePaths + file.relativePath).distinct()) }
                    }
                } catch (t: Throwable) {
                    failed++
                }
            }
            updateEditor { it.copy(picking = false) }
            if (failed > 0) _message.value = "有 $failed 个文件读取失败（可能被系统回收了权限）"
        }
    }

    fun removeImage(index: Int) = updateEditor { s ->
        s.copy(imagePaths = s.imagePaths.filterIndexed { i, _ -> i != index })
    }

    fun removeVideo(index: Int) = updateEditor { s ->
        s.copy(
            videoPaths = s.videoPaths.filterIndexed { i, _ -> i != index },
            videoCoverPaths = s.videoCoverPaths.filterIndexed { i, _ -> i != index }
        )
    }

    fun save() {
        val state = _editor.value ?: return
        if (!state.canSave) return
        viewModelScope.launch {
            _editor.update { it?.copy(saving = true) }
            try {
                repo.save(
                    RecordDraft(
                        id = state.id,
                        content = state.content,
                        side = state.side,
                        recordDate = state.recordDate,
                        tagNames = state.tagNames,
                        imagePaths = state.imagePaths,
                        videoPaths = state.videoPaths,
                        videoCoverPaths = state.videoCoverPaths
                    )
                )
                _editor.value = null
                _message.value = if (state.isNew) "已记下" else "已保存"
                refreshDetail()
            } catch (t: Throwable) {
                _editor.update { it?.copy(saving = false) }
                _message.value = "保存失败：${t.message}"
            }
        }
    }

    // ---------------- 回收站 ----------------

    fun softDelete(id: String) = viewModelScope.launch {
        repo.softDelete(id)
        _message.value = "已移到回收站"
    }

    fun undoDelete(id: String) = viewModelScope.launch {
        repo.restore(id)
        _message.value = "已恢复"
    }

    fun purge(id: String) = viewModelScope.launch { repo.purge(id) }

    fun purgeAll() = viewModelScope.launch {
        repo.purgeAllTrash()
        _message.value = "回收站已清空"
    }

    // ---------------- 设置 ----------------

    fun setLabels(beneficial: String, bad: String) = viewModelScope.launch {
        settingsRepo.setBeneficialLabel(beneficial)
        settingsRepo.setBadLabel(bad)
        _message.value = "分类名称已保存"
    }

    fun setRetention(days: Int) = viewModelScope.launch { settingsRepo.setRetentionDays(days) }

    /** 深色模式写入 DataStore；UI 通过 [settings] 的 darkMode 字段读取（null = 跟随系统） */
    fun toggleDark(on: Boolean) = viewModelScope.launch { settingsRepo.setDarkMode(on) }

    fun consumeMessage() {
        _message.value = null
    }

    fun clearExportFile() {
        _exportFile.value = null
    }

    // ---------------- 备份 ----------------

    fun export() = viewModelScope.launch {
        _busy.value = "准备导出…"
        try {
            val file = container.exportService.export { step -> _busy.value = step }
            _exportFile.value = file
            _message.value = "已生成 ${file.name}（${file.length() / 1024} KB）"
        } catch (t: Throwable) {
            _message.value = "导出失败：${t.message}"
        } finally {
            _busy.value = null
        }
    }

    fun importBackup(uri: android.net.Uri) = viewModelScope.launch {
        _busy.value = "正在读取备份包…"
        try {
            val tmp = withContext(Dispatchers.IO) {
                val f = File(getApplication<Application>().cacheDir, "incoming-${Naming.baseName()}.zip")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                } ?: error("无法读取所选文件")
                f
            }
            val report = container.importService.import(tmp) { step -> _busy.value = step }
            _message.value = report.summary()
            tmp.delete()
        } catch (t: Throwable) {
            _message.value = "导入失败：${t.message}"
        } finally {
            _busy.value = null
        }
    }
}

/** 取 Flow 当前值（只用于 allTags 这类小集合的一次性读取） */
private suspend fun <T> Flow<T>.firstValue(): T = this.first()
