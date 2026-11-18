package com.openminis.app.config

import android.content.SharedPreferences

/**
 * The narrow storage surface the config layer actually needs.
 *
 * Why an interface instead of taking [SharedPreferences] directly:
 * `SharedPreferences` is a final-ish Android interface whose only real
 * implementation requires a running Android runtime, which makes every
 * field untestable on the JVM. [PrefsBackend] has exactly the six reads
 * and one batch write that the field wrappers use — so a plain map
 * implementation ([InMemoryPrefsBackend]) is enough to unit-test every
 * config field.
 *
 * Semantics that MUST be preserved by any implementation (existing code
 * depends on all three):
 *  - [readInt]/[readLong]/[readFloat] return the supplied default when the
 *    key is absent OR holds a different primitive type (SharedPreferences
 *    throws ClassCastException in the second case; we normalise to the
 *    default because the agent surface must never crash on a corrupt value).
 *  - A write is durable when [write] returns — no `apply()`-style deferred
 *    flush, because a config write the agent reported as successful must
 *    survive an immediate process death.
 */
interface PrefsBackend {
    val name: String
    fun contains(key: String): Boolean
    fun readBoolean(key: String, defaultValue: Boolean): Boolean
    fun readInt(key: String, defaultValue: Int): Int
    fun readLong(key: String, defaultValue: Long): Long
    fun readFloat(key: String, defaultValue: Float): Float
    fun readString(key: String, defaultValue: String?): String?
    fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>?

    /**
     * Batch write. [block] receives a [Writer] and may call as many put/remove
     * as it wants; the implementation commits all of them in one transaction.
     */
    fun write(block: Writer.() -> Unit)

    interface Writer {
        fun putBoolean(key: String, value: Boolean)
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun putFloat(key: String, value: Float)
        fun putString(key: String, value: String?)
        fun putStringSet(key: String, value: Set<String>?)
        fun remove(key: String)
    }
}

/** [PrefsBackend] over a real `SharedPreferences`. The only Android-bound implementation. */
class SharedPrefsBackend(
    private val prefs: SharedPreferences,
) : PrefsBackend {

    override val name: String get() = prefsName ?: "<shared>"
    private var prefsName: String? = null

    constructor(prefs: SharedPreferences, name: String) : this(prefs) {
        this.prefsName = name
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        if (!prefs.contains(key)) defaultValue else runCatching { prefs.getBoolean(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readInt(key: String, defaultValue: Int): Int =
        if (!prefs.contains(key)) defaultValue else runCatching { prefs.getInt(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readLong(key: String, defaultValue: Long): Long =
        if (!prefs.contains(key)) defaultValue else runCatching { prefs.getLong(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readFloat(key: String, defaultValue: Float): Float =
        if (!prefs.contains(key)) defaultValue else runCatching { prefs.getFloat(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readString(key: String, defaultValue: String?): String? =
        if (!prefs.contains(key)) defaultValue else runCatching { prefs.getString(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>? =
        if (!prefs.contains(key)) defaultValue else runCatching { prefs.getStringSet(key, defaultValue)?.toSet() }.getOrDefault(defaultValue)

    /**
     * `commit()` not `apply()`: see the interface KDoc. Config writes are
     * rare (handful per session) so the main-thread cost is irrelevant,
     * while a lost write produces a "setting did not stick" bug report that
     * is very hard to reproduce.
     */
    override fun write(block: PrefsBackend.Writer.() -> Unit) {
        val editor = prefs.edit()
        val w = object : PrefsBackend.Writer {
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putFloat(key: String, value: Float) { editor.putFloat(key, value) }
            override fun putString(key: String, value: String?) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            override fun putStringSet(key: String, value: Set<String>?) {
                if (value == null) editor.remove(key) else editor.putStringSet(key, value)
            }
            override fun remove(key: String) { editor.remove(key) }
        }
        w.block()
        editor.commit()
    }
}

/** Test/offline backend. Deterministic, no Android runtime, no disk. */
class InMemoryPrefsBackend(
    override val name: String,
    initial: Map<String, Any?> = emptyMap(),
) : PrefsBackend {

    private val data = LinkedHashMap<String, Any?>(initial)

    /** Test hook: simulate a corrupt value of the wrong primitive type. */
    fun corrupt(key: String, raw: Any?) { data[key] = raw }

    fun snapshot(): Map<String, Any?> = data.toMap()

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defaultValue

    override fun readInt(key: String, defaultValue: Int): Int =
        (data[key] as? Int) ?: defaultValue

    override fun readLong(key: String, defaultValue: Long): Long =
        when (val v = data[key]) {
            is Long -> v
            is Int -> v.toLong()
            else -> defaultValue
        }

    override fun readFloat(key: String, defaultValue: Float): Float =
        when (val v = data[key]) {
            is Float -> v
            is Double -> v.toFloat()
            else -> defaultValue
        }

    override fun readString(key: String, defaultValue: String?): String? =
        (data[key] as? String) ?: defaultValue

    @Suppress("UNCHECKED_CAST")
    override fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>? =
        (data[key] as? Set<String>)?.toSet() ?: defaultValue

    override fun write(block: PrefsBackend.Writer.() -> Unit) {
        val pending = LinkedHashMap<String, Any?>()
        val removed = LinkedHashSet<String>()
        val w = object : PrefsBackend.Writer {
            override fun putBoolean(key: String, value: Boolean) { pending[key] = value; removed.remove(key) }
            override fun putInt(key: String, value: Int) { pending[key] = value; removed.remove(key) }
            override fun putLong(key: String, value: Long) { pending[key] = value; removed.remove(key) }
            override fun putFloat(key: String, value: Float) { pending[key] = value; removed.remove(key) }
            override fun putString(key: String, value: String?) {
                if (value == null) { pending.remove(key); removed.add(key) } else { pending[key] = value; removed.remove(key) }
            }
            override fun putStringSet(key: String, value: Set<String>?) {
                if (value == null) { pending.remove(key); removed.add(key) } else { pending[key] = value.toSet(); removed.remove(key) }
            }
            override fun remove(key: String) { pending.remove(key); removed.add(key) }
        }
        w.block()
        removed.forEach { data.remove(it) }
        data.putAll(pending)
    }
}
