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
            runCatching { container.recordRepository.purgeExpired(container.settings.snapshot().trashRetentionDays) }
        }
    }
}

class AppContainer(app: Application) {

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME)
        .fallbackToDestructiveMigration()  // v1 阶段：还没有需要保留的旧库
        .build()

    val media = MediaStore(app)
    val settings = SettingsRepository(app)
    val recordRepository = RecordRepository(app, db, media)
    val mergeApplier = MergeApplier(db)
    val exportService = ExportService(app, db, media, settings)
    val importService = ImportService(app, db, media, settings, mergeApplier)
}
