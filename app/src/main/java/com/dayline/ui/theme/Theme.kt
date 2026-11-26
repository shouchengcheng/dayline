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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val WarmColorScheme = lightColorScheme(
    primary = WarmPalette.BeneficialAccent,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = WarmPalette.BeneficialContainer,
    onPrimaryContainer = WarmPalette.BeneficialOnContainer,
    secondary = WarmPalette.BadAccent,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = WarmPalette.BadContainer,
    onSecondaryContainer = WarmPalette.BadOnContainer,
    background = WarmPalette.Background,
    onBackground = WarmPalette.Ink,
    surface = WarmPalette.Surface,
    onSurface = WarmPalette.Ink,
    surfaceVariant = WarmPalette.SurfaceVariant,
    onSurfaceVariant = WarmPalette.InkSecondary,
    outline = WarmPalette.AxisLine,
    outlineVariant = WarmPalette.DayNodeStroke,
    error = WarmPalette.Danger,
    onError = androidx.compose.ui.graphics.Color.White
)

private val WarmShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 深色方案：不是把浅色取反，而是重新挑一组「深咖底 + 低饱和暖色块」。
 * 取舍说明：暖色在纯黑上会发荧光，所以底色用 #201A16 而不是 #000000；
 * 两侧卡片用比底色略亮一档的容器色，靠边框而不是靠亮度差去区分左右。
 */
private val WarmDarkColorScheme = darkColorScheme(
    primary = WarmPalette.BeneficialAccentDark,
    onPrimary = Color(0xFF1B2213),
    primaryContainer = WarmPalette.BeneficialContainerDark,
    onPrimaryContainer = WarmPalette.BeneficialOnContainerDark,
    secondary = WarmPalette.BadAccentDark,
    onSecondary = Color(0xFF2A1710),
    secondaryContainer = WarmPalette.BadContainerDark,
    onSecondaryContainer = WarmPalette.BadOnContainerDark,
    background = WarmPalette.BackgroundDark,
    onBackground = WarmPalette.InkDark,
    surface = WarmPalette.SurfaceDark,
    onSurface = WarmPalette.InkDark,
    surfaceVariant = WarmPalette.SurfaceVariantDark,
    onSurfaceVariant = WarmPalette.InkSecondaryDark,
    outline = WarmPalette.AxisLineDark,
    outlineVariant = WarmPalette.DayNodeStrokeDark,
    error = WarmPalette.DangerDark,
    onError = Color(0xFF2A1710)
)

@Composable
fun DaylineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) WarmDarkColorScheme else WarmColorScheme,
            typography = DaylineTypography,
            shapes = WarmShapes,
            content = content
        )
    }
}

/**
 * 当前是否深色主题。
 * 取舍说明：之前各页面用 `colorScheme.background == BackgroundDark` 反推，一旦调色就会悄悄失效；
 * 由主题直接下发布尔值更可靠，且 static local 只在切换主题时整树失效（本来就要全量重绘）。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** 供卡片自定义的额外色位（Material 3 里没有"左右两侧分别配色"这种语义） */
data class SideColors(
    val container: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color,
    val onContainer: androidx.compose.ui.graphics.Color
)

val LocalSideColors = staticCompositionLocalOf { WarmPalette.BeneficialAccent }
