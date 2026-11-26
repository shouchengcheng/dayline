/*
 * Copyright 2026 The DayLine Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dayline.quick

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.dayline.MainActivity
import com.dayline.R
import com.dayline.domain.RecordSide

/**
 * 外部快捷入口（桌面小组件 / 长按图标快捷方式 / 通知栏磁贴）的统一协议。
 *
 * 所有快捷入口都直接拉起 [MainActivity] 并进入完整的编辑器（支持文字 / 图片 / 视频 / 标签），
 * 仅预选好左右侧别并自动聚焦输入框，做到「一步直达 + 能力完整」。
 */
object QuickEntry {
    const val ACTION_QUICK_ADD = "com.dayline.action.QUICK_ADD"
    const val EXTRA_SIDE = "com.dayline.extra.SIDE"

    private const val SHORTCUT_BENEFICIAL = "quick_add_beneficial"
    private const val SHORTCUT_BAD = "quick_add_bad"

    fun quickAddIntent(context: Context, side: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_QUICK_ADD
            putExtra(EXTRA_SIDE, RecordSide.of(side).value)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun openTimelineIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /** 从 Intent 中提取速记目标侧别；非速记 Intent 返回 null */
    fun extractSide(intent: Intent?): String? {
        if (intent?.action != ACTION_QUICK_ADD) return null
        return RecordSide.of(intent.getStringExtra(EXTRA_SIDE)).value
    }

    /**
     * 同步长按桌面图标的动态快捷方式（App Shortcuts）。
     * 用动态快捷方式而不是静态 XML：用户修改自定义分类名称后，长按菜单会立刻跟着变，且不会出现重复项。
     */
    fun syncDynamicShortcuts(context: Context, beneficialLabel: String, badLabel: String) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val beneficial = ShortcutInfo.Builder(context, SHORTCUT_BENEFICIAL)
            .setShortLabel("记 · $beneficialLabel")
            .setLongLabel("记一条「$beneficialLabel」")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_beneficial))
            .setIntent(quickAddIntent(context, RecordSide.BENEFICIAL.value))
            .setRank(0)
            .build()

        val bad = ShortcutInfo.Builder(context, SHORTCUT_BAD)
            .setShortLabel("记 · $badLabel")
            .setLongLabel("记一条「$badLabel」")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_bad))
            .setIntent(quickAddIntent(context, RecordSide.BAD.value))
            .setRank(1)
            .build()

        runCatching { manager.dynamicShortcuts = listOf(beneficial, bad) }
    }
}
