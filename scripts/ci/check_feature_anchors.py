#!/usr/bin/env python3
"""功能锚点守卫 —— 关键入口的引用数不得低于人工认定的下限。

## 为什么是锚点表，不是静态分析

两天里 CI 红了 6 次，其中 2 次是同类事故：解合并冲突时**只取了一侧的一部分**，
代码能编译但功能整块没了。

第一次（`ChatTopBarSection.kt`）删了 `val permissionModes` 却留下
`permissionModes.forEach {}` 空壳 → 编译失败，CI 抓住了。
第二次（`feat/lemon-inapp`）`showSessionResources` / `SessionResourcesSheet`
引用为 0，会话资源面板入口整段丢失 → **CI 一直是绿的**，没人发现。

想用静态分析兜住第二类，试了三次，全被误报否掉：

| 判据 | 误报 |
|---|---|
| 跨包顶层符号被引用但没 import | 302 处 |
| 悬空标识符（全仓唯一 + 无声明） | 6695 处（几乎全是 R.string 资源名） |
| 局部悬空接收者（`x.forEach` 而本文件无 `val x`） | 202 处（局部变量形态无解） |

根因统一：Kotlin 的局部声明（解构、lambda 参数、作用域函数、`by` 委托、
`remember`）无法用正则可靠还原；同包可见性又让「未 import」不等于「不可见」。
**会误报的守卫最终会被 baseline 绕过**，所以这条路放弃。

锚点表不推断，只比对人工认定的下限 —— 零误报的代价是需要人挑锚点。
挑法：真实丢过的东西优先，其次是不可替代的判定点（如 `SecurityGate`）。

## 用法

    python3 scripts/ci/check_feature_anchors.py                 # 校验
    python3 scripts/ci/check_feature_anchors.py --update-anchors # 收紧下限

`min` 只降不升。功能性删除（重构替代、入口迁移）必须同步下调并在
commit message 写明为什么 —— 这和架构棘轮是同一条纪律。

退出码：0 = 全部达标；1 = 有锚点低于下限。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANCHORS = Path(__file__).resolve().parent / "feature-anchors.json"
SCAN = [
    ROOT / "src/android/app/src/main/java",
    ROOT / "src/android/app/src/test/java",
    ROOT / "scripts/lemon",
    ROOT / "scripts/ci",
]


def iter_sources():
    for root in SCAN:
        if not root.is_dir():
            continue
        for ext in ("*.kt", "*.py"):
            yield from root.rglob(ext)


def count_symbol(name: str, blob: str) -> int:
    return len(re.findall(r"(?<![\w$])" + re.escape(name) + r"(?![\w$])", blob))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-anchors", action="store_true",
                    help="按实测值重写下限（只降不升，需在 commit 说明原因）")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    if not ANCHORS.is_file():
        print("feature-anchors: SKIP (no anchors file)")
        return 0

    doc = json.loads(ANCHORS.read_text(encoding="utf-8"))
    anchors = doc.get("anchors", [])
    if not anchors:
        print("feature-anchors: SKIP (empty anchors)")
        return 0

    files = list(iter_sources())
    if not files:
        print("feature-anchors: SKIP (no sources)")
        return 0

    blob = "\n".join(
        p.read_text(encoding="utf-8", errors="replace") for p in files
    )
    actual = {a["symbol"]: count_symbol(a["symbol"], blob) for a in anchors}

    if a.update_anchors:
        changed = []
        for anchor in anchors:
            sym, old, now = anchor["symbol"], anchor["min"], actual[anchor["symbol"]]
            if now < old:
                anchor["min"] = now
                changed.append(f"{sym}: {old} → {now}")
            elif now > old and a.verbose:
                print(f"  可收紧（未自动下调，需人确认）: {sym} 下限 {old} < 实测 {now}")
        ANCHORS.write_text(
            json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"feature-anchors: 已更新 {len(changed)} 条下限")
        for c in changed:
            print("  " + c)
        print("注意：只降不升。功能性删除请在 commit message 说明原因。")
        return 0

    violations = [
        (anchor["symbol"], actual[anchor["symbol"]], anchor["min"], anchor.get("why", ""))
        for anchor in anchors
        if actual[anchor["symbol"]] < anchor["min"]
    ]

    if violations:
        print(f"feature-anchors: {len(violations)} 个关键入口低于下限", file=sys.stderr)
        for sym, now, lo, why in violations:
            print(f"  {sym}: 实测 {now} < 下限 {lo}   [{why}]", file=sys.stderr)
        print(file=sys.stderr)
        print("若这是重构替代（入口换了名字/位置），把迁移写进 commit message，", file=sys.stderr)
        print("然后跑 --update-anchors 下调下限；否则这是功能丢失，请补回。", file=sys.stderr)
        return 1

    total = sum(actual.values())
    print(f"feature-anchors: OK ({len(anchors)} 个锚点 / {total} 处引用 / "
          f"{len(files)} 文件扫描)")
    if a.verbose:
        for anchor in anchors:
            print(f"  {actual[anchor['symbol']]:4d}  {anchor['symbol']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
