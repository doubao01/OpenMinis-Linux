package com.openminis.app.tools

import android.content.Context

/**
 * User-configurable search backend for [WebSearchTool].
 * DuckDuckGo stays the default (no key). SearXNG / Bing / custom are opt-in.
 */
object WebSearchSettings {
    const val PREFS = "web_search_prefs"
    const val KEY_ENGINE = "engine"
    const val KEY_SEARXNG_URL = "searxng_url"
    const val KEY_BING_KEY = "bing_key"
    const val KEY_FALLBACK = "fallback_ddg"
    const val KEY_CUSTOM_URL = "custom_url"
    const val KEY_CUSTOM_KEY = "custom_key"
    const val KEY_CUSTOM_KEY_HEADER = "custom_key_header"
    const val KEY_ENGINE_EXPLICIT = "engine_explicit"
    const val KEY_TAVILY = "tavily_key"
    const val KEY_BOCHA = "bocha_key"
    const val KEY_EXA = "exa_key"
    const val KEY_BRAVE = "brave_key"
    const val KEY_JINA = "jina_key"
    const val KEY_ZHIPU = "zhipu_key"
    const val KEY_KEYLESS_CN = "keyless_cn"

    enum class Engine(val id: String, val needsKey: Boolean = false) {
        DDG("ddg"),
        SEARXNG("searxng"),
        BING("bing", needsKey = true),
        TAVILY("tavily", needsKey = true),
        BOCHA("bocha", needsKey = true),
        EXA("exa", needsKey = true),
        BRAVE("brave", needsKey = true),
        JINA("jina", needsKey = true),
        ZHIPU("zhipu", needsKey = true),
        CUSTOM("custom"),
        ;

        companion object {
            fun fromId(raw: String?): Engine =
                entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DDG
        }
    }

    fun engine(context: Context): Engine {
        val raw = prefs().stringOrNull(KEY_ENGINE)
        if (!raw.isNullOrBlank()) return Engine.fromId(raw)
        // [T-android-websearch-engine-sticky] Auto-pick the first configured
        // keyed engine ONLY until the user has ever made an explicit choice.
        // Before this flag, a user who had never opened Settings → Web
        // search but had been guided to paste a Tavily key somewhere else
        // would see engine() silently flip from DDG to Tavily the next
        // launch — an unexplained behavior change. Once they touch the
        // picker (setEngine below), the flag latches and the auto-pick
        // stops applying, so "user chose DDG then added a key" stays DDG.
        if (prefs().bool(KEY_ENGINE_EXPLICIT, false)) return Engine.DDG
        return firstConfigured(context) ?: Engine.DDG
    }

    /** Keyed backends the user has already filled in, in enum order. */
    fun configuredKeyed(context: Context): List<Engine> =
        Engine.entries.filter { it.needsKey && apiKey(context, it).isNotEmpty() }

    private fun firstConfigured(context: Context): Engine? = configuredKeyed(context).firstOrNull()

    fun setEngine(context: Context, engine: Engine) {
        // [T-android-websearch-engine-sticky] engine 与 explicit 标志必须同事务：
        // 只写 engine 而不置位标志，下次启动会被自动挑选逻辑覆盖回第一个已配置引擎。
        prefs().write {
            putString(KEY_ENGINE, engine.id)
            putBoolean(KEY_ENGINE_EXPLICIT, true)
        }
    }

    fun searxngUrl(context: Context): String =
        prefs().string(KEY_SEARXNG_URL, "")?.trim().orEmpty()

    fun setSearxngUrl(context: Context, url: String) {
        prefs().set(KEY_SEARXNG_URL, url.trim())
    }

    fun bingKey(context: Context): String =
        prefs().string(KEY_BING_KEY, "")?.trim().orEmpty()

    fun setBingKey(context: Context, key: String) {
        prefs().set(KEY_BING_KEY, key.trim())
    }

    fun apiKey(context: Context, engine: Engine): String {
        val key = when (engine) {
            Engine.BING -> KEY_BING_KEY
            Engine.TAVILY -> KEY_TAVILY
            Engine.BOCHA -> KEY_BOCHA
            Engine.EXA -> KEY_EXA
            Engine.BRAVE -> KEY_BRAVE
            Engine.JINA -> KEY_JINA
            Engine.ZHIPU -> KEY_ZHIPU
            else -> return ""
        }
        return prefs().string(key, "")?.trim().orEmpty()
    }

    fun setApiKey(context: Context, engine: Engine, value: String) {
        val key = when (engine) {
            Engine.BING -> KEY_BING_KEY
            Engine.TAVILY -> KEY_TAVILY
            Engine.BOCHA -> KEY_BOCHA
            Engine.EXA -> KEY_EXA
            Engine.BRAVE -> KEY_BRAVE
            Engine.JINA -> KEY_JINA
            Engine.ZHIPU -> KEY_ZHIPU
            else -> return
        }
        prefs().set(key, value.trim())
    }

    fun customUrl(context: Context): String =
        prefs().string(KEY_CUSTOM_URL, "")?.trim().orEmpty()

    fun setCustomUrl(context: Context, url: String) {
        prefs().set(KEY_CUSTOM_URL, url.trim())
    }

    fun customKey(context: Context): String =
        prefs().string(KEY_CUSTOM_KEY, "")?.trim().orEmpty()

    fun setCustomKey(context: Context, key: String) {
        prefs().set(KEY_CUSTOM_KEY, key.trim())
    }

    fun customKeyHeader(context: Context): String =
        prefs().string(KEY_CUSTOM_KEY_HEADER, "")?.trim().orEmpty()

    fun setCustomKeyHeader(context: Context, header: String) {
        prefs().set(KEY_CUSTOM_KEY_HEADER, header.trim())
    }

    fun fallbackEnabled(context: Context): Boolean =
        prefs().bool(KEY_FALLBACK, true)

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        prefs().set(KEY_FALLBACK, enabled)
    }

    /**
     * Key-free Chinese chain (Sogou → Bing RSS → Baidu) tried after every
     * configured engine failed. Default ON — it only ever runs when the
     * whole keyed chain plus DuckDuckGo returned nothing, so it cannot
     * spend quota or shadow the user's chosen backend.
     */
    fun keylessFallbackEnabled(context: Context): Boolean =
        prefs().bool(KEY_KEYLESS_CN, true)

    fun setKeylessFallbackEnabled(context: Context, enabled: Boolean) {
        prefs().set(KEY_KEYLESS_CN, enabled)
    }

    private fun prefs() = com.openminis.app.config.Prefs.file(PREFS)
}
