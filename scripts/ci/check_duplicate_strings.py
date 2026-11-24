#!/usr/bin/env python3
"""守卫：`values*/strings.xml` 里同一个资源名不得定义两次。

## 为什么

Android 资源合并器对重复键是**致命错误**，不是警告：

    ERROR: values-zh/strings.xml: Resource and asset merger:
    Found item String/chat_menu_session_resources more than one time
    > Task :app:mergeReleaseResources FAILED

2026-11-24 真发生过：cherry-pick 一个 l10n 提交时，那个提交给 16 个语言包
各追加了 26 个键，其中 `chat_menu_session_resources` 在 `values-zh` 里
**已经存在**（更早的提交加的）。git 的自动合并把两条都留下了 ——
两边文本完全相同，所以没有冲突标记，人也不会注意到。

后果是 `mergeReleaseResources` 直接红，而**其他 21 项门禁全绿**：
`check_string_refs` 只验证引用能解析（重复定义反而更能解析），
`check_new_untranslated` 只数键在不在（重复也算在）。
这个空档只有资源合并器能发现，而它只在 CI 的 APK 构建里跑。

## 判据

对每个 `res/values*/strings.xml`，统计 `<string|plurals|string-array name="...">`
的出现次数，>1 即报。同目录其他 xml（`strings_settings.xml` 等）一并扫，
因为它们与 `strings.xml` 合并进同一个资源表。

零误报：资源名在单个文件里本来就必须唯一，这是 Android 的硬约束。

## 用法

    python3 scripts/ci/check_duplicate_strings.py [--verbose]

退出码：0 = 无重复；1 = 有重复；2 = 无 res 目录（跳过）。
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/android/app/src/main/res"

NAME_RE = re.compile(r'<(string|plurals|string-array)\s+name="([^"]+)"')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    if not RES.is_dir():
        print("duplicate-strings: SKIP (no res dir)")
        return 2

    problems: list[str] = []
    scanned = 0
    total_keys = 0

    for xml in sorted(RES.glob("values*/*.xml")):
        scanned += 1
        try:
            text = xml.read_text(encoding="utf-8")
        except OSError:
            continue

        # XML 本身是否合法（重复键在 XML 里是合法的，所以要另外查）
        try:
            ET.fromstring(text)
        except ET.ParseError as e:
            problems.append(f"{xml.relative_to(RES)}: XML 不合法（{e}）")
            continue

        seen: dict[str, int] = {}
        for m in NAME_RE.finditer(text):
            kind, name = m.group(1), m.group(2)
            key = f"{kind}/{name}"
            seen[key] = seen.get(key, 0) + 1
        total_keys += len(seen)

        dups = {k: v for k, v in seen.items() if v > 1}
        for key, count in sorted(dups.items()):
            line = next(
                (i + 1 for i, ln in enumerate(text.split("\n"))
                 if f'name="{key.split("/", 1)[1]}"' in ln),
                -1,
            )
            problems.append(
                f"{xml.relative_to(RES)}: {key} 定义了 {count} 次（首次约在第 {line} 行）"
            )

    if problems:
        print(f"duplicate-strings: {len(problems)} 处重复 —— 资源合并会直接失败", file=sys.stderr)
        for p in problems[:20]:
            print(f"  - {p}", file=sys.stderr)
        if len(problems) > 20:
            print(f"  … 另有 {len(problems) - 20} 处", file=sys.stderr)
        print(file=sys.stderr)
        print("合并冲突后同一资源名可能被两侧各留一条（文本相同所以无冲突标记）。", file=sys.stderr)
        print("删掉多余的一条即可；若两侧文本不同，需要人工决定保留哪个。", file=sys.stderr)
        return 1

    print(f"duplicate-strings: OK ({scanned} 个 xml / {total_keys} 个资源名 / 无重复)")
    if a.verbose:
        print(f"  扫描目录: {RES}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
