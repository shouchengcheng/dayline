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

import android.app.Application
import androidx.room.Room
import com.dayline.backup.ExportService
import com.dayline.backup.ImportService
import com.dayline.data.db.AppDatabase
import com.dayline.data.media.MediaStore
import com.dayline.data.prefs.SettingsRepository
import com.dayline.data.repo.MergeApplier
import com.dayline.data.repo.RecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写依赖容器。
 *
 * 取舍说明：**不引入 Hilt**。本 App 的依赖图只有 8 个对象、一条链，
 * Hilt 带来的 kapt/ksp 生成、编译时间和"为什么注入不上"的排查成本，
 * 换不来对应的收益。等真正出现多模块 / 多作用域时再迁移，迁移点就在这个文件。
 */
class DaylineApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            runCatching {
                val snap = container.settings.snapshot()
                com.dayline.quick.QuickEntry.syncDynamicShortcuts(this@DaylineApp, snap.beneficialLabel, snap.badLabel)
                com.dayline.quick.DaylineWidgetProvider.requestUpdate(this@DaylineApp)
                container.recordRepository.purgeExpired(snap.trashRetentionDays)
            }
        }
    }
}

class AppContainer(app: Application) {

    /**
     * 取舍说明：**不再** fallbackToDestructiveMigration()。
     * 这是纯离线日记，没有云端副本——升级 schema 时一旦缺 Migration 就清库，等于静默删光用户日记。
     * 现在缺 Migration 会直接崩溃，在开发 / 测试阶段就能暴露出来；schema 已通过 exportSchema 导出。
     */
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME)
        .build()

    val media = MediaStore(app)
    val settings = SettingsRepository(app)
    val recordRepository = RecordRepository(app, db, media)
    val mergeApplier = MergeApplier(db)
    val exportService = ExportService(app, db, media, settings)
    val importService = ImportService(app, db, media, settings, mergeApplier)
}
