/*
 * Copyright 2026 The DayLine Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dayline.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dayline.DaylineApp
import com.dayline.R
import com.dayline.domain.RecordSide
import com.dayline.util.Dates
import kotlinx.coroutines.launch

/**
 * 桌面双按钮速记小组件。
 *
 * 低内存取舍：直接用原生 RemoteViews，不引入 Glance 依赖；
 * 仅在有桌面实例时才响应数据变更刷新，平时零常驻进程。
 */
class DaylineWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as? DaylineApp
        if (app == null) {
            pending.finish()
            return
        }
        app.appScope.launch {
            try {
                val settings = app.container.settings.snapshot()
                val counts = app.container.db.recordDao().countsForDate(Dates.today())
                val beneficialCount = counts.firstOrNull { it.side == RecordSide.BENEFICIAL.value }?.count ?: 0
                val badCount = counts.firstOrNull { it.side == RecordSide.BAD.value }?.count ?: 0

                val views = buildViews(
                    context = context,
                    beneficialLabel = settings.beneficialLabel,
                    badLabel = settings.badLabel,
                    beneficialCount = beneficialCount,
                    badCount = badCount
                )
                for (id in appWidgetIds) {
                    appWidgetManager.updateAppWidget(id, views)
                }
            } catch (_: Throwable) {
                // 静默兜底，避免小组件广播异常导致系统禁掉 provider
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_DATE_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            requestUpdate(context)
        }
    }

    companion object {
        private const val REQ_OPEN_HOME = 100
        private const val REQ_ADD_BENEFICIAL = 101
        private const val REQ_ADD_BAD = 102

        /** 数据或分类名称变化时调用；若桌面没有放置小组件则直接返回，零开销 */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, DaylineWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, DaylineWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }

        private fun buildViews(
            context: Context,
            beneficialLabel: String,
            badLabel: String,
            beneficialCount: Int,
            badCount: Int
        ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_dayline).apply {
            val summary = if (beneficialCount == 0 && badCount == 0) {
                "今天还没记"
            } else {
                "$beneficialLabel $beneficialCount · $badLabel $badCount"
            }
            setTextViewText(R.id.widget_counts, summary)
            setTextViewText(R.id.widget_btn_beneficial, "+ $beneficialLabel")
            setTextViewText(R.id.widget_btn_bad, "+ $badLabel")

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            setOnClickPendingIntent(
                R.id.widget_header,
                PendingIntent.getActivity(context, REQ_OPEN_HOME, QuickEntry.openTimelineIntent(context), flags)
            )
            setOnClickPendingIntent(
                R.id.widget_btn_beneficial,
                PendingIntent.getActivity(
                    context,
                    REQ_ADD_BENEFICIAL,
                    QuickEntry.quickAddIntent(context, RecordSide.BENEFICIAL.value),
                    flags
                )
            )
            setOnClickPendingIntent(
                R.id.widget_btn_bad,
                PendingIntent.getActivity(
                    context,
                    REQ_ADD_BAD,
                    QuickEntry.quickAddIntent(context, RecordSide.BAD.value),
                    flags
                )
            )
        }
    }
}
