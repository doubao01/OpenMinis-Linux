package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition

/**
 * Installed online OpenAPI plugins + encrypted API keys.
 *
 * Adapted from XINCODE-Public PluginStore online flags
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 */
object OnlinePluginStore {
    private const val FLAGS = "plugin_online_flags"
    private const val KEYS_FILE = "plugin_online_keys"

    private fun fingerprintKey(pluginId: String) = "fingerprint_$pluginId"

    fun isInstalled(context: Context, plugin: PluginRegistry.RemotePlugin): Boolean {
        val prefs = com.openminis.app.config.Prefs.file(FLAGS)
        return prefs.bool(plugin.id) &&
            prefs.stringOrNull(fingerprintKey(plugin.id)) == plugin.configurationKey()
    }

    fun installedIds(context: Context): Set<String> {
        val registry = PluginRegistry.cached()
        return registry.filter { isInstalled(context, it) }.mapTo(mutableSetOf()) { it.id }
    }

    fun setInstalled(context: Context, plugin: PluginRegistry.RemotePlugin, installed: Boolean) {
        val prefs = com.openminis.app.config.Prefs.file(FLAGS)
        prefs.write {
            putBoolean(plugin.id, installed)
            if (installed) putString(fingerprintKey(plugin.id), plugin.configurationKey())
            else remove(fingerprintKey(plugin.id))
        }
        if (!installed) {
            val keys = com.openminis.app.config.Prefs.file(KEYS_FILE)
            val prefix = connectorScopePrefix(plugin.id)
            keys.remove(plugin.id)
            keys.removeAllWithPrefix(prefix)
            ConnectorNetworkPolicy.clearPluginConfigurations(context, plugin.id)
            OnlineApiTool.evictNetworkClient(plugin)
        }
    }

    fun apiKey(context: Context, plugin: PluginRegistry.RemotePlugin): String? {
        if (!isInstalled(context, plugin)) return null
        return com.openminis.app.config.Prefs.file(KEYS_FILE)
            .stringOrNull(plugin.configurationKey())?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, plugin: PluginRegistry.RemotePlugin, key: String?) {
        val prefs = com.openminis.app.config.Prefs.file(KEYS_FILE)
        val storageKey = plugin.configurationKey()
        if (key.isNullOrBlank()) prefs.remove(storageKey)
        else prefs.set(storageKey, key.trim())
    }

    fun toolDefinitions(context: Context): List<AgentToolDefinition> {
        val installed = installedIds(context)
        if (installed.isEmpty()) return emptyList()
        return PluginRegistry.cached()
            .filter { it.id in installed && isInstalled(context, it) }
            .flatMap { plugin -> plugin.tools.map { OnlineApiTool.definition(plugin, it) } }
    }

    /**
     * [T-android-agent-tools-memo] Cheap change-stamp for [toolDefinitions]:
     * covers both inputs (installed flags + the cached remote registry). Reading
     * two SharedPreferences values is orders of magnitude cheaper than building
     * the definitions, so the agent loop can key its memo on this per access.
     */
    fun toolDefinitionsStamp(context: Context): Long {
        val flags = com.openminis.app.config.Prefs.file(FLAGS).snapshot()
        val registry = com.openminis.app.config.Prefs.file("plugin_registry").stringOrNull("plugin_registry_cache")
        return 31L * flags.hashCode() + (registry?.hashCode() ?: 0)
    }
}
