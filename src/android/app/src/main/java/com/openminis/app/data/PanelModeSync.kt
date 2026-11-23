package com.openminis.app.data

import android.util.Log
import com.openminis.app.security.SessionPermissionMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-panel-mode-sync] 会话权限档 → 小柠檬面板 `/api/mode` 的**单向**同步。
 *
 * ## 要解决的问题
 *
 * 面板（8794）和 App 各有一套「变更前确认 / 自主模式 / 完全访问」，语义一一对应
 * （`SessionPermissionMode` 与 `policy.json` 的三档），但状态是两份：
 * 用户在 App 里点了完全访问，面板还停在变更前确认，同一个 `am force-stop`
 * 在聊天里直接跑、在面板里被 428 拦住。用户看到的是「同一台机器，两套脾气」。
 *
 * ## 为什么单向（App → 面板）而不是双向
 *
 * App 的会话档是**每会话**的（`sessions.permission_mode`），面板是**全进程**的
 * （一个进程一个档）。全进程档覆盖不了每会话档，反过来把面板档写回 App 会
 * 悄悄改掉用户没打开的那个会话。所以只把「用户当下这个选择」推给面板，
 * 面板档作为「最近一次选择」的记录，不参与 App 的判定。
 *
 * ## 为什么在这里而不是 ChatViewModel
 *
 * `SessionPermissionPrefs.remember()` 是所有写入路径的汇合点：聊天页的药丸、
 * 设置页、会话菜单改档最终都会到这里（见该文件头注释里两个历史 bug）。
 * 在汇合点挂一个 fire-and-forget 推送，不用逐个调用点补代码，也不会漏。
 *
 * 失败语义：面板没起时 HTTP 会立刻被拒（连接本机 8794，400ms 超时），
 * 只写一行 debug 日志，不影响存盘，不抛给 UI。
 */
internal object PanelModeSync {

    private const val TAG = "PanelModeSync"
    private const val ENDPOINT = "http://127.0.0.1:8794/api/mode"
    private const val CONNECT_TIMEOUT_MS = 400
    private const val READ_TIMEOUT_MS = 600

    /** 单线程串行：两次快速切档不会乱序，也不会并发开连接。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "panel-mode-sync").apply { isDaemon = true }
    }

    /** 上次已推送的档位，避免同一档重复打一次 HTTP。 */
    private val lastPushed = AtomicReference<String?>(null)

    /** 冷启动：把 App 记住的默认档补推一次（面板可能停在别处）。 */
    fun primeCurrent(mode: SessionPermissionMode) {
        push(mode, force = true)
    }

    /** 用户切档时调用。同档去重，跨档串行推送。 */
    fun push(mode: SessionPermissionMode, force: Boolean = false) {
        val token = mode.toStoredToken()
        if (!force && lastPushed.getAndSet(token) == token) return
        lastPushed.set(token)
        worker.submit {
            runCatching { post(token) }
                .onFailure { Log.d(TAG, "panel /api/mode unreachable: ${it.message}") }
        }
    }

    private fun post(mode: String) {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = """{"mode":"$mode","reason":"App 设置同步"}"""
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            // 必须消费响应体，否则连接不会被回收复用
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}