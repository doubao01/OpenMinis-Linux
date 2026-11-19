package com.openminis.app.ui.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 适配层 —— 让 [MinisTokens] 保持纯 Kotlin（可脱离 Android 编译/测试），
 * 只有这 40 行依赖 Compose。
 *
 * 为什么分两层：令牌文件由 `refactor/tools/gen_tokens.py` 生成，必须在 JVM 上
 * 单独编译通过（`kotlinc MinisTokens.kt` 即可）。把 Compose 类型塞进生成物会让
 * 生成物依赖 androidx，失去可验证性。这一层是薄的、手写的、只有转换逻辑。
 */

/** 0xAARRGGBB 的 Long → Compose Color。Compose 的 Int 重载按 ARGB 解释，语义一致。 */
val Long.asColor: Color get() = Color(this.toInt())

// ─── 语义色入口 ─────────────────────────────────────────────────────────────

val LocalMinisColors = staticCompositionLocalOf { MinisColors.Light }

/** 当前主题的语义色。用法：`MinisTheme.colors.toolSuccess.asColor` */
object MinisTheme {
    val colors: MinisColors
        @Composable @ReadOnlyComposable get() = LocalMinisColors.current
}

// ─── 尺寸 / 字号 ────────────────────────────────────────────────────────────

val Float.dpToken: Dp get() = this.dp
val Float.spToken: TextUnit get() = this.sp

val LocalMinisDimens = staticCompositionLocalOf { MinisDimens() }

object Minis {
    val dimens: MinisDimens
        @Composable @ReadOnlyComposable get() = LocalMinisDimens.current
}

// ─── 行高 / 字号阶梯 ────────────────────────────────────────────────────────

val LocalMinisType = staticCompositionLocalOf { MinisType() }

object MinisTypography {
    val type: MinisType
        @Composable @ReadOnlyComposable get() = LocalMinisType.current
}
