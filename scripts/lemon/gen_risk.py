#!/usr/bin/env python3
"""从插件的 cmd 字段推导初始风险表 —— 输出 risk.json，随面板一起携带。

为什么要推导而不是手工标 358 条：
  · 面板原来的风险来自那台机器的 /data/lemonbrain/work/v160_stage/risk_registry.json，
    换设备就丢，全部回落 medium —— ASK 模式下每个工具都弹确认（确认疲劳），
    AUTONOMOUS 模式下高危工具也只按 medium 处理（该拦的没拦）。
  · 风险等级的真实依据写在 cmd 里：读文件是 low，rm -rf 是 high。
    把它算出来、落成可审的 JSON，比人工标 358 条更不容易漏。

判定顺序：destructive(high) > write(medium) > read(low) > 默认 medium。
只做一次初始标注，之后可以在 risk.json 里手工 override。
"""
from __future__ import annotations

import glob
import json
import os
import re
import shlex
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

DESTRUCTIVE = (
    "rm ", "rm -", "rmdir", "unlink", "mv ", "dd ", "mkfs", "wipe", "format",
    "force-stop", "force_stop", "pm uninstall", "pm disable", "pm clear",
    "reboot", "shutdown", "svc power", "settings put", "setprop", "chmod", "chown",
    "> /", ">>", "truncate", "flash", "fastboot", "insmod", "rmmod", "mount -o",
    "drop_caches", "echo 1 >", "echo 0 >", "echo 3 >",
)
WRITE = (
    "mkdir", "touch", "cp ", "ln ", "tee", "sed -i", "tee ", "am start",
    "am broadcast", "input ", "input tap", "input swipe", "input text",
    "screencap", "screenrecord", "pm install", "cmd appops set", "cmd wm",
    "appops set", "svc ",
)
READ = (
    "cat ", "ls ", "ll ", "getprop", "dumpsys", "ps ", "ps -", "grep", "head",
    "tail", "wc ", "df ", "free", "uname", "id", "whoami", "logcat -d", "find ",
    "stat ", "du ", "top -n", "uptime", "date", "echo ", "printf", "which ",
    "readlink", "realpath", "hostname", "ip addr", "ip route", "ss ", "netstat",
    "curl ", "wget ", "ping ", "getenforce", "cmd package list", "pm list",
    "cmd appops get", "settings get", "cmd wm size",
)



# ── 人工覆写（推导看 cmd，这里看"用户实际会用它在干什么"）────────────────────
# 三类必须人工判，因为 cmd 文本看不出意图：
#   1) 名字含破坏性动词、实际只读 —— 推导会误升 high
#   2) 有写入但可逆、爆炸半径小 —— 一直 high 会让用户对弹窗脱敏
#   3) 保持 high 的不写在这里（默认就是 high）
OVERRIDES = {
    # 只读：cat /proc、dumpsys、readlink 遍历、resetprop 读取
    "dbg_shutdown_detect":   ("low", True),
    "dbg_shutdown_recorder": ("low", True),
    "debug_dropbox_list":    ("low", True),
    "fs_lsof":               ("low", True),
    "magisk_prop_get":       ("low", True),
    "rfkill_list":           ("low", True),
    "selinux_avc_summary":   ("low", True),
    # 可逆的小半径写入：权限授予/收回、射频开关、只读重挂、唤醒开关、日志级别
    "pkg_grant":             ("medium", False),
    "pkg_revoke":            ("medium", False),
    "rfkill_toggle":         ("medium", False),
    "mount_readonly":        ("medium", False),
    "power_wakeup_toggle":   ("medium", False),
    "voocphy_loglevel":      ("medium", False),
}

def grade(cmd: str) -> tuple[str, bool]:
    low = (cmd or "").lower()
    # 只读判定优先看重定向：出现 > 就不是只读
    has_redirect = bool(re.search(r"(?<![0-9])>{1,2}\s*\S", low)) and "2>&1" not in low.replace(" ", "")
    if any(k in low for k in DESTRUCTIVE) or has_redirect or re.search(r"\bkill(all)?\b", low):
        return "high", False
    if any(k in low for k in WRITE):
        return "medium", False
    if any(k in low for k in READ):
        return "low", True
    return "medium", False


def main() -> int:
    plugin_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "06-plugins")
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "risk.json")

    tools = {}
    for f in sorted(glob.glob(os.path.join(plugin_dir, "*.json"))):
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        name = d.get("name")
        if not name:
            continue
        risk, ro = grade(d.get("cmd") or "")
        if name in OVERRIDES:
            risk, ro = OVERRIDES[name]
        tools[name] = {"risk": risk, "readOnlyHint": ro}

    # 名字兜底：即使 cmd 看着只读，名字含破坏性动词也升 high
    for n, v in tools.items():
        if n in OVERRIDES:
            continue          # 人工判过的，名字兜底不再覆盖
        low = n.lower()
        if any(k in low for k in ("rm_", "delete", "kill", "force_stop", "wipe", "format",
                                  "uninstall", "disable_", "flash", "chmod", "reboot",
                                  "shutdown", "drop", "clear_")):
            v["risk"] = "high"
            v["readOnlyHint"] = False

    import collections
    cnt = collections.Counter(v["risk"] for v in tools.values())
    doc = {
        "_comment": [
            "工具风险表 —— 由 tools/gen_risk.py 从各插件 cmd 推导的初始值。",
            "risk: low(只读) / medium(有写入) / high(破坏性)；readOnlyHint 影响确认逻辑。",
            "要改某个工具的等级，直接在这里 override；面板读这份文件，不读 /data 下的旧注册表。",
        ],
        "generatedFrom": os.path.basename(os.path.abspath(plugin_dir)),
        "counts": dict(cnt),
        "tools": dict(sorted(tools.items())),
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
    print(f"{out_path}: {len(tools)} 个工具  {dict(cnt)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
