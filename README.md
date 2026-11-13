# DayLine（暖记）— 双栏日期时间轴记录 App

一条竖着的中轴把屏幕劈成两半：左边记「让我变好的事」，右边记「让我难受的事」，按天分组、倒序排列、往上无限翻。

- 完整开发方案：[`docs/开发方案.md`](docs/开发方案.md)（11 章 + Given/When/Then 验收清单）
- 技术栈：Kotlin 2.0.21 · Jetpack Compose (Material 3) · Room 2.6.1 · Paging 3.3.4 · DataStore 1.1.1 · Coil 2.7.0
- 最低系统：Android 8.0（API 26，为了原生 `java.time`）

## 构建

环境要求：JDK 17、Android SDK 35。

```bash
./gradlew :app:assembleDebug          # 出 APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # 26 个 JVM 单测（合并算法 / 命名 / zip-slip）
```

本机（Windows）已验证的路径：

```powershell
$env:JAVA_HOME="D:\cursor_workspace\.toolchain\jdk\jdk-17.0.20.1+1"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

`local.properties` 里的 `sdk.dir` 指向本机 Android SDK，已被 gitignore。

## 代码地图

| 目录 | 职责 |
|---|---|
| `data/db/` | 4 张表（records / tags / record_tag / media_files）、TypeConverter、3 个 DAO |
| `data/repo/TimelinePagingSource.kt` | **按天分页**，保证跨页边界的日期组头不重复 |
| `data/media/MediaStore.kt` | sha256 内容寻址、视频封面首帧提取 |
| `backup/MergeEngine.kt` | 导入合并的纯函数（id 合并 / 内容指纹去重 / 已删除只按 id） |
| `backup/ZipArchive.kt` | zip 读写 + 双层 zip-slip 防护 |
| `backup/BackupServices.kt` | 导出 / 导入（媒体先落盘、数据库单事务） |
| `ui/timeline/` | 双栏中轴时间轴、日期头、预加载触发 |
| `ui/editor/` `ui/trash/` `ui/settings/` | 编辑页 / 回收站 / 设置 |
| `ui/theme/` | 治愈暖色系（浅色 + 独立设计的深色方案） |

## 设计上的三个硬约束

1. **中轴必须连续**——日期头是真实列表项，不用 `stickyHeader`（浮层会切断轴线）。
2. **按天分页，不按记录分页**——否则跨页边界会重复插入同一天的头。
3. **只存相对路径**——媒体字节流不进 SQLite，DB 大小与媒体总量无关。

## 当前状态

| 项 | 状态 |
|---|---|
| 编译（`assembleDebug`） | ✅ 通过 |
| 单测（26 个） | ✅ 全通过 |
| DAO / Compose instrumentation 测试 | ⬜ 未写（见方案 §10.2） |
| 真机验收 | ⬜ 需设备（见方案 §11.2 验收清单） |
