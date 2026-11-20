#!/usr/bin/env python3
"""CI 守卫：build-logic 的版本目录与根目录保持同步。

为什么需要单独一条：
`src/android/build-logic` 是 `includeBuild` 的 composite build，它有自己独立的
`gradle/libs.versions.toml` —— 根目录那份对它不可见。于是同一个 Kotlin 版本号在
两个文件里各写一遍，改一处漏一处是最常见的漂移。

漂移的后果不是编译错（两边都能编），而是 build-logic 里的 convention plugin
用 Kotlin A 版本生成 class，app 用 Kotlin B 版本编译 —— 表现为莫名其妙的
metadata 版本不匹配或 KotlinPluginVersion 冲突，报错信息完全指不到这里。

用法：
    python3 scripts/ci/check_version_catalog_sync.py
    python3 scripts/ci/check_version_catalog_sync.py --selftest
"""

from __future__ import annotations

import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "src" / "android"
MAIN = ANDROID / "gradle" / "libs.versions.toml"
BUILD_LOGIC = ANDROID / "build-logic" / "gradle" / "libs.versions.toml"

# build-logic 必须与根目录同值的版本键。
# 只列真正会被两边同时解析进同一次编译的键 —— build-logic 的 convention plugin
# 与 app 模块共享 Kotlin 编译器版本，别的（Compose、Room）不共享。
SHARED_KEYS = ("kotlin",)


def load(path: Path) -> dict:
    with path.open("rb") as fh:
        return tomllib.load(fh)


def compare(main: dict, sub: dict, shared: tuple[str, ...] = SHARED_KEYS) -> list[str]:
    """返回不一致项。抽成纯函数是为了让 --selftest 不依赖真实文件。"""
    mv = main.get("versions", {})
    sv = sub.get("versions", {})
    problems: list[str] = []
    for key in shared:
        if key not in sv:
            problems.append(f"build-logic 的版本目录缺少 `{key}`（根目录为 {mv.get(key)!r}）")
            continue
        if key not in mv:
            problems.append(f"根目录的版本目录缺少 `{key}`（build-logic 为 {sv[key]!r}）")
            continue
        if mv[key] != sv[key]:
            problems.append(
                f"`{key}` 漂移：根目录 {mv[key]!r} ≠ build-logic {sv[key]!r}"
            )
    return problems


def selftest() -> int:
    cases = [
        ("一致应通过", {"versions": {"kotlin": "2.1.0"}}, {"versions": {"kotlin": "2.1.0"}}, 0),
        ("漂移应抓出", {"versions": {"kotlin": "2.1.0"}}, {"versions": {"kotlin": "2.0.0"}}, 1),
        ("子缺失应抓出", {"versions": {"kotlin": "2.1.0"}}, {"versions": {}}, 1),
        ("根缺失应抓出", {"versions": {}}, {"versions": {"kotlin": "2.1.0"}}, 1),
    ]
    bad = 0
    for label, a, b, want in cases:
        got = len(compare(a, b))
        ok = got == want
        bad += 0 if ok else 1
        print(f"{'ok  ' if ok else 'FAIL'} [{label}] 期望 {want} 实际 {got}")
    print(f"\n自测 {len(cases) - bad}/{len(cases)} 通过")
    return 0 if bad == 0 else 1


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    if not MAIN.exists():
        print(f"version catalog check: 找不到 {MAIN.relative_to(ROOT)}")
        return 1
    if not BUILD_LOGIC.exists():
        print(f"version catalog check: 找不到 {BUILD_LOGIC.relative_to(ROOT)}")
        return 1

    problems = compare(load(MAIN), load(BUILD_LOGIC))
    if problems:
        print(f"version catalog check: FAILED ({len(problems)} 处)")
        for p in problems:
            print(f"  - {p}")
        print("\n修法：把两个 libs.versions.toml 的对应版本改成同一个值。")
        return 1
    print(f"version catalog check: OK（{'/'.join(SHARED_KEYS)} 与根目录一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
