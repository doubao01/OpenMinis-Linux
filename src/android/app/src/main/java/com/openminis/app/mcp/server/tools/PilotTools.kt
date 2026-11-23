package com.openminis.app.mcp.server.tools

import com.openminis.app.accessibility.A11ySkillStore
import com.openminis.app.config.ConfigBridge
import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-lemon-pilot-native] `pilot_*` —— 面板那 5 个无障碍自动化工具的原生移植。
 *
 * ## 为什么要移植
 *
 * `assets/lemon/catalog.json` 的 358 条是从插件壳（`cmd: "shell 命令"`）生成的，
 * 而 `pilot_*` 是内核 builtin、没有插件壳，所以 in-app 目录里天然缺这 5 条：
 *
 * ```
 * in-app catalog: 358 ｜ 面板活体: 363 ｜ 只在面板: pilot_audit, pilot_perceive,
 *                                                   pilot_run, pilot_skills, pilot_tap
 * ```
 *
 * 它们不是 shell 命令，而是「语义层」：解析控件树、按 text/id 点击、把成功路径
 * 结晶成技能、回放。App 里这三件事其实都有实现，缺的只是旧名字的入口。
 *
 * ## 实现取向：适配，不重写
 *
 * | 工具 | 面板原本 | 这里怎么做 |
 * |---|---|---|
 * | `pilot_perceive` | dump 控件树 → 语义元素表 | `android-a11y-cli ui find`（同一棵树的 JSON） |
 * | `pilot_tap` | 按 text/id 定位并点中心 | `android-a11y-cli tap text|id`（自带中心点回退） |
 * | `pilot_run` | 回放技能 / 执行步骤序列 | 步骤序列逐条翻译成 CLI；技能从 [A11ySkillStore] 取 |
 * | `pilot_skills` | 技能库 list/show/del | [A11ySkillStore]（App 已有，含 LRU 与失败降级） |
 * | `pilot_audit` | 最近动作审计 | App 的 ConfigBridge 审计 + guest 侧面板审计（存在才读） |
 *
 * 为什么不把面板那套 Python 语义层搬进来：它最终也是调 `android-a11y-cli`
 * （`sandbox/offload/AccessibilityOffloadHandler.kt` 就是这套 CLI 的实现），
 * 再包一层进程只会多一次 IPC 和一份权限判断。
 *
 * ## 门
 *
 * 每条都过 [McpToolShared.gateAndRun]，与其它工具同一套 SecurityGate：
 * 会话档 ASK 时 `pilot_tap` 一样要确认，`pilot_run` 里带 `open_app`/`stop_app`
 * 这类跨应用动作时按 high 风险走。
 */
internal fun pilotTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "pilot_perceive",
        description = "[LemonPilot 原生] 语义感知：把当前界面的控件树解析成语义元素表" +
            "（text/id/bounds/clickable），按关键词过滤。等价于面板的 pilot_perceive。",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("keyword", JSONObject().apply {
                    put("type", "string")
                    put("description", "过滤关键词（匹配 text/desc 或 id，空=全部）")
                })
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "最大条数，默认 40，上限 80")
                })
            })
        },
        handler = { pilotPerceive(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_tap",
        description = "[LemonPilot 原生] 语义点击：按 text 或 resource-id 定位控件并点其中心，" +
            "免坐标硬编码。等价于面板的 pilot_tap。",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "控件文本/描述子串")
                })
                put("id", JSONObject().apply {
                    put("type", "string")
                    put("description", "resource-id 子串（text 为空时使用）")
                })
                put("index", JSONObject().apply {
                    put("type", "integer")
                    put("description", "第几个匹配，默认 0")
                })
            })
        },
        handler = { pilotTap(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_run",
        description = "[LemonPilot 原生] 任务执行：回放已结晶技能（skill=名字），或执行 JSON 步骤序列" +
            "（open_app/stop_app/tap_text/tap_id/tap_xy/swipe/key/text/wait/assert_text）。" +
            "成功后可 save_as 结晶为技能名，下次回放零 Token。",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("skill", JSONObject().apply {
                    put("type", "string")
                    put("description", "要回放的技能名（与 steps 二选一）")
                })
                put("steps", JSONObject().apply {
                    put("type", "string")
                    put("description", "JSON 步骤数组字符串")
                })
                put("save_as", JSONObject().apply {
                    put("type", "string")
                    put("description", "成功后把本次步骤结晶为该技能名")
                })
                put("confirm", JSONObject().apply {
                    put("type", "string")
                    put("description", "1=允许 open_app/stop_app 这类跨应用敏感动作")
                })
            })
        },
        handler = { pilotRun(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_skills",
        description = "[LemonPilot 原生] 技能库：list 列出、show 看单条步骤、del 删除。" +
            "与 App 的录制/回放共用同一份 a11y_skills.json。",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("description", "list | show | del")
                })
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "技能名（show/del 必填）")
                })
            })
            put("required", JSONArray().put("action"))
        },
        handler = { pilotSkills(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_audit",
        description = "[LemonPilot 原生] 审计：读 App 的配置/门审计（最近 N 条），" +
            "若本机同时跑着独立面板，再附面板审计尾部，便于对照。",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("lines", JSONObject().apply {
                    put("type", "integer")
                    put("description", "条数，默认 20，上限 100")
                })
            })
        },
        handler = { pilotAudit(deps, it) },
    ),
)

// ── 公共 plumbing ────────────────────────────────────────────────────────────

/** 跑一条 guest 命令并取回 CLI 的 JSON 信封（{ok,data|error}）。 */
private fun pilotCli(cmd: String, timeoutSec: Int = 25): JSONObject? {
    val result = McpToolShared.runInGuest(cmd, timeoutSec)
    val raw = result.output.trim()
    val at = raw.indexOf('{')
    if (at < 0) return null
    return runCatching { JSONObject(raw.substring(at)) }.getOrNull()
}

private fun pilotFail(err: JSONObject?, fallback: String): McpServerCore.CallResult {
    val message = err?.optJSONObject("error")?.optString("message").orEmpty()
    return McpToolShared.error(message.ifBlank { fallback })
}

private fun clamp(value: Int, low: Int, high: Int): Int = when {
    value < low -> low
    value > high -> high
    else -> value
}

/** 把 CLI 的 node JSON 压成一行人类可读的语义元素，字段名按乐观兜底取。 */
private fun describeNode(node: JSONObject): String {
    fun pick(vararg keys: String): String {
        for (key in keys) {
            val value = node.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }
    val text = pick("text", "label")
    val desc = pick("desc", "description", "contentDescription")
    val id = pick("id", "viewId", "resourceId")
    val bounds = pick("bounds", "boundsInScreen")
    val clickable = node.optBoolean("clickable") || node.optBoolean("isClickable")
    val parts = mutableListOf<String>()
    if (text.isNotBlank()) parts.add("text=$text")
    if (desc.isNotBlank()) parts.add("desc=$desc")
    if (id.isNotBlank()) parts.add("id=${id.substringAfterLast('/')}")
    if (bounds.isNotBlank()) parts.add("bounds=$bounds")
    if (clickable) parts.add("clickable")
    return parts.joinToString(" ")
}

// ── 工具实现 ────────────────────────────────────────────────────────────────

private fun pilotPerceive(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val keyword = arguments.optString("keyword").trim()
    val limit = clamp(arguments.optInt("limit", 40).takeIf { it > 0 } ?: 40, 1, 80)
    val escaped = McpToolShared.shellEscape(keyword)
    // 关键词同时匹配 text 与 desc：CLI 的谓词是 `--text-contains` / `--desc-contains`
    // （见 AccessibilityOffloadHandler.matchesPredicate），两次 find 合并去重。
    val cmds = if (keyword.isEmpty()) {
        listOf("android-a11y-cli ui find --limit $limit")
    } else {
        listOf(
            "android-a11y-cli ui find --text-contains $escaped --limit $limit",
            "android-a11y-cli ui find --desc-contains $escaped --limit $limit",
        )
    }
    val seen = LinkedHashMap<String, String>()
    var lastError: JSONObject? = null
    for (cmd in cmds) {
        val envelope = pilotCli(cmd)
        if (envelope == null) continue
        if (!envelope.optBoolean("ok")) {
            lastError = envelope
            continue
        }
        val data = envelope.opt("data")
        val nodes = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val line = describeNode(node)
            if (line.isNotBlank() && !seen.containsKey(line)) seen[line] = line
            if (seen.size >= limit) break
        }
        if (seen.size >= limit) break
    }
    if (seen.isEmpty() && lastError != null) return pilotFail(lastError, "ui find 失败")
    val body = if (seen.isEmpty()) {
        "（没有匹配的语义元素：关键词=${keyword.ifBlank { "<全部>" }}）"
    } else {
        seen.keys.joinToString("\n")
    }
    return McpServerCore.CallResult(
        content = JSONArray().put(McpToolShared.text("语义元素 ${seen.size} 条：\n$body")),
    )
}

private fun pilotTap(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val text = arguments.optString("text").trim()
    val id = arguments.optString("id").trim()
    val index = arguments.optInt("index", 0).coerceAtLeast(0)
    if (text.isEmpty() && id.isEmpty()) return McpToolShared.error("需要 text 或 id")
    val cmd = if (text.isNotEmpty()) {
        "android-a11y-cli tap text ${McpToolShared.shellEscape(text)} --contains --index $index"
    } else {
        "android-a11y-cli tap id ${McpToolShared.shellEscape(id)}"
    }
    val envelope = pilotCli(cmd) ?: return McpToolShared.error("tap 没有返回可解析的结果")
    if (!envelope.optBoolean("ok")) {
        // 面板的行为：按 text 找不到时，把同一个串当 id 再试一次。
        if (text.isNotEmpty()) {
            val retry = pilotCli("android-a11y-cli tap id ${McpToolShared.shellEscape(text)}")
            if (retry != null && retry.optBoolean("ok")) {
                return McpServerCore.CallResult(
                    content = JSONArray().put(McpToolShared.text("按 id 命中并点击：$text")),
                )
            }
        }
        return pilotFail(envelope, "tap 失败")
    }
    val data = envelope.optJSONObject("data")
    val where = listOfNotNull(
        data?.optString("x")?.takeIf { it.isNotBlank() }?.let { "x=$it" },
        data?.optString("y")?.takeIf { it.isNotBlank() }?.let { "y=$it" },
        data?.optString("nodeId")?.takeIf { it.isNotBlank() }?.let { "node=$it" },
    ).joinToString(" ")
    return McpServerCore.CallResult(
        content = JSONArray().put(
            McpToolShared.text("已点击：${text.ifBlank { id }}${if (where.isBlank()) "" else "（$where）"}"),
        ),
    )
}

/** 把一步 JSON 翻译成 CLI 命令；[error] 非空表示这一步被门拦下，整个任务中止。 */
private data class StepPlan(val cmd: String, val label: String, val error: String? = null)

private fun stepCommand(step: JSONObject, allowCrossApp: Boolean): StepPlan? {
    val op = step.optString("op").ifBlank { step.optString("action") }.lowercase()
    fun v(key: String): String = step.optString(key)
    fun esc(key: String): String = McpToolShared.shellEscape(v(key))
    fun int(key: String, def: Int = 0): Int = step.optInt(key, def)
    return when (op) {
        "open_app" -> {
            val pkg = esc("pkg").ifBlank { esc("package") }
            if (!allowCrossApp) return StepPlan("", "open_app $pkg", "open_app 需要 confirm=1")
            StepPlan("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", "打开 $pkg")
        }
        "stop_app" -> {
            val pkg = esc("pkg").ifBlank { esc("package") }
            if (!allowCrossApp) return StepPlan("", "stop_app $pkg", "stop_app 需要 confirm=1")
            StepPlan("am force-stop $pkg", "停止 $pkg")
        }
        "tap_text" -> StepPlan(
            "android-a11y-cli tap text ${esc("text")} --contains --index ${int("index")}",
            "点「${v("text")}」",
        )
        "tap_id" -> StepPlan("android-a11y-cli tap id ${esc("id")}", "点 id=${v("id")}")
        "tap_xy" -> StepPlan("android-a11y-cli tap xy ${int("x")} ${int("y")}", "点 (${int("x")},${int("y")})")
        "swipe" -> StepPlan(
            "android-a11y-cli gesture swipe ${int("x1")} ${int("y1")} ${int("x2")} ${int("y2")} ${int("ms", 300)}",
            "滑动",
        )
        "key" -> StepPlan("android-a11y-cli input key ${esc("key")}", "按键 ${v("key")}")
        "text" -> StepPlan("android-a11y-cli input text ${esc("text")}", "输入")
        "wait" -> {
            val ms = int("ms", 500).coerceAtLeast(50)
            StepPlan("sleep ${ms / 1000.0}", "等待 ${ms}ms")
        }
        "assert_text" -> null   // 断言在调用侧校验，不发命令
        else -> null
    }
}

private fun pilotRun(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val skillName = arguments.optString("skill").trim()
    val saveAs = arguments.optString("save_as").trim()
    val allowCrossApp = arguments.optString("confirm").trim() == "1"
    val context = deps.context ?: return McpToolShared.error("需要 Android context（单元测试环境不可用）")
    val steps: JSONArray = when {
        skillName.isNotEmpty() -> {
            val store = A11ySkillStore.forContext(context)
            val skill = store.byName(skillName) ?: return McpToolShared.error("技能不存在：$skillName")
            val arr = JSONArray()
            for (step in skill.steps) {
                val text = step.text ?: step.contentDescription
                val xy = step.resolvedXy ?: step.xy
                arr.put(JSONObject().apply {
                    when {
                        !text.isNullOrBlank() -> {
                            put("op", "tap_text")
                            put("text", text)
                        }
                        !step.viewId.isNullOrBlank() -> {
                            put("op", "tap_id")
                            put("id", step.viewId)
                        }
                        !xy.isNullOrBlank() -> {
                            put("op", "tap_xy")
                            put("x", xy.substringBefore(',').trim().toIntOrNull() ?: 0)
                            put("y", xy.substringAfter(',').trim().toIntOrNull() ?: 0)
                        }
                        else -> put("op", "wait").also { put("ms", 300) }
                    }
                })
            }
            arr
        }
        arguments.has("steps") -> runCatching { JSONArray(arguments.optString("steps")) }.getOrElse {
            return McpToolShared.error("steps 不是合法 JSON 数组")
        }
        else -> return McpToolShared.error("需要 skill 或 steps")
    }
    val log = StringBuilder()
    for (i in 0 until steps.length()) {
        val step = steps.optJSONObject(i) ?: continue
        val plan = stepCommand(step, allowCrossApp) ?: continue
        plan.error?.let { reason ->
            return McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("步骤 ${i + 1} 被拦下：$reason\n$log")),
                isError = true,
            )
        }
        val envelope = if (plan.cmd.startsWith("android-a11y-cli")) pilotCli(plan.cmd) else null
        val failed = envelope?.let { !it.optBoolean("ok") } ?: false
        log.append("${i + 1}. ${plan.label.ifBlank { plan.cmd }}")
            .append(if (failed) " ✗ ${envelope?.optJSONObject("error")?.optString("message").orEmpty()}" else " ✓")
            .append('\n')
        if (failed) {
            return McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("步骤 ${i + 1} 失败，已中止：\n$log")),
                isError = true,
            )
        }
    }
    if (saveAs.isNotEmpty()) {
        val store = A11ySkillStore.forContext(context)
        val converted = ArrayList<A11ySkillStore.Step>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            converted.add(
                A11ySkillStore.Step(
                    kind = "tap",
                    packageName = null,
                    text = step.optString("text").ifBlank { null },
                    viewId = step.optString("id").ifBlank { null },
                    contentDescription = null,
                    xy = if (step.has("x")) "${step.optInt("x")},${step.optInt("y")}" else null,
                    atMs = (i + 1).toLong() * 300L,
                ),
            )
        }
        store.save(saveAs, "pilot_run 结晶", converted, null)
        log.append("已结晶为技能：$saveAs\n")
    }
    return McpServerCore.CallResult(
        content = JSONArray().put(McpToolShared.text("执行完成 ${steps.length()} 步\n$log")),
    )
}

private fun pilotSkills(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val action = arguments.optString("action").trim().lowercase()
    val name = arguments.optString("name").trim()
    val context = deps.context ?: return McpToolShared.error("需要 Android context（单元测试环境不可用）")
    val store = A11ySkillStore.forContext(context)
    return when (action) {
        "list" -> {
            val all = store.all()
            val lines = all.joinToString("\n") {
                "${it.name}  ${it.steps.size} 步  pkg=${it.packageName ?: "-"}  失败连击=${it.failStreak}"
            }
            McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("技能 ${all.size} 条：\n${lines.ifBlank { "（空）" }}")),
            )
        }
        "show" -> {
            val skill = store.byName(name) ?: return McpToolShared.error("技能不存在：$name")
            val lines = skill.steps.mapIndexed { i, s ->
                "${i + 1}. text=${s.text ?: "-"} id=${s.viewId ?: "-"} xy=${s.resolvedXy ?: s.xy ?: "-"}"
            }.joinToString("\n")
            McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("${skill.name}（${skill.steps.size} 步）\n$lines")),
            )
        }
        "del" -> {
            val skill = store.byName(name) ?: return McpToolShared.error("技能不存在：$name")
            val removed = store.delete(skill.id)
            McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text(if (removed) "已删除：$name" else "删除失败：$name")),
            )
        }
        else -> McpToolShared.error("action 只能是 list / show / del")
    }
}

private fun pilotAudit(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val limit = clamp(arguments.optInt("lines", 20).takeIf { it > 0 } ?: 20, 1, 100)
    val sections = ArrayList<String>()

    // 1) App 自己的配置/门审计（进程内，一定能读）
    runCatching {
        val payload = ConfigBridge.auditList(limit, "all")
        val entries = payload.optJSONArray("entries") ?: payload.optJSONArray("items")
        if (entries != null && entries.length() > 0) {
            val lines = (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                "${e.optString("ts", e.optString("timestamp"))}  ${e.optString("toolName", e.optString("field"))}  ${e.optString("decision", e.optString("action"))}"
            }
            if (lines.isNotEmpty()) sections.add("App 审计：\n" + lines.joinToString("\n"))
        }
    }

    // 2) 独立面板的审计（存在才读，不存在就跳过）
    runCatching {
        val tail = McpToolShared.runInGuest(
            "tail -n $limit /var/minis/workspace/lemon-panel/stage/audit.jsonl 2>/dev/null || true",
            10,
        ).output.trim()
        if (tail.isNotBlank()) sections.add("面板审计：\n$tail")
    }

    val body = sections.joinToString("\n\n").ifBlank { "（没有审计记录）" }
    return McpServerCore.CallResult(content = JSONArray().put(McpToolShared.text(body)))
}
