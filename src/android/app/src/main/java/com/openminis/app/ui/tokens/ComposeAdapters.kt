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

    /** 正文档字号。13sp 不在 M3 规范档位里（bodyMedium 14 / bodySmall 12），
     *  所以每个用它的地方都得手写 `13.sp` —— 全仓 54 处。这是令牌该接管的
     *  典型场景：值本身没错，错的是散落 54 份副本，改一次正文尺寸要改 54 处。 */
    val body: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.body.sp

    /** 次级正文。14sp，与 M3 bodyMedium 同值但语义独立（我们的档位不跟 M3 走）。 */
    val bodyLg: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.bodyLg.sp

    /** 注脚 / 辅助信息。12sp。 */
    val foot: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.foot.sp

    /** 小节标题 / 列表主行。11sp，与 M3 labelSmall 同值。 */
    val cap: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.cap.sp

    /** 等宽代码档字号（代码块、日志、路径、命令回显）。13sp。
     *
     *  为什么不能和 [body] 共用一个槽：等宽字体的字形宽度固定，13sp 等宽行
     *  比 13sp 比例字体宽约 12%，视觉重量也不一样。把它们绑成一个名字，将来
     *  有人调正文字号会把代码块一起推歪 —— 那是 13 处代码视图的排版事故，
     *  而正文改动本身是对的。值相同不等于语义相同。 */
    val code: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.body.sp

    /** 等宽小档（行内代码、终端标签、密集日志行）。11sp。
     *
     *  等宽 11 / 12 / 13 / 14 是四个独立档位，不是同一个档位的四舍五入。
     *  实测它们的用途完全分开：11sp 是行内 code span 与终端快捷键标签，
     *  12sp 是日志面板与 diff 行，13sp 是代码块正文，14sp 是 KaTeX 公式
     *  与用量统计里的等宽数值。合并成一个 `code` 会把 11sp 的密集日志
     *  推到 13sp，行数一样但密度掉一档 —— 日志那种一屏几十行的场景里
     *  一屏就少显示四五行。 */
    val codeSm: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.cap.sp

    /** 等宽小档（日志面板、diff 行号与内容）。12sp。见 [codeSm] 的档位说明。 */
    val codeFoot: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.foot.sp

    /** 等宽大档（公式渲染、统计数值）。14sp。见 [codeSm] 的档位说明。 */
    val codeLg: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.bodyLg.sp

    /** 输入框正文。16.5sp —— 比正文松半档，因为打字时光标需要呼吸空间。
     *  16.5 这个数不是随手取的：它刚好高于 16sp 的 `titleLg`，让输入区在
     *  视觉上比周围的说明文字更"在前"，又不至于跨进 18sp `head` 的标题感。 */
    val bodyInput: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.bodyInput.sp
}
