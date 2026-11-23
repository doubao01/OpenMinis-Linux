package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务链选择与持久化的回归用例。
 *
 * 覆盖三道闸门（停用 / 只认显式边 / 深度上限）和新增字段的 JSON 往返，
 * 后者是升级兼容的关键：老客户端写下的不带 triggerKind 的 JSON 必须仍能读进来。
 */
class ScheduledChainTest {

    private fun task(
        id: String,
        after: String? = null,
        trigger: ScheduledTriggerKind = ScheduledTriggerKind.SCHEDULE,
        enabled: Boolean = true,
        createdAt: Long = 1_000L,
    ) = ScheduledTask(
        id = id,
        label = "t-$id",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p",
        triggerKind = trigger,
        afterTaskId = after,
        enabled = enabled,
        createdAt = createdAt,
    )

    @Test
    fun picksOnlyTasksBoundToCompletedTask() {
        val tasks = listOf(
            task("upstream"),
            task("child", after = "upstream", trigger = ScheduledTriggerKind.AFTER_TASK),
            task("other", after = "someone-else", trigger = ScheduledTriggerKind.AFTER_TASK),
            task("timer"), // 按时间触发的任务不该被链拉起
        )
        val picked = ScheduledChain.selectDependents(tasks, "upstream", depth = 0)
        assertEquals(listOf("child"), picked.map { it.id })
    }

    @Test
    fun disabledDependentIsSkipped() {
        val tasks = listOf(
            task("upstream"),
            task("child", after = "upstream", trigger = ScheduledTriggerKind.AFTER_TASK, enabled = false),
        )
        assertTrue(ScheduledChain.selectDependents(tasks, "upstream", depth = 0).isEmpty())
    }

    @Test
    fun depthCapStopsRunawayChain() {
        val tasks = listOf(
            task("a"),
            task("b", after = "a", trigger = ScheduledTriggerKind.AFTER_TASK),
        )
        assertEquals(1, ScheduledChain.selectDependents(tasks, "a", depth = ScheduledChain.MAX_DEPTH - 1).size)
        assertTrue(ScheduledChain.selectDependents(tasks, "a", depth = ScheduledChain.MAX_DEPTH).isEmpty())
        // A→B→A 的环：深度到顶后不再返回任何下游，链终止
        val cycle = listOf(
            task("a", after = "b", trigger = ScheduledTriggerKind.AFTER_TASK),
            task("b", after = "a", trigger = ScheduledTriggerKind.AFTER_TASK),
        )
        assertTrue(ScheduledChain.selectDependents(cycle, "a", depth = ScheduledChain.MAX_DEPTH).isEmpty())
    }

    @Test
    fun dependentOrderIsStableByCreationTime() {
        val tasks = listOf(
            task("upstream"),
            task("late", after = "upstream", trigger = ScheduledTriggerKind.AFTER_TASK, createdAt = 3_000L),
            task("early", after = "upstream", trigger = ScheduledTriggerKind.AFTER_TASK, createdAt = 2_000L),
        )
        assertEquals(
            listOf("early", "late"),
            ScheduledChain.selectDependents(tasks, "upstream", depth = 0).map { it.id },
        )
    }

    @Test
    fun linkValidationRejectsSelfReferenceAndMissingUpstream() {
        val all = listOf(task("a"))
        assertTrue(ScheduledChain.isValidLink(task("a"), all))
        assertFalse(
            ScheduledChain.isValidLink(
                task("a", after = "a", trigger = ScheduledTriggerKind.AFTER_TASK), all,
            ),
        )
        assertFalse(
            ScheduledChain.isValidLink(
                task("b", after = "ghost", trigger = ScheduledTriggerKind.AFTER_TASK), all,
            ),
        )
        assertTrue(
            ScheduledChain.isValidLink(
                task("b", after = "a", trigger = ScheduledTriggerKind.AFTER_TASK), all,
            ),
        )
    }

    @Test
    fun jsonRoundTripKeepsChainFields() {
        val t = task("child", after = "upstream", trigger = ScheduledTriggerKind.AFTER_TASK)
        val back = ScheduledTask.fromJson(t.toJson())
        assertEquals(ScheduledTriggerKind.AFTER_TASK, back.triggerKind)
        assertEquals("upstream", back.afterTaskId)
        assertEquals(t.id, back.id)
    }

    @Test
    fun legacyJsonWithoutChainFieldsStillReads() {
        // 老版本写下的 JSON：没有 triggerKind / afterTaskId 两个键
        val legacy = task("legacy").toJson().apply { remove("triggerKind"); remove("afterTaskId") }
        val back = ScheduledTask.fromJson(legacy)
        assertEquals(ScheduledTriggerKind.SCHEDULE, back.triggerKind)
        assertNull(back.afterTaskId)
    }
}
