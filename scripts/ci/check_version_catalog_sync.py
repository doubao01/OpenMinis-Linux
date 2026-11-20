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

import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "src" / "android"
MAIN = ANDROID / "gradle" / "libs.versions.toml"
BUILD_LOGIC = ANDROID / "build-logic" / "gradle" / "libs.versions.toml"


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)

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


def check_catalog(catalog_path: Path) -> list[str]:
    """校验版本目录里没有 Gradle 保留词别名。

    为什么单列一条：`class` / `extensions` / `convention` 等词是 Gradle
    版本目录的保留名，一旦出现在别名里，**整个构建在配置阶段就失败**，
    报错是 "Alias '...' is a reserved name in Gradle which prevents
    generation of accessors"。它和依赖是否正确无关 —— 即使该别名从未被
    引用（死条目），照样炸。本轮实测踩过：`androidx-material3-window-size-class`
    让 CI 编译步骤在 5 分 47 秒时失败，而本地静态守卫因为不解析 toml
    语义完全看不见。
    """
    import tomllib

    problems: list[str] = []
    try:
        data = tomllib.loads(catalog_path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"{rel(catalog_path)}: 无法解析 TOML — {exc}"]

    # Gradle 文档列出的保留名（小写比较，'-'/'_'/'.' 都视作分隔符）
    reserved = {"class", "extensions", "convention", "bundles", "versions", "plugins"}

    for section in ("libraries", "plugins", "bundles"):
        for alias in data.get(section, {}):
            for token in re.split(r"[-_.]", alias.lower()):
                if token in reserved:
                    problems.append(
                        f"{rel(catalog_path)}: [{section}] 别名 '{alias}' 含保留词 "
                        f"'{token}' → 改名（如 windowsize），否则整个构建在配置阶段失败"
                    )
                    break
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
    # 保留词别名：这条是 CI 实测踩出来的（androidx-material3-window-size-class
    # 让整个构建在配置阶段失败，而当时所有本地守卫全绿）。
    catalog_cases = [
        ("保留词 class 应抓出", '[libraries]\nx-window-size-class = { module = "a:b" }\n', 1),
        ("保留词 extensions 应抓出", '[libraries]\nx-extensions = { module = "a:b" }\n', 1),
        ("改名后应通过", '[libraries]\nx-windowsize = { module = "a:b" }\n', 0),
        ("plugins 段也要查", '[plugins]\nx-class = { id = "a.b" }\n', 1),
        ("普通别名应通过", '[libraries]\nandroidx-core-ktx = { module = "a:b" }\n', 0),
    ]
    import tempfile

    with tempfile.TemporaryDirectory() as td:
        for label, body, want in catalog_cases:
            p = Path(td) / "libs.versions.toml"
            p.write_text(body, encoding="utf-8")
            got = len(check_catalog(p))
            ok = got == want
            bad += 0 if ok else 1
            print(f"{'ok  ' if ok else 'FAIL'} [{label}] 期望 {want} 实际 {got}")

    total = len(cases) + len(catalog_cases)
    print(f"\n自测 {total - bad}/{total} 通过")
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
    problems += check_catalog(MAIN)
    problems += check_catalog(BUILD_LOGIC)
    if problems:
        print(f"version catalog check: FAILED ({len(problems)} 处)")
        for p in problems:
            print(f"  - {p}")
        print("\n修法：版本漂移就改成同一个值；保留词别名就改名。")
        return 1
    print(f"version catalog check: OK（{'/'.join(SHARED_KEYS)} 一致，无保留词别名）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
