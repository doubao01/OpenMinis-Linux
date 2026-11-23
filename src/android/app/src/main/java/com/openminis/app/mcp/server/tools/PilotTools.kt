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
        description = "[LemonPilot native] Semantic perception: parse the current screen's view tree into " +
            "a semantic element list (text/id/bounds/clickable), filtered by keyword. " +
            "Same contract as the panel's pilot_perceive.",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("keyword", JSONObject().apply {
                    put("type", "string")
                    put("description", "Keyword filter (matches text/desc or id; empty = all)")
                })
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Max entries (default 40, cap 80)")
                })
            })
        },
        handler = { pilotPerceive(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_tap",
        description = "[LemonPilot native] Semantic tap: locate a control by text or resource-id and tap " +
            "its center, so callers never hard-code coordinates.",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Control text or content-description substring")
                })
                put("id", JSONObject().apply {
                    put("type", "string")
                    put("description", "resource-id substring (used when text is empty)")
                })
                put("index", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Index among matches (default 0)")
                })
            })
        },
        handler = { pilotTap(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_run",
        description = "[LemonPilot native] Run a task: replay a crystallized skill (skill=name) or execute a " +
            "JSON step array (open_app/stop_app/tap_text/tap_id/tap_xy/swipe/key/text/wait/assert_text). " +
            "Pass save_as to crystallize the steps into a skill for zero-token replay.",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("skill", JSONObject().apply {
                    put("type", "string")
                    put("description", "Skill name to replay (mutually exclusive with steps)")
                })
                put("steps", JSONObject().apply {
                    put("type", "string")
                    put("description", "JSON array of steps, as a string")
                })
                put("save_as", JSONObject().apply {
                    put("type", "string")
                    put("description", "Crystallize the steps under this name on success")
                })
                put("confirm", JSONObject().apply {
                    put("type", "string")
                    put("description", "1 = allow cross-app actions such as open_app / stop_app")
                })
            })
        },
        handler = { pilotRun(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_skills",
        description = "[LemonPilot native] Skill library: list, show one skill's steps, or delete. " +
            "Shares the same a11y_skills.json the app's recorder and replayer use.",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("description", "list | show | del")
                })
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Skill name (required for show / del)")
                })
            })
            put("required", JSONArray().put("action"))
        },
        handler = { pilotSkills(deps, it) },
    ),
    McpToolSpec(
        name = "pilot_audit",
        description = "[LemonPilot native] Audit: read the app's config/gate audit trail (last N entries); " +
            "when a standalone panel is also running, append its tail for cross-checking.",
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("lines", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Number of entries (default 20, cap 100)")
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
    if (seen.isEmpty() && lastError != null) return pilotFail(lastError, "ui find failed")
    val body = if (seen.isEmpty()) {
        "(no matching semantic elements; keyword=${keyword.ifBlank { "<all>" }})"
    } else {
        seen.keys.joinToString("\n")
    }
    return McpServerCore.CallResult(
        content = JSONArray().put(McpToolShared.text("Semantic elements: ${seen.size}\n$body")),
    )
}

private fun pilotTap(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val text = arguments.optString("text").trim()
    val id = arguments.optString("id").trim()
    val index = arguments.optInt("index", 0).coerceAtLeast(0)
    if (text.isEmpty() && id.isEmpty()) return McpToolShared.error("text or id is required")
    val cmd = if (text.isNotEmpty()) {
        "android-a11y-cli tap text ${McpToolShared.shellEscape(text)} --contains --index $index"
    } else {
        "android-a11y-cli tap id ${McpToolShared.shellEscape(id)}"
    }
    val envelope = pilotCli(cmd) ?: return McpToolShared.error("tap returned no parseable result")
    if (!envelope.optBoolean("ok")) {
        // 面板的行为：按 text 找不到时，把同一个串当 id 再试一次。
        if (text.isNotEmpty()) {
            val retry = pilotCli("android-a11y-cli tap id ${McpToolShared.shellEscape(text)}")
            if (retry != null && retry.optBoolean("ok")) {
                return McpServerCore.CallResult(
                    content = JSONArray().put(McpToolShared.text("Matched by id and tapped: $text")),
                )
            }
        }
        return pilotFail(envelope, "tap failed")
    }
    val data = envelope.optJSONObject("data")
    val where = listOfNotNull(
        data?.optString("x")?.takeIf { it.isNotBlank() }?.let { "x=$it" },
        data?.optString("y")?.takeIf { it.isNotBlank() }?.let { "y=$it" },
        data?.optString("nodeId")?.takeIf { it.isNotBlank() }?.let { "node=$it" },
    ).joinToString(" ")
    return McpServerCore.CallResult(
        content = JSONArray().put(
            McpToolShared.text("Tapped: ${text.ifBlank { id }}${if (where.isBlank()) "" else " ($where)"}"),
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
            if (!allowCrossApp) return StepPlan("", "open_app $pkg", "open_app requires confirm=1")
            StepPlan("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", "open $pkg")
        }
        "stop_app" -> {
            val pkg = esc("pkg").ifBlank { esc("package") }
            if (!allowCrossApp) return StepPlan("", "stop_app $pkg", "stop_app requires confirm=1")
            StepPlan("am force-stop $pkg", "stop $pkg")
        }
        "tap_text" -> StepPlan(
            "android-a11y-cli tap text ${esc("text")} --contains --index ${int("index")}",
            "tap \"${v("text")}\"",
        )
        "tap_id" -> StepPlan("android-a11y-cli tap id ${esc("id")}", "tap id=${v("id")}")
        "tap_xy" -> StepPlan("android-a11y-cli tap xy ${int("x")} ${int("y")}", "tap (${int("x")},${int("y")})")
        "swipe" -> StepPlan(
            "android-a11y-cli gesture swipe ${int("x1")} ${int("y1")} ${int("x2")} ${int("y2")} ${int("ms", 300)}",
            "swipe",
        )
        "key" -> StepPlan("android-a11y-cli input key ${esc("key")}", "key ${v("key")}")
        "text" -> StepPlan("android-a11y-cli input text ${esc("text")}", "type text")
        "wait" -> {
            val ms = int("ms", 500).coerceAtLeast(50)
            StepPlan("sleep ${ms / 1000.0}", "wait ${ms}ms")
        }
        "assert_text" -> null   // 断言在调用侧校验，不发命令
        else -> null
    }
}

private fun pilotRun(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val skillName = arguments.optString("skill").trim()
    val saveAs = arguments.optString("save_as").trim()
    val allowCrossApp = arguments.optString("confirm").trim() == "1"
    val context = deps.context ?: return McpToolShared.error("Android context required (unavailable in JVM tests)")
    val steps: JSONArray = when {
        skillName.isNotEmpty() -> {
            val store = A11ySkillStore.forContext(context)
            val skill = store.byName(skillName) ?: return McpToolShared.error("No such skill: $skillName")
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
            return McpToolShared.error("steps is not a valid JSON array")
        }
        else -> return McpToolShared.error("skill or steps is required")
    }
    val log = StringBuilder()
    for (i in 0 until steps.length()) {
        val step = steps.optJSONObject(i) ?: continue
        val plan = stepCommand(step, allowCrossApp) ?: continue
        plan.error?.let { reason ->
            return McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("Step ${i + 1} blocked: $reason\n$log")),
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
                content = JSONArray().put(McpToolShared.text("Step ${i + 1} failed; aborted:\n$log")),
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
        store.save(saveAs, "pilot_run crystallized", converted, null)
        log.append("Crystallized as skill: $saveAs\n")
    }
    return McpServerCore.CallResult(
        content = JSONArray().put(McpToolShared.text("Done: ${steps.length()} step(s)\n$log")),
    )
}

private fun pilotSkills(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val action = arguments.optString("action").trim().lowercase()
    val name = arguments.optString("name").trim()
    val context = deps.context ?: return McpToolShared.error("Android context required (unavailable in JVM tests)")
    val store = A11ySkillStore.forContext(context)
    return when (action) {
        "list" -> {
            val all = store.all()
            val lines = all.joinToString("\n") {
                "${it.name}  ${it.steps.size} step(s)  pkg=${it.packageName ?: "-"}  failStreak=${it.failStreak}"
            }
            McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("Skills: ${all.size}\n${lines.ifBlank { "(empty)" }}")),
            )
        }
        "show" -> {
            val skill = store.byName(name) ?: return McpToolShared.error("No such skill: $name")
            val lines = skill.steps.mapIndexed { i, s ->
                "${i + 1}. text=${s.text ?: "-"} id=${s.viewId ?: "-"} xy=${s.resolvedXy ?: s.xy ?: "-"}"
            }.joinToString("\n")
            McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text("${skill.name} (${skill.steps.size} step(s))\n$lines")),
            )
        }
        "del" -> {
            val skill = store.byName(name) ?: return McpToolShared.error("No such skill: $name")
            val removed = store.delete(skill.id)
            McpServerCore.CallResult(
                content = JSONArray().put(McpToolShared.text(if (removed) "Deleted: $name" else "Delete failed: $name")),
            )
        }
        else -> McpToolShared.error("action must be list / show / del")
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
            if (lines.isNotEmpty()) sections.add("App audit:\n" + lines.joinToString("\n"))
        }
    }

    // 2) 独立面板的审计（存在才读，不存在就跳过）
    runCatching {
        val tail = McpToolShared.runInGuest(
            "tail -n $limit /var/minis/workspace/lemon-panel/stage/audit.jsonl 2>/dev/null || true",
            10,
        ).output.trim()
        if (tail.isNotBlank()) sections.add("Panel audit:\n$tail")
    }

    val body = sections.joinToString("\n\n").ifBlank { "(no audit entries)" }
    return McpServerCore.CallResult(content = JSONArray().put(McpToolShared.text(body)))
}
