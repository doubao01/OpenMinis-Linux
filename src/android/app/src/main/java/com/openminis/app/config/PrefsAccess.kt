package com.openminis.app.config

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 一个 prefs 文件的类型化句柄 —— 建在 [PrefsBackend] 之上。
 *
 * ```
 * private val ui = Prefs.file("ui_prefs")
 * var splitMode by ui.boolProp("tablet_list_pane_collapsed", false)
 * val lastModel = ui.string("chat.last_model", "auto")
 * ```
 *
 * 为什么不像早先草案那样再定义一套 `PrefsSource`：`PrefsBackend` 已经是
 * 「键 → 值」的完整抽象（`PrefsFields.kt` 的 7 个配置字段类都建在它上面），
 * 再加一个同形状的接口只会让"哪个才是底层"变成新的歧义 —— 那正是这次
 * 重构要消除的东西。
 *
 * 唯一强制的东西：**键必须可追溯**。
 * - [PrefsRegistry] 已为该文件登记键清单 → 只接受登记过的键；
 * - 未登记（历史遗留文件）→ 至少要求 `前缀.名字` 形态。
 */
class PrefsHandle internal constructor(
    val spec: PrefsFileSpec,
    private val backend: PrefsBackend,
) {
    val name: String get() = spec.name

    fun string(key: String, default: String = ""): String = backend.readString(check(key), default) ?: default

    fun stringOrNull(key: String): String? = backend.readString(check(key), null)

    fun int(key: String, default: Int = 0): Int = backend.readInt(check(key), default)

    fun long(key: String, default: Long = 0L): Long = backend.readLong(check(key), default)

    fun float(key: String, default: Float = 0f): Float = backend.readFloat(check(key), default)

    fun bool(key: String, default: Boolean = false): Boolean = backend.readBoolean(check(key), default)

    fun stringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        backend.readStringSet(check(key), default) ?: default

    fun set(key: String, value: String?) = backend.write { putString(check(key), value) }
    fun set(key: String, value: Int) = backend.write { putInt(check(key), value) }
    fun set(key: String, value: Long) = backend.write { putLong(check(key), value) }
    fun set(key: String, value: Float) = backend.write { putFloat(check(key), value) }
    fun set(key: String, value: Boolean) = backend.write { putBoolean(check(key), value) }
    fun set(key: String, value: Set<String>?) = backend.write { putStringSet(check(key), value) }

    fun remove(key: String) = backend.write { remove(check(key)) }

    /**
     * 批量写：一次事务提交多个键（对应旧的 `prefs.edit()...apply()`）。
     * 每个键仍然过 [check]，所以批量写不能绕开键名校验。
     */
    fun write(block: PrefsBackend.Writer.() -> Unit) {
        backend.write { block(GuardedWriter(this)) }
    }

    /** 把 [PrefsBackend.Writer] 的每次调用都接到 [check] 后面。 */
    private inner class GuardedWriter(private val delegate: PrefsBackend.Writer) :
        PrefsBackend.Writer {
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(check(key), value)
        override fun putInt(key: String, value: Int) = delegate.putInt(check(key), value)
        override fun putLong(key: String, value: Long) = delegate.putLong(check(key), value)
        override fun putFloat(key: String, value: Float) = delegate.putFloat(check(key), value)
        override fun putString(key: String, value: String?) = delegate.putString(check(key), value)
        override fun putStringSet(key: String, value: Set<String>?) =
            delegate.putStringSet(check(key), value)
        override fun remove(key: String) = delegate.remove(check(key))
    }

    fun contains(key: String): Boolean = backend.contains(check(key))

    /** 本文件已落盘的全部键（不校验，用于诊断/迁移）。 */
    fun rawKeys(): Set<String> = backend.keys()

    fun boolProp(key: String, default: Boolean = false): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = bool(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = set(key, value)
        }

    fun stringProp(key: String, default: String = ""): ReadWriteProperty<Any?, String> =
        object : ReadWriteProperty<Any?, String> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = string(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = set(key, value)
        }

    fun intProp(key: String, default: Int = 0): ReadWriteProperty<Any?, Int> =
        object : ReadWriteProperty<Any?, Int> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = int(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = set(key, value)
        }

    fun floatProp(key: String, default: Float = 0f): ReadWriteProperty<Any?, Float> =
        object : ReadWriteProperty<Any?, Float> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = float(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) = set(key, value)
        }

    fun longProp(key: String, default: Long = 0L): ReadWriteProperty<Any?, Long> =
        object : ReadWriteProperty<Any?, Long> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = long(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = set(key, value)
        }

    /**
     * 键名守卫，两条规则见类注释。
     * 登记清单用 [PrefsFileSpec.keys] 的**值**（真实字符串键），不是常量名。
     *
     * 动态键（`"lastReadAt:" + sessionId`、`"mirror.selected.${x}"`）由
     * [PrefsFileSpec.dynamicPrefixes] 声明；只要命中前缀即放行。
     */
    private fun check(key: String): String {
        require(key.isNotBlank()) { "prefs key must not be blank" }
        // 1. 静态登记或动态前缀命中 —— 一定放行
        if (spec.accepts(key)) return key
        // 2. 该文件没有任何登记清单（历史遗留，扫描器没抓到键常量）——
        //    退回最弱约定：必须带 '前缀.名字' 形态，挡住裸键即可。
        if (spec.keys.isEmpty() && spec.dynamicPrefixes.isEmpty()) {
            require(key.contains('.')) {
                "key '$key' must look like 'prefix.name'; ${spec.name} has no registered key list, so only the prefix convention applies"
            }
            return key
        }
        // 3. 有登记清单但不包含该键 —— 明确的错
        throw IllegalArgumentException(
            buildString {
                append("key '$key' is not registered in PrefsRegistry for ${spec.name}")
                if (spec.dynamicPrefixes.isNotEmpty()) {
                    append("; allowed dynamic prefixes in this file: ")
                    append(spec.dynamicPrefixes.joinToString(", ") { "`$it`" })
                }
                append("; add `const val KEY_X = \"")
                append(key)
                append("\"` in the source file, then re-run gen_prefs_registry.py")
            }
        )
    }
}

/**
 * 全局入口。真实构造走 `Prefs.install(source)`（AppContainer 里装一次），
 * 测试构造走 `Prefs.install(InMemorySource())`。
 */
object Prefs {

    private var source: PrefsSource? = null
    private val cache = LinkedHashMap<String, PrefsHandle>()

    fun install(s: PrefsSource) {
        synchronized(this) {
            source = s
            cache.clear()
        }
    }

    fun uninstall() = synchronized(this) {
        source = null
        cache.clear()
    }

    fun file(name: String): PrefsHandle = synchronized(this) {
        cache.getOrPut(name) {
            val spec = PrefsRegistry.require(name)
            val s = source ?: error("Prefs is not installed: call Prefs.install(...) from MinisApp.onCreate")
            PrefsHandle(spec, s.backend(spec.name))
        }
    }

    /** 诊断/迁移用：绕开缓存与工厂，直接拿一个后端。 */
    fun handleFor(spec: PrefsFileSpec, backend: PrefsBackend) = PrefsHandle(spec, backend)
}
