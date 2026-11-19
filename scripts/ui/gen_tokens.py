#!/usr/bin/env python3
"""
从源码实测数据生成设计令牌的数据层（纯 JVM，无 Compose 依赖）。

为什么把令牌做成"纯数据 + 适配器"两层：
  Compose 的 Color/Dp 是 UI 类型，一旦令牌定义里出现它们，令牌层就无法在
  纯 JVM 上单测。所以这里只产出 ARGB Long 与 dp 的 Float，Compose 侧由
  ui/tokens/ComposeAdapters.kt 用扩展属性转成 Color/Dp。

关键设计：命名常量**无条件全量输出**，不按"当前是否还有源文件在用"裁剪。
  早期版本按出现次数裁剪，结果第一批文件迁移完成后该色值从源码里消失，
  令牌跟着消失，已迁移的引用立刻 unresolved —— 生成器把使用者自己删掉了。
  频率只用来排序和写注释，不用来决定是否存在。

用法：
  python3 tools/gen_tokens.py --ui <.../app/ui> --out gen
产出：
  gen/MinisTokens.kt        令牌对象（RawColors / RawSpace / MinisColors / MinisDimens / MinisType）
  gen/token_data.json       扫描事实（供文档与校验器复用）
"""
import argparse
import json
import os
import re
from collections import Counter

RE_HEX = re.compile(r'Color\(0x([0-9A-Fa-f]{8})\)')
RE_DP = re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.dp\b')
RE_SP = re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.sp\b')

# iOS HIG 系统色。这张表是**契约**，不是统计结果：名字一旦发布就不能删。
IOS_NAMES = {
    'FF34C759': 'systemGreen',
    'FFFF9500': 'systemOrange',
    'FF007AFF': 'systemBlue',
    'FFFF3B30': 'systemRed',
    'FFFF2D55': 'systemPink',
    'FF5856D6': 'systemPurple',
    'FF8E8E93': 'systemGray',
    'FF32ADE6': 'systemTeal',
    'FF64D2FF': 'systemCyan',
    'FFFFCC00': 'systemYellow',
    'FFAF52DE': 'systemPurpleAlt',
    'FF30D158': 'systemGreenAlt',
    'FF30B0C7': 'systemTealAlt',
    'FFB8B096': 'sparkleWarm',
    'FF99998C': 'sparkleStone',
}
NEUTRAL_NAMES = {
    'FFFFFFFF': 'neutral0',
    'FFF2F2F7': 'neutral50',
    'FFE2E2E6': 'neutral100',
    'FFC4C4C8': 'neutral200',
    'FF8E8E92': 'neutral400',
    'FF747477': 'neutral500',
    'FF444446': 'neutral600',
    'FF1C1C1E': 'neutral800',
    'FF16161A': 'neutral900',
    'FF000000': 'neutral1000',
}
# dp 命名：按实测频率赋名，保证高频值拿到短名字
SPACE_ORDER = ['2', '4', '6', '8', '10', '12', '14', '16',
               '18', '20', '22', '24', '28', '32', '40', '48', '56']
SPACE_NAMES = {
    '2': 'xxs', '4': 'xs', '6': 'sm', '8': 'md', '10': 'md2', '12': 'lg',
    '14': 'lg2', '16': 'xl', '18': 'xl2', '20': 'xxl', '22': 'xxl2',
    '24': 'xxl3', '28': 'xxl4', '32': 'huge', '40': 'huge2', '48': 'giant',
    '56': 'giant2',
}
STROKE = {'0.5': 'hairline', '1': 'thin', '2': 'thick'}
SP_NAMES = {'9': 'cap2', '11': 'cap', '12': 'foot', '13': 'body',
            '14': 'bodyLg', '15': 'title', '16': 'titleLg', '18': 'head'}

HERE = os.path.dirname(os.path.abspath(__file__))


def load_brand():
    """品牌色表：20 家厂商主色 + 18 条深色覆盖 + 各 6 条哈希兜底。"""
    p = os.path.join(HERE, 'brand_palette.json')
    if not os.path.exists(p):
        raise SystemExit('缺少 tools/brand_palette.json（由 BrandAccent.kt 抽取）')
    return json.load(open(p, encoding='utf-8'))


def scan(ui_root):
    colors, dps, sps, files = Counter(), Counter(), Counter(), 0
    for dirpath, _, names in os.walk(ui_root):
        for n in names:
            if not n.endswith('.kt'):
                continue
            files += 1
            s = open(os.path.join(dirpath, n), encoding='utf-8', errors='replace').read()
            for m in RE_HEX.finditer(s):
                colors[m.group(1).upper()] += 1
            for m in RE_DP.finditer(s):
                dps[m.group(1)] += 1
            for m in RE_SP.finditer(s):
                sps[m.group(1)] += 1
    return files, colors, dps, sps


def render(files, colors, dps, sps, brand):
    raw = []
    for hexv, name in IOS_NAMES.items():
        n = colors.get(hexv, 0)
        raw.append('    const val %s: Long = 0x%sL%s' % (name, hexv, '  // x %d' % n if n else ''))
    for hexv, name in NEUTRAL_NAMES.items():
        n = colors.get(hexv, 0)
        raw.append('    const val %s: Long = 0x%sL%s' % (name, hexv, '  // x %d' % n if n else ''))

    def slug(s):
        return re.sub(r'[^a-z0-9_]', '_', s)

    brand_light = '\n'.join('        const val %s: Long = 0x%sL' % (slug(s), h.upper())
                            for s, h in brand['light'])
    brand_dark = '\n'.join('        const val %s: Long = 0x%sL' % (slug(s), h.upper())
                           for s, h in brand['dark'])
    lh = '\n'.join('        0x%sL,' % h.upper() for h in brand['lh'])
    dh = '\n'.join('        0x%sL,' % h.upper() for h in brand['dh'])

    space = ['    const val %s: Float = %sf%s' % (SPACE_NAMES[v], v, '  // x %d' % dps[v] if v in dps else '')
             for v in SPACE_ORDER]
    space += ['    const val %s: Float = %sf%s' % (STROKE[v], v, '  // x %d' % dps[v] if v in dps else '')
              for v in STROKE]
    space.append('    const val touchTargetMin: Float = 48f  // 无障碍硬要求，不是审美')
    types = ['    const val %s: Float = %sf%s' % (SP_NAMES[v], v, '  // x %d' % sps[v] if v in sps else '')
             for v in SP_NAMES]

    named_total = sum(c for h, c in colors.items() if h in IOS_NAMES or h in NEUTRAL_NAMES)
    total = sum(colors.values())
    pct = round(named_total * 100 / total) if total else 0

    head = '''package com.openminis.app.ui.tokens

/**
 * 自动生成 —— 不要手改。生成器: refactor/tools/gen_tokens.py
 *
 * 扫描事实：%d 个 ui 下的 .kt 文件 -> Color(0x...): %d 处 / %d 个不同色值；
 * dp 字面量 %d 处 / %d 个不同值；sp %d 处。
 * 本文件命名 %d 个色值，当前覆盖 %d/%d = %d%% 的颜色使用点；
 * 未命名的色值留在原地，由 refactor/tools/check_ui_tokens.py 的棘轮逐步压制。
 *
 * 注意：常量全量输出，不因"源码里暂时没有使用者"而删除 —— 迁移完一个色值后
 * 源码里就不再有它，如果按频率裁剪，令牌会把自己删空。
 */

/** 物理色。命名规则=来源，不改语义。语义槽在 [MinisColors]。 */
object RawColors {
%s

    // ─── 品牌色：厂商自身主色，不随动态取色变化 ─────────────────────────
    object Brand {
%s
    }

    object BrandDark {
%s
    }

    val brandHashLight: List<Long> = listOf(
%s
    )
    val brandHashDark: List<Long> = listOf(
%s
    )
}

/** 间距 / 圆角 / 线宽原语（2dp 栅格）。未命名的值（如 3/5/7）由棘轮压制。 */
object RawSpace {
%s
}

/** 字号原语。实测只有 8 个不同值，集中度极高，直接命名。 */
object RawType {
%s
}
''' % (files, total, len(colors), sum(dps.values()), len(dps), sum(sps.values()),
       len(IOS_NAMES) + len(NEUTRAL_NAMES), named_total, total, pct,
       '\n'.join(raw), brand_light, brand_dark, lh, dh,
       '\n'.join(space), '\n'.join(types))

    return head + SEMANTIC

SEMANTIC = '''
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
    // ─── 编辑器卡片（文件编辑工具的 diff 视图）──────────────────────────
    // 独立于聊天气泡的灰阶：body 要比 sheet 容器沉一档，header 再比 body 亮一档，
    // 三段灰阶的层次关系是 iOS fileEditorContent 的观感契约，不是随手取的灰。
    val editorCardBg: Long,
    val editorCardHeaderBg: Long,
    val editorCardBorder: Long,
    val diffAddedBg: Long,
    val diffAddedText: Long,
    val diffRemovedBg: Long,
    val diffRemovedText: Long,
    // ─── 终端面板 ──────────────────────────────────────────────────────
    // 终端永远深底（连浅色主题也是），所以这三个槽两套主题同值。
    val terminalBg: Long,
    val terminalBorder: Long,
    val terminalLink: Long,
    val terminalHudBg: Long,
    // iOS CopyableURLCapsule: 固定白灰胶囊（white:0.85 填充 / white:0.35 文字），
    // 贴在终端深底上，不随主题翻转 —— 与 iOS 逐像素对齐。
    val urlCapsuleBg: Long,
    val urlCapsuleText: Long,
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
            editorCardBg = 0xFFF0F0F0L,
            editorCardHeaderBg = 0xFFEBEBEBL,
            editorCardBorder = 0xFFD1D1D1L,
            diffAddedBg = 0xFFE5FFE5L,
            // 原 iOS 值 #1A991A 在 #E5FFE5 上只有 3.52:1，低于 AA 正文 4.5。
            // 压暗到 #15801A 得 4.78:1 —— 色相不变，只是不再靠"绿得发亮"取胜。
            diffAddedText = 0xFF15801AL,
            diffRemovedBg = 0xFFFFE5E5L,
            diffRemovedText = 0xFFCC1A1AL,
            terminalBg = 0xFF000000L,
            terminalBorder = 0xFF404040L,
            terminalLink = 0xFF7CC4FFL,
            terminalHudBg = 0xFF141414L,
            urlCapsuleBg = 0xFFD9D9D9L,
            urlCapsuleText = 0xFF595959L,
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
            editorCardBg = 0xFF1A1A1AL,
            editorCardHeaderBg = 0xFF212121L,
            editorCardBorder = 0xFF404040L,
            diffAddedBg = 0xFF144D14L,
            diffAddedText = 0xFF66FF66L,
            diffRemovedBg = 0xFF4D1414L,
            diffRemovedText = 0xFFFF6666L,
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
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ui', required=True)
    ap.add_argument('--out', default='gen')
    a = ap.parse_args()

    files, colors, dps, sps = scan(a.ui)
    brand = load_brand()
    os.makedirs(a.out, exist_ok=True)
    open(os.path.join(a.out, 'MinisTokens.kt'), 'w', encoding='utf-8').write(
        render(files, colors, dps, sps, brand))

    named = sum(c for h, c in colors.items() if h in IOS_NAMES or h in NEUTRAL_NAMES)
    total = sum(colors.values())
    json.dump({
        'files': files,
        'colors': dict(colors.most_common()),
        'dp': dict(dps.most_common()),
        'sp': dict(sps.most_common()),
        'named_total': named,
        'named_pct': round(named * 100 / total) if total else 0,
        'brand_light': len(brand['light']),
        'brand_dark': len(brand['dark']),
    }, open(os.path.join(a.out, 'token_data.json'), 'w', encoding='utf-8'),
        ensure_ascii=False, indent=1)

    pct = round(named * 100 / total) if total else 0
    print('ui 文件      : %d' % files)
    print('颜色         : %d 处 / %d 个不同值  -> 命名覆盖 %d 处 (%d%%)'
          % (total, len(colors), named, pct))
    print('品牌色       : 浅色 %d / 深色 %d' % (len(brand['light']), len(brand['dark'])))
    print('dp           : %d 处 / %3d 个不同值' % (sum(dps.values()), len(dps)))
    print('sp           : %d 处 / %d 个不同值' % (sum(sps.values()), len(sps)))
    print('输出 -> %s/MinisTokens.kt + token_data.json' % a.out)


if __name__ == '__main__':
    main()
