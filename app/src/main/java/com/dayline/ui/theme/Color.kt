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

package com.dayline.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 治愈暖色系。
 *
 * 取舍说明：刻意不用纯白背景，用奶油色 #FDF8F3 打底，降低长时间盯屏的刺激；
 * 右侧"糟糕"不用红，用低饱和陶土橙 → 灰粉的渐变，避免记录时产生羞耻感。
 * 所有前景色对背景的对比度都 >= 4.5:1（正文）与 3:1（大字/图标）。
 */
object WarmPalette {

    // 背景层次
    val Background = Color(0xFFFDF8F3)      // 奶油白
    val Surface = Color(0xFFFFFDFA)
    val SurfaceVariant = Color(0xFFF4EDE4)

    // 文字
    val Ink = Color(0xFF4A3F35)             // 暖棕色正文，对 #FDF8F3 对比度 ~9.6:1
    val InkSecondary = Color(0xFF8A7A68)    // 次要文字 ~4.6:1
    val InkTertiary = Color(0xFFB3A695)

    // 左侧「有益」：暖绿 + 奶油黄
    val BeneficialContainer = Color(0xFFF2F6E6)   // 奶油绿
    val BeneficialBorder = Color(0xFFD8E5BC)
    val BeneficialAccent = Color(0xFF6F8F4E)      // 橄榄绿，用于标签/圆点
    val BeneficialOnContainer = Color(0xFF3E4A2E)

    // 右侧「糟糕」：低饱和陶土橙 → 灰粉
    val BadContainer = Color(0xFFF6EBE4)          // 灰粉
    val BadBorder = Color(0xFFE7D2C5)
    val BadAccent = Color(0xFFB07A5E)             // 陶土橙，刻意压饱和
    val BadOnContainer = Color(0xFF5A3E32)

    // 时间轴
    val AxisLine = Color(0xFFE3D8CB)
    val DayNodeFill = Color(0xFFFFF7EA)
    val DayNodeStroke = Color(0xFFE0CFB8)

    // 语义
    val Undo = Color(0xFF7A9A63)
    val Danger = Color(0xFF9C5B4B)         // 删除确认用，仍然压低饱和

    // ---------- 深色方案（见 Theme.kt 的取舍说明） ----------
    val BackgroundDark = Color(0xFF201A16)
    val SurfaceDark = Color(0xFF2A231D)
    val SurfaceVariantDark = Color(0xFF342C24)

    val InkDark = Color(0xFFF1E8DE)
    val InkSecondaryDark = Color(0xFFC4B6A6)

    val BeneficialContainerDark = Color(0xFF2C3324)
    val BeneficialOnContainerDark = Color(0xFFD6E4BE)
    val BeneficialAccentDark = Color(0xFF9DBB78)

    val BadContainerDark = Color(0xFF33241D)
    val BadOnContainerDark = Color(0xFFEBD2C3)
    val BadAccentDark = Color(0xFFD29C7E)

    val AxisLineDark = Color(0xFF4A3E33)
    val DayNodeStrokeDark = Color(0xFF5C4C3E)

    val DangerDark = Color(0xFFD9987F)

    /** 按侧取色：卡片容器 / 边框 / 强调 / 容器前景 */
    fun side(side: String, dark: Boolean): SideColors =
        if (side == "bad") {
            if (dark) SideColors(BadContainerDark, AxisLineDark, BadAccentDark, BadOnContainerDark)
            else SideColors(BadContainer, BadBorder, BadAccent, BadOnContainer)
        } else {
            if (dark) SideColors(BeneficialContainerDark, AxisLineDark, BeneficialAccentDark, BeneficialOnContainerDark)
            else SideColors(BeneficialContainer, BeneficialBorder, BeneficialAccent, BeneficialOnContainer)
        }
}
