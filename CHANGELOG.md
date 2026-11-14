# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-11-14

### 新增

- 双栏日期时间轴：左侧「有益」/ 右侧「糟糕」，共用一条连续中轴，按天分组、跨天倒序
- 日期头：今天 / 昨天 / 前天 / 具体日期，跨年自动补年份
- 记录编辑：正文、侧别、日期、最多 9 张图片 + 3 个视频、多标签复用
- 媒体处理：SHA-256 内容寻址（同图只存一份）、视频首帧 720px 封面、列表只显示封面不自动播放
- 分页：按「天」分页 + 双路径预加载（`prefetchDistance` + UI 侧 600dp 触底提前量）
- 回收站：软删除、可撤销、保留期 7/30/90 天/永不，过期自动清理
- 备份导出：`records_yyyy-MM-dd.zip`（重名追加 `_1`、`_2`…），媒体用 STORED 不二次压缩
- 备份导入：按 id 合并 + 内容指纹去重 + 已删除记录只按 id 合并；单事务落库、媒体先落盘
- 安全：zip-slip 双层防护（条目名白名单 + 规范化路径二次校验）
- 主题：治愈暖色系浅色方案 + 独立设计的深色方案（非反色）
- 隐私：`allowBackup=false` + `data_extraction_rules.xml` 排除云备份与设备迁移
- 许可：Apache License 2.0，源码文件附 SPDX 标识

### 测试

- 34 个 JVM 单元测试全通过（`MergeEngineTest` 12 / `TimelinePagingSourceTest` 8 / `ZipArchiveTest` 8 / `NamingTest` 6）

### 已知限制

- 仅 debug 包，未配置签名
- 未写 instrumentation 测试（DAO / Compose UI 未覆盖）
- `OFFSET` 分页在超大数据量（约 5000 天以上）会线性扫描变慢

[1.0.0]: https://github.com/OWNER/dayline/releases/tag/v1.0.0
