#!/usr/bin/env python3
"""生成 PrefsRegistry.kt + prefs-inventory.json（v2）。

v2 相对 v1 的修正：
  * 加密 prefs 直接取 `EncryptedPrefsFactory.safeCreate(ctx, "name")` 的字面量实参，
    不再用"同文件唯一 prefs 就合并"的猜测（v1 会把 mirror_settings 误标成加密）。
  * 去掉"多行跨文件拼 object body"的正则脆点，改用 getSharedPreferences 的实参 + 常量解析。
  * 增加 `--check` 模式：只比对磁盘上的 PrefsRegistry.kt 与重新生成的结果，不一致返回 2。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict

RE_GET_LIT = re.compile(r'getSharedPreferences\(\s*"([^"]+)"')
RE_GET_IDENT = re.compile(r'getSharedPreferences\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*[,)]')
# 已迁移到类型化句柄的调用：Prefs.file("name") / Prefs.file(CONST)
# 必须一起采集，否则迁移一个文件就等于把它从注册表里删掉 ——
# 然后守卫会拒绝它自己的全部键（实测踩过：UnreadRegistry）。
RE_TYPED_LIT = re.compile(r'Prefs\s*\.\s*file\(\s*"([^"]+)"')
RE_TYPED_IDENT = re.compile(r'Prefs\s*\.\s*file\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)')
RE_SAFE_CREATE = re.compile(
    r'EncryptedPrefsFactory\s*\.\s*(?:safeCreate|open)\(\s*[^,]+,\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))')
RE_CONST_ANY = re.compile(r'const\s+val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"')
# 动态拼接键：`getX("prefix" + ident)` 或 `getX("prefix.${x}")`。
# 这些键无法静态归集，但对应的是合法写入，故记成前缀交给 PrefsFileSpec.dynamicPrefixes。
# 字面量在前、拼接在后：getLong("lastReadAt:" + sessionId)
RE_DYN_PLUS = re.compile(r'\.(?:getString|putString|getBoolean|putBoolean|getInt|putInt|getLong|putLong|getFloat|putFloat|getStringSet|putStringSet|contains|remove|string|stringOrNull|bool|int|long|float|stringSet)\(\s*"([^"$]*)"\s*\+')
# 模板字符串：getString("mirror.selected.${cat.key}") → 前缀 "mirror.selected."
RE_DYN_TMPL = re.compile(r'\.(?:getString|putString|getBoolean|putBoolean|getInt|putInt|getLong|putLong|getFloat|putFloat|getStringSet|putStringSet|contains|remove|string|stringOrNull|bool|int|long|float|stringSet)\(\s*"([^"$]*)\$\{')
# 字面量作为前缀常量再拼接：PRIVATE_PREFIX + ident（常量名不限 KEY_ 前缀）
RE_DYN_IDENT_PLUS = re.compile(r'\.(?:getString|putString|getBoolean|putBoolean|getInt|putInt|getLong|putLong|getFloat|putFloat|getStringSet|putStringSet|contains|remove|string|stringOrNull|bool|int|long|float|stringSet)\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\+')
# 任意 const val NAME = "literal"，用于解析上面那种前缀常量
RE_ANY_CONST = re.compile(r'const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"')

# 键常量名：KEY_* 是主流写法，但 HostEventHooks 用的是裸 `KEY`，
# 还有 *_KEY 后缀的形态。只认 KEY_* 会让这些文件的 keys 为空 ——
# 而 keys 为空的文件会退化成「前缀约定」校验，等于没有校验。
RE_KEY_CONST = re.compile(r'const\s+val\s+((?:KEY|key)[A-Za-z0-9_]*|[A-Za-z0-9_]+_KEY)\s*=\s*"([^"]*)"')


# 生成物自己不能被扫描 —— 否则 `PrefsRegistry.kt` 里那些
# `PrefsFileSpec(name=..., dynamicPrefixes=listOf(...))` 字面量会在下一轮被当成
# 「源码里的 prefs 使用点」，把全部前缀喂给第一个被扫到的文件
# （实测：mirror_settings 因此吃到了 allow_private_ / lastReadAt_）。
GENERATED = {'PrefsRegistry.kt', 'PrefsAccess.kt', 'MinisTokens.kt', 'ComposeAdapters.kt', 'PrefsBackend.kt', 'PrefsSource.kt', 'TokenBridge.kt'}

def iter_kt(root):
    for dp, _dn, fn in os.walk(root):
        for f in sorted(fn):
            if f.endswith('.kt') and f not in GENERATED:
                p = os.path.join(dp, f)
                try:
                    yield p, open(p, encoding='utf-8', errors='replace').read()
                except OSError:
                    continue


def module_of(rel):
    parts = rel.replace('\\', '/').split('/')
    return parts[0] if len(parts) > 1 else '.'


def scan(src_root):
    files = list(iter_kt(src_root))

    consts, const_owner, conflicts = {}, {}, []
    for p, s in files:
        rel = os.path.relpath(p, src_root)
        for m in RE_CONST_ANY.finditer(s):
            n, lit = m.group(1), m.group(2)
            if n in consts and consts[n] != lit:
                conflicts.append({'name': n, 'a': consts[n], 'b': lit,
                                  'files': [const_owner[n], rel]})
            consts[n] = lit
            const_owner[n] = rel

    prefs = {}

    def touch(name, rel, how, enc):
        e = prefs.setdefault(name, {'name': name, 'usages': [], 'encrypted': enc})
        e['usages'].append({'file': rel, 'how': how})
        if enc:
            e['encrypted'] = True
        return e

    for p, s in files:
        rel = os.path.relpath(p, src_root)
        for m in RE_GET_LIT.finditer(s):
            touch(m.group(1), rel, 'literal', False)
        local_consts = dict(RE_ANY_CONST.findall(s))
        for m in RE_GET_IDENT.finditer(s):
            ident = m.group(1)
            if ident in ('context', 'this'):
                continue
            nm = local_consts.get(ident) or consts.get(ident)
            if nm is None:
                continue
            touch(nm, rel, 'const:' + ident, False)
        for m in RE_SAFE_CREATE.finditer(s):
            nm = m.group(1) or consts.get(m.group(2), '')
            if nm:
                touch(nm, rel, 'encrypted', True)
        for m in RE_TYPED_LIT.finditer(s):
            touch(m.group(1), rel, 'typed', False)
        for m in RE_TYPED_IDENT.finditer(s):
            ident = m.group(1)
            # 优先用**本文件**的常量定义。全局 consts 按名字去重，
            # 而 `PREFS` 这种名字被 13 个文件各定义一次（mirror_settings /
            # minis_unread_registry / ...），全局表只会留下最后一个 ——
            # 实测 UnreadRegistry 的 `Prefs.file(PREFS)` 因此被解析成
            # mirror_settings，然后整个文件从注册表里消失。
            nm = dict(RE_ANY_CONST.findall(s)).get(ident) or consts.get(ident)
            if nm:
                touch(nm, rel, 'typed:' + ident, False)

    # 键常量归属：某文件只用到一个 prefs 文件 → 该文件里的 KEY_* 归它
    for p, s in files:
        rel = os.path.relpath(p, src_root)
        local = {n for n, e in prefs.items() if any(u['file'] == rel for u in e.get('usages', []))}
        keys = RE_KEY_CONST.findall(s)
        # 注意：这里不能因为「没有 KEY_* 常量」就跳过 —— MirrorSettingsScreen 这类
        # 文件是纯动态键（"mirror.selected.${x}"），一个常量都没有，但它的前缀
        # 必须被登记，否则严格校验会锁死这个文件。
        # 动态键前缀只归给「本文件唯一使用的 prefs」；多目标时无处安放，跳过
        dyn_prefixes = [m.group(1) for m in RE_DYN_PLUS.finditer(s) if m.group(1)]
        dyn_prefixes += [m.group(1) for m in RE_DYN_TMPL.finditer(s) if m.group(1)]
        # `PREFIX + x` 形态：把常量名解析成它的字面量
        local_consts = dict(RE_ANY_CONST.findall(s))
        for m in RE_DYN_IDENT_PLUS.finditer(s):
            v = local_consts.get(m.group(1))
            if v:
                dyn_prefixes.append(v)
        if len(local) == 1:
            target = next(iter(local))
            prefs[target].setdefault('keys', {})
            for kn, kl in keys:
                prefs[target]['keys'][kn] = kl
            if dyn_prefixes:
                bucket = prefs[target].setdefault('dyn_keys', set())
                bucket.update(dyn_prefixes)
        else:
            for kn, kl in keys:
                prefs.setdefault('__orphan_keys__', {'keys': {}})['keys'][kn] = kl

    # 合并手工补充清单（tools/prefs_extra_keys.json）。
    # 扫描器有三个固定的盲区：字面量键、多 prefs 文件的归属、迁移后由调用点拼接的键。
    # 这三类必须靠显式声明，否则守卫会开始拒绝活代码 —— 那是比漏登记更糟的失败模式。
    extra_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'prefs_extra_keys.json')
    if os.path.exists(extra_path):
        with open(extra_path, encoding='utf-8') as fh:
            extra = json.load(fh).get('files', {})
        for name, decl in extra.items():
            e = prefs.get(name)
            if e is None:
                continue
            e.setdefault('keys', {})
            for kn, kl in (decl.get('keys') or {}).items():
                e['keys'][kn] = kl

    out = []
    for name, e in sorted(prefs.items()):
        if name == '__orphan_keys__':
            continue
        mods = sorted({module_of(u['file']) for u in e['usages']})
        out.append({
            'name': name,
            'module': mods[0],
            'modules': mods,
            'encrypted': e['encrypted'],
            'usage_count': len(e['usages']),
            'usage_files': sorted({u['file'] for u in e['usages']}),
            'keys': dict(sorted(e.get('keys', {}).items())),
            'dyn_keys': sorted(e.get('dyn_keys', set())),
        })

    orphan = sorted(prefs.get('__orphan_keys__', {}).get('keys', {}).items())
    return {
        'src_root': os.path.abspath(src_root),
        'prefs_files': out,
        'constant_conflicts': conflicts,
        'orphan_keys': [k for k, _ in orphan],
        'totals': {
            'prefs_files': len(out),
            'prefs_encrypted': sum(1 for f in out if f['encrypted']),
            'key_constants_attributed': sum(len(f['keys']) for f in out),
            'orphan_keys': len(orphan),
            'usage_sites': sum(f['usage_count'] for f in out),
            'usage_files': len({u for f in out for u in f['usage_files']}),
            'constant_conflicts': len(conflicts),
        },
    }


HEADER = '''package com.openminis.app.config

/*
 * 自动生成 —— 不要手改。
 * 生成器: refactor/tools/gen_prefs_registry.py
 * 源目录: {src}
 * 规模:   {files} 个 prefs 文件（加密 {enc}）/ 已归属键常量 {keys} / {sites} 处使用
 *
 * 目的: 把「SharedPreferences 文件名 + 键名」从 93 个文件里的裸字符串收敛为一处
 * 可枚举、可校验、可审计的数据表。设计见 refactor/docs/01-config-unification.md。
 */

/** 存储性质，决定迁移策略（不决定读写 API）。 */
enum class PrefsScope {{
    /** 明文 SharedPreferences，可迁移到 DataStore。 */
    PLAIN,

    /** AES 加密（EncryptedPrefsFactory），只登记不迁移。 */
    ENCRYPTED,

    /** 一次性标记位（安装提示 / 重试计数 / 健康缓存），迁移无收益。 */
    EPHEMERAL,
}}

/**
 * @param name   SharedPreferences 文件名字面量
 * @param module 主要使用方（app 内顶层包名）
 * @param keys   已归集的键常量（常量名 -> 键名字面量）
 * @param dynamicPrefixes 允许动态拼接的键前缀。全仓实测有 8 处这种写法，例如
 *        UnreadRegistry 的 `"lastReadAt:" + sessionId`、BackgroundSettingsRepository
 *        的 `KEY_BG_OVERLAY_X + suffix`、ConnectorNetworkPolicy 的
 *        `"allow_private_" + configurationKey()`。这些键无法被静态扫描归集，
 *        但它们是合法写入 —— 所以要在 spec 里显式声明前缀，否则严格校验会锁死
 *        一个仍在正常工作的文件。
 */
data class PrefsFileSpec(
    val name: String,
    val module: String,
    val scope: PrefsScope,
    val keys: Map<String, String> = emptyMap(),
    val dynamicPrefixes: List<String> = emptyList(),
    val note: String = "",
) {{
    fun isEncrypted(): Boolean = scope == PrefsScope.ENCRYPTED

    /** 该键是否可以出现在本文件里（静态登记 or 命中动态前缀）。 */
    fun accepts(key: String): Boolean =
        keys.containsValue(key) || dynamicPrefixes.any {{ key.startsWith(it) }}
}}

object PrefsRegistry {{

    val files: List<PrefsFileSpec> = listOf(
{entries}
    )

    private val byName: Map<String, PrefsFileSpec> = files.associateBy {{ it.name }}

    fun spec(name: String): PrefsFileSpec? = byName[name]

    fun require(name: String): PrefsFileSpec = byName[name]
        ?: error("unregistered SharedPreferences file: $name - run gen_prefs_registry.py")

    fun ofModule(module: String): List<PrefsFileSpec> = files.filter {{ it.module == module }}

    /** 键名 -> 声明它的 prefs 文件。同名键在不同文件里是两回事，故返回列表。 */
    val keyIndex: Map<String, List<PrefsFileSpec>> by lazy {{
        val m = LinkedHashMap<String, MutableList<PrefsFileSpec>>()
        for (f in files) for (k in f.keys.values) m.getOrPut(k) {{ mutableListOf() }}.add(f)
        m
    }}

    fun filesDeclaring(key: String): List<PrefsFileSpec> = keyIndex[key].orEmpty()

    data class Coverage(
        val totalFiles: Int,
        val encryptedFiles: Int,
        val ephemeralFiles: Int,
        val plainFiles: Int,
        val attributedKeys: Int,
    )

    fun coverage(): Coverage = Coverage(
        totalFiles = files.size,
        encryptedFiles = files.count {{ it.isEncrypted() }},
        ephemeralFiles = files.count {{ it.scope == PrefsScope.EPHEMERAL }},
        plainFiles = files.count {{ it.scope == PrefsScope.PLAIN }},
        attributedKeys = files.sumOf {{ it.keys.size }},
    )
}}
'''

EPHEMERAL_HINTS = ('retry', 'asked', 'health', 'flag', 'dummy', 'cache', 'mark', 'state', 'download')


def scope_of(f):
    if f['encrypted']:
        return 'PrefsScope.ENCRYPTED'
    low = f['name'].lower()
    if any(h in low for h in EPHEMERAL_HINTS):
        return 'PrefsScope.EPHEMERAL'
    return 'PrefsScope.PLAIN'


def render(inv):
    entries = []
    for f in inv['prefs_files']:
        parts = [f'name = "{f["name"]}"', f'module = "{f["module"]}"', f'scope = {scope_of(f)}']
        ks = f['keys']
        if ks:
            body = ', '.join(f'"{k}" to "{v}"' for k, v in ks.items())
            parts.append(f'keys = mapOf({body})')
        # 动态拼接键：扫描器看到的 `"prefix" + x` / `"prefix.${x}"` 记在 dyn_keys
        dyn = f.get('dyn_keys') or []
        if dyn:
            parts.append('dynamicPrefixes = listOf(' + ', '.join(f'"{d}"' for d in dyn) + ')')
        parts.append(f'note = "{f["usage_count"]} usages across {len(f["usage_files"])} file(s)"')
        entries.append('        PrefsFileSpec(' + ', '.join(parts) + '),')
    t = inv['totals']
    head = HEADER.format(src=inv['src_root'], files=t['prefs_files'], enc=t['prefs_encrypted'],
                         keys=t['key_constants_attributed'], sites=t['usage_sites'],
                         entries='\n'.join(entries))
    return head.replace('{{', '{').replace('}}', '}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--check', action='store_true', help='只比对，不写盘；不一致 exit 2')
    a = ap.parse_args()

    inv = scan(a.src)
    kt = render(inv)
    kt_path = os.path.join(a.out, 'PrefsRegistry.kt')

    if a.check:
        old = open(kt_path, encoding='utf-8').read() if os.path.exists(kt_path) else ''
        if old != kt:
            print('STALE: PrefsRegistry.kt 与源码不一致，请重跑生成器', file=sys.stderr)
            return 2
        print('OK: PrefsRegistry.kt 与源码一致')
        return 0

    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, 'prefs-inventory.json'), 'w', encoding='utf-8') as f:
        json.dump(inv, f, ensure_ascii=False, indent=2)
    with open(kt_path, 'w', encoding='utf-8') as f:
        f.write(kt)

    t = inv['totals']
    print(f"prefs 文件   : {t['prefs_files']}（加密 {t['prefs_encrypted']}）")
    print(f"已归属键常量 : {t['key_constants_attributed']}（无归属 {t['orphan_keys']}）")
    print(f"使用点       : {t['usage_sites']} 处 / {t['usage_files']} 个文件")
    print(f"常量同名冲突 : {t['constant_conflicts']}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
