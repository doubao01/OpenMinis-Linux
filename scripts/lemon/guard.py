#!/usr/bin/env python3
"""执行门 —— 与 App 的会话权限三档共用一套语义，并把每次判定写进审计。

三个判定结果：
  delegate  这个操作 App 有自己的权限入口（改设置→设置页，删会话→会话菜单），
            面板不执行、不分类、不弹窗，只把用户指过去
  confirm   需要二次确认（附原因）
  allow     直接执行

关键约定：FULL_ACCESS（yolo）永不二次确认。用户已经选了全授权，再问一遍
既不安全也不诚实 —— 它只会训练用户闭眼点「确定」。审计里照样记 mode，
事后可查「这次是谁用 yolo 放行的」。
"""
from __future__ import annotations

import json
import os
import time

HERE = os.path.dirname(os.path.abspath(__file__))
POLICY_FILE = os.path.join(HERE, "policy.json")

ACTION_DELEGATE = "delegate"
ACTION_CONFIRM = "confirm"
ACTION_ALLOW = "allow"

_RULES: dict | None = None


def load_policy(path: str | None = None) -> dict:
    global _RULES
    if _RULES is None or path:
        with open(path or POLICY_FILE, encoding="utf-8") as fh:
            p = json.load(fh)
        for d in p.get("delegated", []):
            d["prefixes"] = sorted(d.get("prefixes", []), key=len, reverse=True)
        _RULES = p
    return _RULES


def effective_risk(name: str, risk: str, read_only: bool) -> str:
    """采用 risk.json 的结论，只做一致性兜底。

    名字关键词升级**已经落在 risk.json 里**（tools/gen_risk.py 推导时做，并可人工
    override）。这里如果再按名字升一次，人工 override 就永远失效 —— 实测踩过：
    `rfkill_list`/`debug_dropbox_list` 明明已判 low，却被这里按 "kill"/"drop"
    又抬回 high，面板显示的分布与 risk.json 对不上（49 vs 43）。
    """
    if read_only and risk not in ("low", "high"):
        return "low"
    return risk or "medium"


def delegated_for(name: str) -> dict | None:
    p = load_policy()
    low = (name or "").lower()
    for d in p.get("delegated", []):
        if name in d.get("exact", []):
            return d
        for pre in d.get("prefixes", []):
            if low.startswith(pre):
                return d
    return None


def decide(name: str, mode: str, risk: str = "medium", read_only: bool = False) -> dict:
    """返回 {action, mode, risk, route?, why?}。"""
    p = load_policy()
    mode = mode if mode in p["modes"] else p.get("defaultMode", "ASK_BEFORE_CHANGES")
    spec = p["modes"][mode]
    eff = effective_risk(name, risk, read_only)

    d = delegated_for(name)
    if d:
        return {"action": ACTION_DELEGATE, "mode": mode, "risk": eff,
                "route": d["route"], "why": d["why"]}

    if mode == "FULL_ACCESS":
        # yolo：直接放行，不弹窗。审计仍记 mode，事后可追。
        return {"action": ACTION_ALLOW, "mode": mode, "risk": eff,
                "why": "FULL_ACCESS 已授权全部工具"}

    need = eff in spec.get("confirmRisk", [])
    if not need and spec.get("confirmWrites") and not read_only:
        need = True
    if need:
        return {"action": ACTION_CONFIRM, "mode": mode, "risk": eff,
                "requireReason": bool(spec.get("requireReason", True)),
                "why": f"{spec['label']}：{eff} 风险且{"只读" if read_only else "有副作用"}"}
    return {"action": ACTION_ALLOW, "mode": mode, "risk": eff, "why": "低风险只读"}


def audit_line(audit_file: str, *, name: str, args: dict, mode: str, risk: str,
               decision: str, reason: str = "", ok: bool | None = None,
               elapsed_ms: int | None = None, err: str = "") -> dict:
    rec = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "target": name,
        "mode": mode,
        "risk": risk,
        "decision": decision,
        "reason": reason,
        "args": _trim(args),
    }
    if ok is not None:
        rec["ok"] = bool(ok)
    if elapsed_ms is not None:
        rec["ms"] = int(elapsed_ms)
    if err:
        rec["err"] = err[:300]
    try:
        os.makedirs(os.path.dirname(audit_file), exist_ok=True)
        with open(audit_file, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
        if os.path.getsize(audit_file) > 1024 * 1024:
            with open(audit_file, encoding="utf-8") as fh:
                tail = fh.readlines()[-2000:]
            with open(audit_file, "w", encoding="utf-8") as fh:
                fh.writelines(tail)
    except OSError:
        pass
    return rec


def _trim(args: dict, limit: int = 8) -> dict:
    out = {}
    for i, (k, v) in enumerate((args or {}).items()):
        if i >= limit:
            out["…"] = f"{len(args) - limit} more"
            break
        s = v if isinstance(v, (int, float, bool)) or v is None else str(v)
        if isinstance(s, str) and len(s) > 120:
            s = s[:120] + "…"
        out[k] = s
    return out


if __name__ == "__main__":
    import sys
    pol = load_policy()
    print("模式:", ", ".join(f"{k}({v['label']}/{v['appMode']})" for k, v in pol["modes"].items()))
    for n, m, r, ro in [
        ("kernel_version", "ASK_BEFORE_CHANGES", "low", True),
        ("fs_write", "ASK_BEFORE_CHANGES", "medium", False),
        ("fs_write", "AUTONOMOUS", "medium", False),
        ("am_force_stop", "AUTONOMOUS", "medium", False),
        ("am_force_stop", "FULL_ACCESS", "medium", False),
        ("cmd_settings_put", "FULL_ACCESS", "low", False),
        ("session_delete", "FULL_ACCESS", "high", False),
    ]:
        print(f"  {n:20} {m:18} → {decide(n, m, r, ro)}")
    sys.exit(0)
