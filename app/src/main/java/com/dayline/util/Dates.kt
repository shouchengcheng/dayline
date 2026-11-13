package com.dayline.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 日期格式化集中处。
 *
 * 取舍说明：recordDate 在数据库里就是 `yyyy-MM-dd` 字符串（天粒度），
 * 不做「时间戳 + 时区换算」。原因：用户关心的「哪一天」是本地日历日，
 * 用毫秒时间戳存会在跨时区/夏令时下把同一天切成两天，日期吸顶头就会重复出现。
 */
object Dates {

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun today(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).toString()

    fun parse(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

    /** 卡片右下角的时刻 */
    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * 日期头主标题：今天 / 昨天 / 前天，再往前退回「11月14日」；
     * 跨年时补年份，避免"1月3日"看不出是哪一年。
     */
    fun title(iso: String, today: LocalDate = LocalDate.now()): String {
        val d = parse(iso) ?: return iso
        return when (ChronoUnit.DAYS.between(d, today)) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            else -> monthDay(d, today)
        }
    }

    /** 日期头副标题：always 具体日期 + 星期，让"今天"也能看到是哪天 */
    fun subtitle(iso: String): String {
        val d = parse(iso) ?: return ""
        val week = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
        return "${monthDay(d, LocalDate.now())} · $week"
    }

    fun weekday(iso: String): String =
        parse(iso)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.CHINA) ?: ""

    fun shift(iso: String, days: Long): String =
        parse(iso)?.plusDays(days)?.toString() ?: iso

    private fun monthDay(d: LocalDate, today: LocalDate): String =
        if (d.year == today.year) "${d.monthValue}月${d.dayOfMonth}日"
        else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
}
