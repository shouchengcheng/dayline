/*
 * Copyright 2026 The DayLine Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dayline.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/**
 * 全局动效规范。
 *
 * 低内存取舍（所有动画都遵守）：
 * - **不引入任何新依赖**（不用 Lottie / 帧动画 / GIF），只用 Compose 自带的 animation-core；
 * - 位移 / 透明度 / 缩放一律走 `graphicsLayer {}` 的 lambda 版本，动画值只在**绘制阶段**读取，
 *   不触发重组、不分配新 Modifier；
 * - 无限循环动画只在「需要被看见」的那个节点里创建（最新一条的脉冲、空态插画），
 *   离开组合即销毁，不在后台空转；
 * - 不用 blur / shadow / 离屏 Bitmap，这些是动效里最吃内存的东西。
 */
object Motion {
    /** Material 3 emphasized-decelerate：进入时「先快后慢」，比默认 FastOutSlowIn 更柔 */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 180
    const val MEDIUM = 320
    const val LONG = 420

    /** 时间轴卡片入场：轻微回弹，但不晃 */
    val entranceSpec: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    /** 中轴圆点「啵」一下：比卡片更弹一点，形成先后层次 */
    val dotPopSpec: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    /** 列表项插入 / 删除 / 重排时的位置动画 */
    val placementSpec: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))

    // ---------------- 页面级转场 ----------------

    /** 共享 X 轴转场：往深一层 → 新页从右侧滑入；返回 → 从左侧滑入 */
    fun sharedAxisX(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        val enter = slideInHorizontally(tween(MEDIUM, easing = EmphasizedDecelerate)) { w -> dir * w / 6 } +
            fadeIn(tween(MEDIUM, delayMillis = 60))
        val exit = slideOutHorizontally(tween(MEDIUM, easing = EmphasizedAccelerate)) { w -> -dir * w / 8 } +
            fadeOut(tween(SHORT))
        return enter togetherWith exit
    }

    /** 全屏覆盖页（详情 / 编辑）：自下而上浮起 + 淡入 */
    val overlayEnter: EnterTransition =
        slideInVertically(tween(MEDIUM, easing = EmphasizedDecelerate)) { h -> h / 10 } +
            fadeIn(tween(SHORT)) +
            scaleIn(tween(MEDIUM, easing = EmphasizedDecelerate), initialScale = 0.98f)

    val overlayExit: ExitTransition =
        slideOutVertically(tween(SHORT, easing = EmphasizedAccelerate)) { h -> h / 14 } +
            fadeOut(tween(SHORT))

    /** 看大图：从中间放大淡入 */
    val viewerEnter: EnterTransition = fadeIn(tween(SHORT)) + scaleIn(tween(MEDIUM, easing = EmphasizedDecelerate), initialScale = 0.9f)
    val viewerExit: ExitTransition = fadeOut(tween(SHORT)) + scaleOut(tween(SHORT), targetScale = 0.94f)
}

/** 页面层级深度，用于判断转场方向 */
fun <S> AnimatedContentTransitionScope<S>.sharedAxisBy(depth: (S) -> Int): ContentTransform =
    Motion.sharedAxisX(forward = depth(targetState) >= depth(initialState))

/**
 * 有界的「已入场」集合：同一个 key 只播放一次入场动画，回滚到旧条目时不再重复播放。
 *
 * 取舍说明：用 access-order 的 LinkedHashMap 做 LRU，最多记住 [capacity] 个 key（每个只是一个
 * 已经在内存里的 String 引用），无限往前翻也不会让这个集合无限增长。
 */
class EntranceTracker(private val capacity: Int = 384) {
    private val createdAt = System.nanoTime()
    private val seen = object : LinkedHashMap<Any, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Unit>?) = size > capacity
    }

    /** 第一次见到返回 true，并记下 */
    fun markIfNew(key: Any): Boolean = seen.put(key, Unit) == null

    /**
     * 首屏的那一批条目按位置错峰入场（瀑布感）；之后滚动 / 新增的条目立即入场，不让用户等。
     */
    fun staggerMillis(index: Int): Long {
        val sinceStart = (System.nanoTime() - createdAt) / 1_000_000
        return if (sinceStart < 450) index.coerceAtMost(8) * 45L else 0L
    }
}

/**
 * 返回一个入场进度（0→1）的读取函数。已经入场过的 key 直接返回常量 1，
 * 不创建 Animatable、不启动协程——滚动回看旧内容时零开销。
 */
@Composable
fun rememberEntranceProgress(
    key: Any,
    index: Int,
    tracker: EntranceTracker,
    spec: FiniteAnimationSpec<Float> = Motion.entranceSpec
): () -> Float {
    val isNew = remember(key) { tracker.markIfNew(key) }
    if (!isNew) return FullyEntered
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        val d = tracker.staggerMillis(index)
        if (d > 0) delay(d)
        anim.animateTo(1f, spec)
    }
    return remember(anim) { { anim.value } }
}

private val FullyEntered: () -> Float = { 1f }

/**
 * 从侧边滑入 + 淡入 + 轻微放大。[fromPx] 为负表示从左侧来。
 * 只在绘制阶段读 progress，不触发重组。
 */
fun Modifier.slideFadeIn(progress: () -> Float, fromPx: Float, fromScale: Float = 0.94f): Modifier =
    graphicsLayer {
        val p = progress()
        alpha = p.coerceIn(0f, 1f)
        translationX = (1f - p) * fromPx
        val s = fromScale + (1f - fromScale) * p
        scaleX = s
        scaleY = s
    }

/** 按压回弹：按下缩到 [pressedScale]，松手弹回。 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 记住最后一个非空值：让 AnimatedVisibility 在退出动画期间仍有内容可画。
 * 用普通引用而不是 State，不会因为写入而引发额外重组。
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    val ref = remember { arrayOfNulls<Any>(1) }
    if (value != null) ref[0] = value
    @Suppress("UNCHECKED_CAST")
    return ref[0] as T?
}
