package com.openminis.app.data.repository

/**
 * 记忆检索的条目级实现（纯 Kotlin，无 Android 依赖，可在 JVM 上单测）。
 *
 * 替换 [MemoryRepository.searchMemory] 里「行 ±2 窗口 + 裸子串」的匹配方式，修三处实测缺陷：
 *
 *  1. 单字符/单字母 ASCII 关键词不过滤：搜 `a` 会命中整份日志，多词 AND 被这类 token 变成恒真；
 *  2. AND 判定基于「相邻 5 行窗口」：两条相邻的不同记忆会被当成同一条命中（跨条目污染）；
 *  3. HTML 注释参与匹配：`<!-- ... -->` 里的内容既被检索、又被回填进上下文。
 *
 * 另外让每条命中带时间戳与标题，模型能看清命中的是哪一条记忆，而不是一段无出处的行窗口。
 */
object MemorySearch {

    /** 条目分隔标记：memory_write 写入的时间戳注释（行首锚定由调用方保证格式一致）。 */
    private val ENTRY_MARK = Regex("""<!--\s*\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\s*-->""")

    /** 条目 id 注释，紧跟时间戳，属于元信息。 */
    private val ENTRY_ID = Regex("""^\s*<!--\s*minis-memory:[^>]*-->\s*""")

    /** HTML 注释（含内容）。 */
    private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")

    private const val MAX_HIT_BODY_LINES = 24
    private const val DEFAULT_MAX_HITS = 8

    data class Entry(val marker: String?, val body: String) {
        val timestamp: String =
            marker?.let { Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").find(it)?.value } ?: ""

        /** 条目首个 Markdown 标题；没有标题就退回首个非空行。 */
        val title: String =
            body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#") }
                ?: body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: ""
    }

    /**
     * 关键词切分：中文整词保留，ASCII 丢弃长度 < 2 的碎片。
     * `a`、`b`、`1` 在英文正文里到处命中，留着等于取消 AND 条件。
     */
    fun tokenize(raw: String): List<String> =
        raw.trim().lowercase()
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .filter { token -> token.any { it.code > 127 } || token.length >= 2 }
            .distinct()

    fun normalizeTokens(tokens: List<String>): List<String> =
        tokens.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .filter { token -> token.any { it.code > 127 } || token.length >= 2 }
            .distinct()

    /** 去掉 HTML 注释，注释里的文本不参与检索。 */
    fun stripComments(text: String): String = HTML_COMMENT.replace(text, " ")

    /** 按时间戳标记切条目；没有标记的旧文件整体算一条。顺序与文件一致（最新在前）。 */
    fun splitEntries(content: String): List<Entry> {
        val marks = ENTRY_MARK.findAll(content).toList()
        if (marks.isEmpty()) return listOf(Entry(null, content))
        return marks.mapIndexed { i, m ->
            val start = m.range.last + 1
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else content.length
            val body = content.substring(start, end).replaceFirst(ENTRY_ID, "")
            Entry(m.value, body.trim('\n'))
        }
    }

    data class Hit(val timestamp: String, val title: String, val body: String) {
        /** 渲染：时间戳行 + 标题 + 正文（超长条目截断）。 */
        fun render(): String {
            val lines = body.lines()
            val shown = if (lines.size > MAX_HIT_BODY_LINES) {
                lines.take(MAX_HIT_BODY_LINES).joinToString("\n") +
                    "\n… (entry truncated, ${lines.size - MAX_HIT_BODY_LINES} more lines)"
            } else body
            val head = buildString {
                append("<!-- ").append(if (timestamp.isEmpty()) "no-timestamp" else timestamp).append(" -->")
                if (title.isNotEmpty()) append("\n").append(title)
            }
            return "$head\n$shown"
        }
    }

    /** 条目级检索：所有关键词必须落在同一条记忆内。 */
    fun search(content: String, keywords: String, maxHits: Int = DEFAULT_MAX_HITS): List<Hit> =
        search(content, tokenize(keywords), maxHits)

    fun search(content: String, tokens: List<String>, maxHits: Int = DEFAULT_MAX_HITS): List<Hit> {
        val normalized = normalizeTokens(tokens)
        if (normalized.isEmpty()) return emptyList()
        return splitEntries(content)
            .mapNotNull { entry ->
                val clean = stripComments(entry.body)
                val haystack = clean.lowercase()
                if (normalized.all { haystack.contains(it) }) {
                    Hit(entry.timestamp, entry.title, clean.trim())
                } else null
            }
            .take(maxHits)
    }
}
