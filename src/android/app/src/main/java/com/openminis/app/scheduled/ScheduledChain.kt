package com.openminis.app.scheduled

/**
 * 任务链的选择逻辑（纯函数，可在 JVM 上单测）。
 *
 * 上游任务成功跑完后，由 [ScheduledAgentRunner] 调用 [selectDependents] 找出
 * 挂在它下面的下游任务。三道闸门各自都必要：
 *
 *  - `enabled`：停用的任务不该被链拉起；
 *  - `triggerKind == AFTER_TASK` 且 `afterTaskId` 精确匹配：只认显式绑定的边；
 *  - `depth < MAX_DEPTH`：A→B→A 这种环必须有终点，否则一次触发就能刷爆执行队列。
 */
object ScheduledChain {

    /** 一条链最多串几级。超过就停，下游任务保持待触发状态等人工或下一次调度。 */
    const val MAX_DEPTH = 3

    /**
     * @param tasks 当前全部任务
     * @param completedTaskId 刚刚成功完成的任务 id
     * @param depth 当前已走过的层级（上游任务自己算第 0 层）
     * @return 需要立刻拉起的下游任务，按创建时间排序，保证同一批里的执行顺序稳定
     */
    fun selectDependents(
        tasks: List<ScheduledTask>,
        completedTaskId: String,
        depth: Int,
        maxDepth: Int = MAX_DEPTH,
    ): List<ScheduledTask> {
        if (depth >= maxDepth) return emptyList()
        if (completedTaskId.isEmpty()) return emptyList()
        return tasks.asSequence()
            .filter { it.enabled }
            .filter { it.triggerKind == ScheduledTriggerKind.AFTER_TASK }
            .filter { it.afterTaskId == completedTaskId }
            .filter { it.id != completedTaskId }
            .sortedBy { it.createdAt }
            .toList()
    }

    /**
     * 链边是否成立（用于保存时校验与 UI 提示）：不能自指、上游必须存在。
     * 环不在这里拦——[selectDependents] 的深度闸门已经保证环会终止，
     * 这里只挡掉「绑到自己」和「上游不存在」两种一眼错的配置。
     */
    fun isValidLink(task: ScheduledTask, all: List<ScheduledTask>): Boolean {
        if (task.triggerKind != ScheduledTriggerKind.AFTER_TASK) return true
        val up = task.afterTaskId ?: return false
        if (up == task.id) return false
        return all.any { it.id == up }
    }
}
