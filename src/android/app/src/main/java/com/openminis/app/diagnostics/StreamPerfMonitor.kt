package com.openminis.app.diagnostics

import android.os.Debug
import com.openminis.app.logging.AppLogger

/**
 * [T-android-stream-pipeline-incremental] Per-streaming-turn render-pipeline
 * aggregator. Exists to quantify the frozen/live incremental flatten rollout
 * against the ANR baseline (minis-2026-06-10 crash loop: GC freeing
 * 130–180MB/s, buildFlatChatItems up to 100s, main-thread regex hang).
 *
 * Zero-overhead contract (the monitor must never become its own perf bug):
 *  - Hot path ([tick]) does primitive field math only — no allocation, no
 *    string building, no logging, no locks (single-writer: the pipeline's
 *    collect loop).
 *  - Turn boundaries ([turnStart]/[turnEnd]) each do two
 *    [Debug.getRuntimeStat] reads and at most ONE AppLogger line. Runtime
 *    stats are cheap counters maintained by ART; reading them twice per turn
 *    is negligible.
 *  - When no turn is active every call no-ops on a boolean check.
 *
 * The single summary line per turn gives the before/after comparison:
 *   [StreamPerf] sid=… ticks=N flattenAvgUs=… flattenMaxMs=… frozenHits=N-1/N
 *   rowsLast=total(frozen+live) gcCount=+x gcFreedMB=+y.z turnS=…
 * Baseline equivalents come from the existing PerfLongCtx slow-build lines and
 * logcat GC lines, so old logs remain comparable.
 */
object StreamPerfMonitor {

    private const val TAG = "StreamPerf"

    /**
     * 资源水位落盘目录。由 MinisApp 启动时 [attachFilesDir] 接上；没接上就跳过，
     * 崩溃报告里退化成「没有水位」而不是写到一个猜出来的路径。
     */
    @Volatile
    private var filesDir: java.io.File? = null

    /** 只在 Application.onCreate 调一次。 */
    fun attachFilesDir(dir: java.io.File) {
        filesDir = dir
    }

    /**
     * 每轮流式结束落一次资源水位（线程数 / maps / fd），供崩溃报告读取。
     * 采样读的是 /proc/self，代价微秒级；任何异常都吞掉——监控不能自己变成崩溃源。
     */
    private fun writeResourceWatermark() {
        val dir = filesDir ?: return
        try {
            val pid = android.os.Process.myPid()
            ResourceWatermark.pruneWatermarks(dir, pid)
            val sample = ResourceWatermark.sample()
            ResourceWatermark.writeWatermark(
                dir,
                pid,
                "sampled=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} " +
                    sample.fields() + "\n",
            )
        } catch (_: Throwable) {
        }
    }

    private var active = false
    private var sessionId: String = ""
    private var turnStartNs = 0L
    private var tickCount = 0L
    private var flattenTotalNs = 0L
    private var flattenMaxNs = 0L
    private var frozenHitTicks = 0L
    private var lastFrozenRows = 0
    private var lastLiveRows = 0
    private var gcCountStart = 0L
    private var gcFreedStart = 0L

    /** ART runtime stat, or 0 when unavailable (older devices / stat renamed). */
    private fun runtimeStat(key: String): Long =
        Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L

    /** Begin aggregating a streaming turn. Idempotent for the same session;
     *  a different session means the previous turn's collector was cancelled
     *  mid-stream (session switch) — flush its summary before starting. */
    fun turnStart(sid: String) {
        if (active && sid == sessionId) return
        if (active) turnEnd()
        active = true
        sessionId = sid
        turnStartNs = System.nanoTime()
        tickCount = 0
        flattenTotalNs = 0
        flattenMaxNs = 0
        frozenHitTicks = 0
        lastFrozenRows = 0
        lastLiveRows = 0
        gcCountStart = runtimeStat("art.gc.gc-count")
        gcFreedStart = runtimeStat("art.gc.bytes-freed")
    }

    /**
     * One pipeline tick (one sampled flatten+publish). [flattenNanos] covers
     * the off-main flatten work for this tick; [frozenReused] is true when the
     * frozen-prefix cache was reused as-is (the expected steady state).
     */
    fun tick(flattenNanos: Long, frozenReused: Boolean, frozenRows: Int, liveRows: Int) {
        if (!active) return
        tickCount++
        flattenTotalNs += flattenNanos
        if (flattenNanos > flattenMaxNs) flattenMaxNs = flattenNanos
        if (frozenReused) frozenHitTicks++
        lastFrozenRows = frozenRows
        lastLiveRows = liveRows
        // 时间线标记（debug-only）：与 dumpsys gfxinfo 的 CLOCK_MONOTONIC 对齐，
        // 用来判定哪些掉帧紧跟在 flatten 之后。挂在 tick 上而不是调用点，避免
        // ChatScreen 再长一行（架构门禁按文件行数设了天花板）。
        StreamJitterProbe.flatten(
            ms = flattenNanos / 1_000_000,
            frozenReused = frozenReused,
            frozen = frozenRows,
            live = liveRows,
            streamEmpty = liveRows == 0,
        )
    }

    /** End the turn and emit the one-line summary. No-op when not active. */
    fun turnEnd() {
        if (!active) return
        active = false
        val turnMs = (System.nanoTime() - turnStartNs) / 1_000_000
        val gcCountDelta = runtimeStat("art.gc.gc-count") - gcCountStart
        val gcFreedDeltaMb = (runtimeStat("art.gc.bytes-freed") - gcFreedStart) / 1_000_000.0
        val avgUs = if (tickCount > 0) flattenTotalNs / tickCount / 1_000 else 0
        AppLogger.info(
            TAG,
            "turn sid=$sessionId ticks=$tickCount flattenAvgUs=$avgUs " +
                "flattenMaxMs=${flattenMaxNs / 1_000_000} frozenHits=$frozenHitTicks/$tickCount " +
                "rowsLast=${lastFrozenRows + lastLiveRows}(frozen=$lastFrozenRows,live=$lastLiveRows) " +
                "gcCount=+$gcCountDelta gcFreedMB=+${String.format(java.util.Locale.US, "%.1f", gcFreedDeltaMb)} " +
                "turnS=${turnMs / 1000}",
        )
        writeResourceWatermark()
    }
}
