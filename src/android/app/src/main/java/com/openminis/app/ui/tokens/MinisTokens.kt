package com.openminis.app.ui.tokens

/**
 * 自动生成 —— 不要手改。生成器: refactor/tools/gen_tokens.py
 *
 * 扫描事实：420 个 ui 下的 .kt 文件 -> Color(0x...): 308 处 / 148 个不同色值；
 * dp 字面量 3522 处 / 83 个不同值；sp 363 处。
 * 本文件命名 25 个色值，当前覆盖 123/308 = 40% 的颜色使用点；
 * 未命名的色值留在原地，由 refactor/tools/check_ui_tokens.py 的棘轮逐步压制。
 *
 * 注意：常量全量输出，不因"源码里暂时没有使用者"而删除 —— 迁移完一个色值后
 * 源码里就不再有它，如果按频率裁剪，令牌会把自己删空。
 */

/** 物理色。命名规则=来源，不改语义。语义槽在 [MinisColors]。 */
object RawColors {
    const val systemGreen: Long = 0xFF34C759L  // x 21
    const val systemOrange: Long = 0xFFFF9500L  // x 22
    const val systemBlue: Long = 0xFF007AFFL  // x 16
    const val systemRed: Long = 0xFFFF3B30L  // x 9
    const val systemPink: Long = 0xFFFF2D55L  // x 2
    const val systemPurple: Long = 0xFF5856D6L  // x 3
    const val systemGray: Long = 0xFF8E8E93L  // x 4
    const val systemTeal: Long = 0xFF32ADE6L
    const val systemCyan: Long = 0xFF64D2FFL
    const val systemYellow: Long = 0xFFFFCC00L  // x 4
    const val systemPurpleAlt: Long = 0xFFAF52DEL  // x 2
    const val systemGreenAlt: Long = 0xFF30D158L  // x 1
    const val systemTealAlt: Long = 0xFF30B0C7L  // x 1
    const val sparkleWarm: Long = 0xFFB8B096L  // x 1
    const val sparkleStone: Long = 0xFF99998CL  // x 1
    const val neutral0: Long = 0xFFFFFFFFL  // x 8
    const val neutral50: Long = 0xFFF2F2F7L  // x 7
    const val neutral100: Long = 0xFFE2E2E6L  // x 3
    const val neutral200: Long = 0xFFC4C4C8L  // x 1
    const val neutral400: Long = 0xFF8E8E92L  // x 1
    const val neutral500: Long = 0xFF747477L  // x 1
    const val neutral600: Long = 0xFF444446L  // x 2
    const val neutral800: Long = 0xFF1C1C1EL  // x 5
    const val neutral900: Long = 0xFF16161AL
    const val neutral1000: Long = 0xFF000000L  // x 8

    // ─── 品牌色：厂商自身主色，不随动态取色变化 ─────────────────────────
    object Brand {
        const val zhipu: Long = 0xFF3859FFL
        const val deepseek: Long = 0xFF4D6BFEL
        const val claude: Long = 0xFFD97757L
        const val gemini: Long = 0xFF3186FFL
        const val wenxin: Long = 0xFF012F8DL
        const val minimax: Long = 0xFFE2167EL
        const val hailuo: Long = 0xFF1FA2B8L
        const val kimi: Long = 0xFF16161AL
        const val openai: Long = 0xFF16161AL
        const val grok: Long = 0xFF16161AL
        const val qwen: Long = 0xFF16161AL
        const val moonshot: Long = 0xFF16161AL
        const val meta: Long = 0xFF0668E1L
        const val mistral: Long = 0xFFFF7000L
        const val cohere: Long = 0xFF39594DL
        const val perplexity: Long = 0xFF20808DL
        const val openrouter: Long = 0xFF00A3C4L
        const val groq: Long = 0xFFF55036L
        const val baichuan: Long = 0xFF1A6DFFL
        const val hunyuan: Long = 0xFF0052D9L
    }

    object BrandDark {
        const val kimi: Long = 0xFF8FA8C8L
        const val openai: Long = 0xFFC8C8D2L
        const val grok: Long = 0xFFA8AEC0L
        const val qwen: Long = 0xFF9B8CF0L
        const val hailuo: Long = 0xFF7FC8D8L
        const val moonshot: Long = 0xFF8FA8C8L
        const val cohere: Long = 0xFF6FA893L
        const val mistral: Long = 0xFFFF9A4DL
        const val wenxin: Long = 0xFF5B7FD4L
        const val hunyuan: Long = 0xFF4D8AE8L
        const val baichuan: Long = 0xFF5B8CFFL
        const val meta: Long = 0xFF4C8DF0L
        const val groq: Long = 0xFFFF7A66L
        const val minimax: Long = 0xFFFF5AA5L
        const val zhipu: Long = 0xFF6D8BFFL
        const val deepseek: Long = 0xFF7A93FFL
        const val claude: Long = 0xFFE89070L
        const val gemini: Long = 0xFF6FA8FFL
    }

    val brandHashLight: List<Long> = listOf(
        0xFF5B8DEFL,
        0xFF3FA36AL,
        0xFFD4654AL,
        0xFF8A6AD6L,
        0xFFC48A2AL,
        0xFF3A9CA8L,
    )
    val brandHashDark: List<Long> = listOf(
        0xFF8AB4F8L,
        0xFF7BC98FL,
        0xFFE89478L,
        0xFFB79CE8L,
        0xFFE4B65CL,
        0xFF6FC4D0L,
    )
}

/** 间距 / 圆角 / 线宽原语（2dp 栅格）。未命名的值（如 3/5/7）由棘轮压制。 */
object RawSpace {
    const val xxs: Float = 2f  // x 159
    const val xs: Float = 4f  // x 268
    const val sm: Float = 6f  // x 202
    const val md: Float = 8f  // x 497
    const val md2: Float = 10f  // x 222
    const val lg: Float = 12f  // x 364
    const val lg2: Float = 14f  // x 208
    const val xl: Float = 16f  // x 426
    const val xl2: Float = 18f  // x 103
    const val xxl: Float = 20f  // x 173
    const val xxl2: Float = 22f  // x 31
    const val xxl3: Float = 24f  // x 94
    const val xxl4: Float = 28f  // x 34
    const val huge: Float = 32f  // x 87
    const val huge2: Float = 40f  // x 19
    const val giant: Float = 48f  // x 25
    const val giant2: Float = 56f  // x 31
    const val hairline: Float = 0.5f  // x 100
    const val thin: Float = 1f  // x 58
    const val thick: Float = 2f  // x 159
    const val touchTargetMin: Float = 48f  // 无障碍硬要求，不是审美
}

/** 字号原语。实测只有 8 个不同值，集中度极高，直接命名。 */
object RawType {
    const val cap2: Float = 9f  // x 13
    const val cap: Float = 11f  // x 70
    const val foot: Float = 12f  // x 64
    const val body: Float = 13f  // x 56
    const val bodyLg: Float = 14f  // x 36
    const val title: Float = 15f  // x 18
    const val titleLg: Float = 16f  // x 25
    const val head: Float = 18f  // x 13
}

/**
 * 语义色。取值来自 RawColors，但每个字段回答的是"用在哪"，而不是"是什么颜色"。
 * 换肤只改 Raw*，语义槽不动。
 */
data class MinisColors(
    val primary: Long,
    val onPrimary: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val separator: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val textTertiary: Long,
    val textDisabled: Long,
    val cardBg: Long,
    val cardBorder: Long,
    val inputBg: Long,
    val inputBorder: Long,
    val codeBlockBg: Long,
    val codeBlockText: Long,
    val inlineCodeBg: Long,
    val inlineCodeText: Long,
    val toolPending: Long,
    val toolRunning: Long,
    val toolSuccess: Long,
    val toolFailed: Long,
    val toolCancelled: Long,
    val statusInfo: Long,
    val statusWarn: Long,
    val statusDanger: Long,
    val statusWarnBg: Long,
    val categoryCode: Long,
    val categoryCreative: Long,
    val categoryResearch: Long,
    val categoryAffair: Long,
    val sendEnabled: Long,
    val sendDisabled: Long,
    val fabAccent: Long,
    val fabOnAccent: Long,
    val link: Long,
    val toastBg: Long,
    val brandOpenAI: Long,
    val brandGemini: Long,
    val brandFallback: Long,
) {
    companion object {
        /** 浅色方案。语义值直接引用 Raw*，改视觉只改 Raw*。 */
        val Light = MinisColors(
            primary = RawColors.systemBlue,
            onPrimary = RawColors.neutral0,
            background = RawColors.neutral50,
            onBackground = RawColors.neutral800,
            surface = RawColors.neutral0,
            onSurface = RawColors.neutral800,
            surfaceVariant = RawColors.neutral100,
            onSurfaceVariant = RawColors.neutral600,
            outline = RawColors.neutral500,
            separator = RawColors.neutral100,
            textPrimary = RawColors.neutral900,
            textSecondary = RawColors.neutral600,
            textTertiary = RawColors.neutral400,
            textDisabled = RawColors.neutral200,
            cardBg = RawColors.neutral0,
            cardBorder = RawColors.neutral100,
            inputBg = RawColors.neutral50,
            inputBorder = RawColors.neutral100,
            codeBlockBg = RawColors.neutral50,
            codeBlockText = RawColors.neutral900,
            inlineCodeBg = RawColors.neutral100,
            inlineCodeText = RawColors.neutral800,
            toolPending = RawColors.systemGray,
            toolRunning = RawColors.systemBlue,
            toolSuccess = RawColors.systemGreen,
            toolFailed = RawColors.systemRed,
            toolCancelled = RawColors.systemOrange,
            statusInfo = RawColors.systemBlue,
            statusWarn = RawColors.systemOrange,
            statusDanger = RawColors.systemRed,
            statusWarnBg = 0xFFFFF4E5L,
            categoryCode = RawColors.systemOrange,
            categoryCreative = RawColors.systemPink,
            categoryResearch = RawColors.systemTeal,
            categoryAffair = RawColors.systemPurple,
            sendEnabled = RawColors.systemBlue,
            sendDisabled = RawColors.neutral200,
            fabAccent = RawColors.systemBlue,
            fabOnAccent = RawColors.neutral0,
            link = RawColors.systemBlue,
            toastBg = 0xE61C1C1EL,
            brandOpenAI = RawColors.neutral900,
            brandGemini = RawColors.systemBlue,
            brandFallback = RawColors.systemGray,
        )

        /** 深色方案。与 Light 逐槽对应 —— 新增槽位漏填会编译报错，而不是运行期黑屏。 */
        val Dark = Light.copy(
            primary = 0xFFABC7FFL,
            onPrimary = 0xFF002D5FL,
            background = RawColors.neutral1000,
            onBackground = RawColors.neutral100,
            surface = RawColors.neutral1000,
            onSurface = RawColors.neutral100,
            surfaceVariant = RawColors.neutral600,
            onSurfaceVariant = RawColors.neutral200,
            outline = RawColors.neutral400,
            separator = RawColors.neutral600,
            textPrimary = RawColors.neutral100,
            textSecondary = RawColors.neutral200,
            textTertiary = RawColors.neutral400,
            textDisabled = RawColors.neutral600,
            cardBg = RawColors.neutral900,
            cardBorder = RawColors.neutral600,
            inputBg = RawColors.neutral900,
            inputBorder = RawColors.neutral600,
            codeBlockBg = RawColors.neutral900,
            codeBlockText = RawColors.neutral100,
            inlineCodeBg = RawColors.neutral600,
            inlineCodeText = RawColors.neutral100,
            statusWarnBg = 0xFF3A2A10L,
            sendDisabled = RawColors.neutral600,
            toastBg = 0xE6E2E2E6L,
            brandOpenAI = RawColors.neutral100,
            brandFallback = RawColors.neutral400,
        )
    }
}

/**
 * 语义尺寸。凡是"间距/圆角/线宽/触控"一律走这里。
 * 默认值即当前视觉；需要换密度时提供 alternate，而不是各处改 dp。
 */
data class MinisDimens(
    val spaceXxs: Float = 2f, val spaceXs: Float = 4f, val spaceSm: Float = 6f,
    val spaceMd: Float = 8f, val spaceMd2: Float = 10f, val spaceLg: Float = 12f,
    val spaceLg2: Float = 14f, val spaceXl: Float = 16f, val spaceXl2: Float = 18f,
    val spaceXxl: Float = 20f, val spaceXxl2: Float = 22f, val spaceXxl3: Float = 24f,
    val spaceXxl4: Float = 28f, val spaceHuge: Float = 32f, val spaceHuge2: Float = 40f,
    val spaceGiant: Float = 48f, val spaceGiant2: Float = 56f,
    val strokeHairline: Float = 0.5f, val strokeThin: Float = 1f, val strokeThick: Float = 2f,
    val touchTargetMin: Float = 48f,
    val radiusSm: Float = 8f, val radiusMd: Float = 12f, val radiusLg: Float = 16f,
    val radiusPill: Float = 999f,
) {
    companion object {
        /** 紧凑档：平板分栏 / 小屏横屏时整体缩一档间距，视觉比例不变。 */
        val Compact = MinisDimens(
            spaceSm = 4f, spaceMd = 6f, spaceLg = 8f, spaceXl = 12f, spaceXxl = 16f,
        )
    }
}

/** 字号 + 行高。行高按 1.35 比例生成 —— 单点定义，改一处全站一致。 */
data class MinisType(
    val cap2: Float = 9f, val cap: Float = 11f, val foot: Float = 12f,
    val body: Float = 13f, val bodyLg: Float = 14f, val title: Float = 15f,
    val titleLg: Float = 16f, val head: Float = 18f,
) {
    fun lineHeight(size: Float, ratio: Float = 1.35f): Float = size * ratio
    companion object {
        val Default = MinisType()
    }
}

/** 动效令牌。fast=120ms 用于 press，base=200ms 用于展开，slow=320ms 用于页面切换。 */
object MinisMotion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 320
}
