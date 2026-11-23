package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目级记忆检索的回归用例。
 *
 * 覆盖三处实测缺陷（都出自「行 ±2 窗口 + 裸子串」的旧实现）：
 *  1. 单字符 ASCII token 让 AND 恒真（搜 `a` 命中整份日志）；
 *  2. 相邻两条记忆被同一 5 行窗口覆盖，跨条目误命中；
 *  3. HTML 注释内容参与匹配，注释会被检索到。
 */
class MemorySearchTest {

    private val fixture = """
        ---
        type: daily
        ---
        <!-- 2026-11-23 20:57:25 --> <!-- minis-memory:08e2eb2b53fe4e75942cbec93de428d3 -->
        # 长条目测试 LONG-1
        行01 填充文本。
        行02 填充文本。
        尾部标记 TAIL-ZZ 结束。

        <!-- 2026-11-23 20:55:09 --> <!-- minis-memory:c3d52606ab294df6 -->
        # 并发测试 PARA-B
        两个并发写入之二，标志 PARA-B。

        <!-- 2026-11-23 20:55:09 --> <!-- minis-memory:d7e964cd7bca1455 -->
        # 并发测试 PARA-A
        两个并发写入之一，标志 PARA-A。

        <!-- 2026-11-23 20:54:28 --> <!-- minis-memory:b76326cecf6c40a0 -->
        # 记忆自测 ZETA-1
        中文检索关键词。隐藏注释 <!-- CMT-9931 secret-note --> 结束。
    """.trimIndent()

    @Test
    fun singleLetterTokenIsDroppedInsteadOfMatchingEverything() {
        assertTrue(MemorySearch.tokenize("a").isEmpty())
        assertTrue(MemorySearch.search(fixture, "a").isEmpty())
        // 中文单字保留：中文没有词边界，单字是有效检索单位
        assertEquals(1, MemorySearch.search(fixture, "藏").size)
    }

    @Test
    fun keywordsMustLandInTheSameEntry() {
        assertTrue(MemorySearch.search(fixture, "PARA-A PARA-B").isEmpty())
        assertEquals(1, MemorySearch.search(fixture, "PARA-A").size)
    }

    @Test
    fun adjacentEntriesAreNotMergedIntoOneHit() {
        val hits = MemorySearch.search(fixture, "并发")
        assertEquals(2, hits.size)
        assertEquals(setOf("# 并发测试 PARA-A", "# 并发测试 PARA-B"), hits.map { it.title }.toSet())
    }

    @Test
    fun htmlCommentsAreNotSearchable() {
        assertTrue(MemorySearch.search(fixture, "CMT-9931").isEmpty())
        assertEquals(1, MemorySearch.search(fixture, "ZETA-1").size)
        assertFalse(MemorySearch.search(fixture, "ZETA-1").first().body.contains("CMT-9931"))
    }

    @Test
    fun hitsCarryTimestampAndTitle() {
        val hit = MemorySearch.search(fixture, "TAIL-ZZ").single()
        assertEquals("2026-11-23 20:57:25", hit.timestamp)
        assertEquals("# 长条目测试 LONG-1", hit.title)
        assertTrue(hit.render().startsWith("<!-- 2026-11-23 20:57:25 -->\n# 长条目测试 LONG-1"))
    }

    @Test
    fun entrySplitIgnoresEntryIdComment() {
        val entries = MemorySearch.splitEntries(fixture)
        assertEquals(4, entries.size)
        assertFalse(entries.first().body.contains("minis-memory:"))
    }

    /** 漂移守卫：检索必须走 MemorySearch，旧的 ±2 行窗口不能再回来。 */
    @Test
    fun productionSearchStillUsesEntryScopedSearch() {
        val root = ProductionSources.mainRoot() ?: return
        val source = java.io.File(root, "data/repository/MemoryRepository.kt").readText()
        assertTrue(source.contains("MemorySearch.search("))
        assertFalse(source.contains("val windowStart = maxOf(0, i - 2)"))
    }
}
