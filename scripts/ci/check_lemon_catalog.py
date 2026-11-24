#!/usr/bin/env python3
"""守卫：`assets/lemon/catalog.json` 必须能由「源」重新推算出来。

## 为什么需要

`catalog.json` 是生成物（`gen_catalog.py` 从插件壳 + `groups.json` + `risk.json`
产出），但它直接决定 App 运行时能做什么：
  · `domain` 决定面板分组与 `lemon_list` 过滤
  · `risk` 决定**三档权限里哪些工具会被拦**

也就是说，手改一个字符就能把 `fs_rm` 从 high 改成 low —— 编译通过、门禁全绿
（`catalog.json` 是 JSON 资产，没有任何类型约束）、`check_feature_anchors` 也
不查内容。这是当前唯一一条「改数据就能绕过安全判断」的路径。

## 判据（全部可重算，零误报）

| # | 断言 | 抓什么 |
|---|---|---|
| 1 | JSON 可解析、`version == 2` | 文件被编辑器弄坏 |
| 2 | 每条的九个字段齐全且非空（`required` 可为空串） | 手删字段 |
| 3 | `name` 全仓唯一 | 复制粘贴重复条目 |
| 4 | `risk ∈ {low, medium, high}` | 手写非法等级 |
| 5 | `timeoutMs` 是正整数且 ≥ 1000 | 手写 0 / 负数 / 字符串 |
| 6 | `domain == grouper.domain_field(name)` | **手改分组**（与 groups.json 脱钩） |
| 7 | `risk == risk.json[name].risk` | **手改风险**（最危险的一条） |
| 8 | catalog 的 name 集合 == risk.json 的 name 集合 | 增删工具没同步 |
| 9 | `D9`（未分组）条数为 0 | 分组表覆盖退化 |

全部从仓库内文件重算，不需要上游 `06-plugins` 目录 —— 所以能在任何 clone 上跑。

## 用法

    python3 scripts/ci/check_lemon_catalog.py
    python3 scripts/ci/check_lemon_catalog.py --verbose

退出码：0 = 一致；1 = 有偏差（说明手改了资产或忘了重生成）；2 = 无柠檬资产（跳过）。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEMON = ROOT / "scripts/lemon"
CATALOG = ROOT / "src/android/app/src/main/assets/lemon/catalog.json"
RISK = LEMON / "risk.json"
GROUPS = LEMON / "groups.json"

REQUIRED_FIELDS = ("name", "desc", "cmd", "params", "required",
                   "timeoutMs", "domain", "risk", "ro")
VALID_RISK = {"low", "medium", "high"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    if not CATALOG.is_file():
        print("lemon-catalog: SKIP (no catalog.json)")
        return 2
    if not RISK.is_file() or not GROUPS.is_file():
        print("lemon-catalog: SKIP (catalog present but sources missing)")
        return 2

    sys.path.insert(0, str(LEMON))
    try:
        from grouper import domain_field, load_rules
    except Exception as e:  # pragma: no cover - grouper 是仓库内文件
        print(f"lemon-catalog: SKIP (grouper unimportable: {e})")
        return 2

    problems: list[str] = []

    # ── 1) catalog 可解析
    try:
        doc = json.loads(CATALOG.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"lemon-catalog: catalog.json 不是合法 JSON（{e}）")
        return 1

    if doc.get("version") != 2:
        problems.append(f"version 应为 2，实际 {doc.get('version')!r}")

    tools = doc.get("tools") or []
    if not isinstance(tools, list) or not tools:
        print("lemon-catalog: catalog.json 里没有 tools 数组")
        return 1

    risk_doc = json.loads(RISK.read_text(encoding="utf-8")).get("tools") or {}
    rules = load_rules(str(GROUPS))

    # ── 2-5) 逐条基本约束
    seen: dict[str, int] = {}
    for idx, t in enumerate(tools):
        tag = t.get("name") or f"#{idx}"
        missing = [f for f in REQUIRED_FIELDS if f not in t]
        if missing:
            problems.append(f"{tag}: 缺字段 {missing}")
            continue
        if not t["name"]:
            problems.append(f"#{idx}: name 为空")
            continue
        if t["name"] in seen:
            problems.append(f"{tag}: name 重复（首次出现于 #{seen[t['name']]}）")
        seen[t["name"]] = idx
        if t["risk"] not in VALID_RISK:
            problems.append(f"{tag}: risk={t['risk']!r} 不在 {sorted(VALID_RISK)}")
        # 上界不设：上游插件自己声明过 900000（15 分钟）这类长任务，
        # 运行时由 LemonTools 的 `coerceIn(1000, 120_000)` 收口。这里只挡
        # 真错的值（0 / 负数 / 字符串 / 缺失），不重复实现一遍 clamp。
        ms = t["timeoutMs"]
        if not isinstance(ms, int) or isinstance(ms, bool) or ms < 1000:
            problems.append(f"{tag}: timeoutMs={ms!r} 必须是 ≥ 1000 的整数")

    # ── 6) domain 必须能由 groups.json 重算
    drift_domain = [
        (t["name"], t["domain"], domain_field(t["name"], rules))
        for t in tools
        if "name" in t and "domain" in t and domain_field(t["name"], rules) != t["domain"]
    ]
    for name, actual, expected in drift_domain[:10]:
        problems.append(f"{name}: domain={actual!r} 但 groups.json 推算是 {expected!r}")
    if len(drift_domain) > 10:
        problems.append(f"… 另有 {len(drift_domain) - 10} 条 domain 与 groups.json 不符")

    # ── 7) risk 必须与 risk.json 一致（最危险的一条）
    drift_risk = []
    for t in tools:
        name = t.get("name")
        if not name:
            continue
        expected = (risk_doc.get(name) or {}).get("risk")
        if expected and expected != t.get("risk"):
            drift_risk.append((name, t.get("risk"), expected))
    for name, actual, expected in drift_risk[:10]:
        problems.append(f"{name}: risk={actual!r} 但 risk.json 是 {expected!r}")
    if len(drift_risk) > 10:
        problems.append(f"… 另有 {len(drift_risk) - 10} 条 risk 与 risk.json 不符")

    # ── 8) 两侧 name 集合一致
    cat_names = {t.get("name") for t in tools if t.get("name")}
    risk_names = set(risk_doc)
    only_cat = sorted(cat_names - risk_names)
    only_risk = sorted(risk_names - cat_names)
    if only_cat:
        problems.append(f"只在 catalog 里（risk.json 缺）：{only_cat[:6]}")
    if only_risk:
        problems.append(f"只在 risk.json 里（catalog 缺）：{only_risk[:6]}")

    # ── 9) 未分组必须为 0
    ungrouped = [t["name"] for t in tools if str(t.get("domain", "")).startswith("D9")]
    if ungrouped:
        problems.append(f"未分组（D9）{len(ungrouped)} 条，首几个：{ungrouped[:6]}")

    if problems:
        print(f"lemon-catalog: {len(problems)} 处偏差 —— catalog.json 与源不一致", file=sys.stderr)
        for p in problems[:20]:
            print(f"  - {p}", file=sys.stderr)
        if len(problems) > 20:
            print(f"  … 另有 {len(problems) - 20} 处", file=sys.stderr)
        print(file=sys.stderr)
        print("若确实改了 groups.json / risk.json，重跑生成器：", file=sys.stderr)
        print("  python3 scripts/lemon/gen_catalog.py --plugins <上游 06-plugins>", file=sys.stderr)
        return 1

    print(f"lemon-catalog: OK ({len(tools)} 工具 / 域与 risk 均可由源重算 / 未分组 0)")
    if a.verbose:
        import collections
        dom = collections.Counter(t["domain"] for t in tools)
        rk = collections.Counter(t["risk"] for t in tools)
        print(f"  分组: {dict(dom.most_common())}")
        print(f"  风险: {dict(rk)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
