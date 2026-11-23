package com.openminis.app.ui.chat

// [T-android-split-chat] Slash-menu STATE methods (filter / open / dismiss /
// menu-state) extracted from ChatViewModel as extension functions. The action
// dispatcher executeSlashCommand stays in the class (entangled with compact/
// thinking/memory). 7 slash-state fields flipped private->internal. Verbatim.

import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import com.openminis.app.R

/** Filter available commands by current filter text, with dynamic
 *  localized subtitles. Subtitles read strings.xml via the injected
 *  Context so the panel respects system locale (T241).
 *
 *  [T-skill-slash a88ea8f9] After the built-in rows, append every
 *  enabled installed Skill as a `/<skill-name>` slash entry — tap
 *  fills the composer with `/<name>` and dismisses the menu (see
 *  [executeSlashCommand]). Disabled skills are hidden so toggling a
 *  skill off in Settings naturally removes it from the menu without
 *  uninstalling it.
 *
 *  [T-session-skill-toggle-override-global-android] Enablement is
 *  resolved per-session, not globally: [SkillRepository.isEnabledForSession]
 *  returns the session-scoped override when one exists and falls back to
 *  the global toggle otherwise. The previous code filtered on the raw
 *  global `isEnabled` flag, so a skill turned off globally but turned ON
 *  for this session never appeared in the `/` picker even though the
 *  prompt-injection path ([SkillRepository.skillPromptFragment]) already
 *  honored the override — the agent knew about the skill but the user
 *  couldn't surface it via slash. (DM 𝙓𝙄𝙉 304891.)
 */
/**
 * Static catalogue of slash commands, in display order. Extracted from
 * ChatViewModel (T-supervisor-slash-entry): the catalogue is data, not VM
 * state — subtitles are placeholders here and [filteredSlashCommands]
 * rebuilds them with the current localized state.
 */
internal val chatSlashCommands: List<SlashCommand> = listOf(
    SlashCommand(
        id = "clear",
        icon = Icons.Default.Delete,
        title = "Clear",
        subtitle = "",
    ),
    SlashCommand(
        id = "compact",
        icon = Icons.Default.Compress,
        title = "Compact",
        subtitle = "",
    ),
    SlashCommand(
        id = "memory",
        icon = Icons.Default.Psychology,
        title = "Memory",
        subtitle = "",
    ),
    SlashCommand(
        id = "thinking",
        icon = Icons.Default.Lightbulb,
        title = "Thinking",
        subtitle = "",
    ),
    SlashCommand(id = "goal", icon = Icons.Outlined.Build, title = "Goal", subtitle = ""),
    SlashCommand(
        id = "supervisor",
        icon = Icons.Outlined.Groups,
        title = "Supervisor",
        subtitle = "",
    ),
)

internal fun ChatViewModel.filteredSlashCommands(): List<SlashCommand> {
    val filter = _slashFilter.value.lowercase()
    val base = availableSlashCommands.map { cmd ->
        when (cmd.id) {
            "compact" -> cmd.copy(
                subtitle = context.getString(R.string.slash_compact_subtitle),
            )
            "memory" -> cmd.copy(
                subtitle = context.getString(
                    if (_memoryEnabled.value) R.string.slash_memory_writes_on
                    else R.string.slash_memory_writes_off,
                ),
            )
            "thinking" -> cmd.copy(
                subtitle = if (!currentModelSupportsReasoning) {
                    context.getString(R.string.slash_thinking_unsupported)
                } else {
                    context.getString(
                        R.string.slash_thinking_subtitle,
                        _thinkingLevel.value.localizedName(context),
                    )
                },
            )
            "clear" -> cmd.copy(
                subtitle = context.getString(R.string.slash_clear_subtitle),
            )
            else -> cmd
        }
    }
    val sid = activeSessionId
    val skillRows: List<SlashCommand> = skillRepository?.skills?.value
        ?.filter { skillRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { skill ->
            val trimmed = skill.description.trim()
            val sub = if (trimmed.isNotEmpty()) trimmed else "Skill · v${skill.version}"
            SlashCommand(
                id = "skill:${skill.id}",
                icon = Icons.Outlined.Extension,
                title = skill.name,
                subtitle = sub,
                isSkill = true,
            )
        } ?: emptyList()
    // [T-mcp-integration-android] MCP servers appear in the / picker too,
    // tagged [mcp] with a wrench icon to distinguish them from skills (⚡).
    // All servers enabled for this session are shown (not just the Top-20
    // disclosed in the system prompt). Tapping fills the composer with the
    // server name; discovery/call happens model-side via minis-mcp-cli.
    val mcpRows: List<SlashCommand> = mcpRepository?.servers?.value
        ?.filter { mcpRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.id.lowercase() }
        ?.map { server ->
            val note = server.note?.trim().orEmpty()
            val sub = if (note.isNotEmpty()) "[mcp] $note" else "[mcp] ${server.transportSummary}"
            SlashCommand(
                id = "mcp:${server.id}",
                icon = Icons.Outlined.Build,
                title = server.id,
                subtitle = sub,
                isMcp = true,
            )
        } ?: emptyList()
    val all = base + skillRows + mcpRows
    return if (filter.isEmpty()) all else all.filter { it.title.lowercase().contains(filter) }
}

/**
 * Update slash-menu state based on composer text. Call from the composer's
 * `onValueChange`. Mirrors iOS `updateSlashMenuState()`.
 */
internal fun ChatViewModel.updateSlashMenuState(text: String) {
    // [T-android-slash-menu-align-ios-prepend] Over-content mode: the
    // composer reads `/<typed-filter> <saved-original>` (see
    // showSlashMenuOverInput). The filter is the token between the leading
    // "/" and the first whitespace — everything after that is the user's
    // preserved original text and must NOT join the filter, or the menu
    // would search the whole `/<filter> <original>` string and never match.
    // Mirrors iOS updateSlashMenuState's over-input branch.
    if (savedInputBeforeSlash != null) {
        val starts = text.startsWith("/") || text.startsWith("／")
        if (!starts) {
            // The leading "/" was deleted — close the menu and leave
            // over-content mode WITHOUT restoring (the user is editing the
            // composer directly now). Drop the saved original so we don't
            // later re-strip a prefix that no longer exists.
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            return
        }
        val afterSlash = text.drop(1)
        val end = afterSlash.indexOfFirst { it.isWhitespace() }.let { if (it < 0) afterSlash.length else it }
        _slashFilter.value = afterSlash.substring(0, end)
        _showSlashMenu.value = true
        _slashMenuSelectedIndex.value = -1
        return
    }
    // Typed-"/" path (no saved original): the whole input is the slash
    // query. Accept full-width "／" (U+FF0F) too — see tryExecuteInputAsSlashCommand.
    val starts = text.startsWith("/") || text.startsWith("／")
    if (starts && !text.contains("\n") && text.length < 30) {
        _showSlashMenu.value = true
        _slashFilter.value = text.drop(1)
        _slashMenuSelectedIndex.value = -1
    } else {
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
    }
}

/**
 * "/" button tapped. If composer is empty, set input to "/"; otherwise save
 * and show the menu directly without clobbering existing input.
 * Returns the (possibly updated) input text.
 */
internal fun ChatViewModel.showSlashMenuOverInput(currentInput: String): String {
    val trimmed = currentInput.trim()
    // [T-android-slash-menu-align-ios-prepend] Boundary: the composer text
    // already starts with "/" (the user hand-typed a slash command). Don't
    // prepend another "/ " (which would make "/ /foo") — treat it exactly
    // like the typed-"/" path: open the menu over the existing text and let
    // updateSlashMenuState derive the filter from it. No saved original.
    if (currentInput.startsWith("/") || currentInput.startsWith("／")) {
        savedInputBeforeSlash = null
        updateSlashMenuState(currentInput)
        return currentInput
    }
    return if (trimmed.isEmpty()) {
        // Empty composer: behave like typing "/" — the input becomes the
        // slash query itself, so we're NOT "over content" (no saved text).
        savedInputBeforeSlash = null
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        "/"
    } else {
        // [T-android-slash-menu-align-ios-prepend] iOS parity: PREPEND "/ "
        // so the composer reads `/ <original>`, save the original for
        // dismiss/skill-prepend, and land the caret right after the slash
        // (index 1) so the user types into the menu's filter slot —
        // identical to having typed "/" at the start themselves.
        savedInputBeforeSlash = currentInput
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        _pendingCaret.value = 1
        "/ $currentInput"
    }
}

/**
 * Dismiss the slash menu. Returns the text the composer should restore to —
 * either the previously-saved text (if opened via button over content) or
 * an empty string (if opened by typing "/").
 */
internal fun ChatViewModel.dismissSlashMenu(currentInput: String): String {
    // [T-android-slash-menu-align-ios-prepend] iOS parity:
    // - Over-content (saved != null): restore the saved ORIGINAL, stripping
    //   the "/ " prefix showSlashMenuOverInput injected. We restore `saved`,
    //   NOT the live `currentInput` (which is "/ <original>" possibly with a
    //   filter token), so the prefix never lingers and the body is intact.
    // - Typed-"/" (saved == null): the input was the slash query → clear it.
    val saved = savedInputBeforeSlash
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return if (currentInput.startsWith("/") || currentInput.startsWith("／")) "" else currentInput
}

/**
 * [T-android-slash-send-keeps-text] End the slash session because the composer
 * was SENT.
 *
 * Unlike [dismissSlashMenu] this restores nothing and returns nothing: the body
 * text has just been sent as a message, so the stashed original must be dropped
 * rather than written back into the (now cleared) composer. Without this, the
 * "/" button's over-content mode survived the send and the just-sent text
 * reappeared in the input — reported on Android only.
 *
 * Safe to call unconditionally: when no slash session is open every assignment
 * below is already its own value.
 */
internal fun ChatViewModel.endSlashSessionForSend() {
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    _slashFilter.value = ""
}

internal fun ChatViewModel.slashMenuSetSelectedIndex(index: Int) {
    _slashMenuSelectedIndex.value = index
}

/**
 * [T-slash-skill-passthrough] True when [name] (lowercased, leading slash
 * already stripped) is an installed skill's handle. Skill rows only fill the
 * composer, so the send path has to let the text reach runAgentLoop for the
 * SKILL.md fragment injection — otherwise the literal "/<skill>" was
 * swallowed by the unknown-command branch and a hand-typed skill could never
 * run (the /lemon-tools dead end).
 */
internal fun ChatViewModel.isKnownSkillSlash(name: String): Boolean {
    if (name.isBlank()) return false
    val skills = runCatching { skillRepository?.skills?.value }.getOrNull() ?: return false
    return skills.any { it.name.equals(name, ignoreCase = true) }
}

/**
 * [T-slash-mcp-fill] True when [name] addresses an MCP server, either by the
 * row's own handle ("/<server-id>") or the explicit "/mcp:<server-id>" form.
 * Those rows are composer fillers as well: discovery and calls happen
 * model-side through minis-mcp-cli.
 */
internal fun ChatViewModel.isKnownMcpSlash(name: String): Boolean {
    if (name.isBlank()) return false
    if (name.startsWith("mcp:")) return true
    val servers = runCatching { mcpRepository?.servers?.value }.getOrNull() ?: return false
    return servers.any { it.id.equals(name, ignoreCase = true) }
}

/** [T-slash-router] 路由本体从 ChatViewModel 迁出，守住架构策略的文件行数上限。 */
internal fun ChatViewModel.tryExecuteInputAsSlashCommandImpl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val body = trimmed.drop(1)
        if (body.equals("goal", ignoreCase = true) || body.startsWith("goal ", ignoreCase = true)) {
            viewModelScope.launch { executeGoalCommand(body.removeRange(0, 4).trim()) }
            return true
        }
        // [T-slash-router] Only the first whitespace-delimited token can be a
        // command name. "/skill args", MCP rows and filesystem paths must fall
        // through to the model instead of being answered with "未知命令".
        val tokens = body.trim().split(Regex("\\s+"), limit = 2)
        val name = tokens[0].lowercase()
        val args = tokens.getOrElse(1) { "" }.trim()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
        if (cmd != null) {
            executeSlashCommand(cmd)
            return true
        }
        // [T-slash-skill-passthrough] A skill row fills "/<skill-name> " into
        // the composer (executeSlashCommand → isSkill), so the very next Send
        // landed here and was swallowed as an unknown command: skills could
        // only ever be triggered from the / panel, never by hand. Let the text
        // through so runAgentLoop injects the SKILL.md fragment.
        if (isKnownSkillSlash(name)) return false
        // Same for MCP rows ("/<server-id>" / "/mcp:<server-id>") — discovery
        // and calls happen model-side through minis-mcp-cli.
        if (isKnownMcpSlash(name)) return false
        // A path (any further "/" in the body) is content, not a command:
        // "/data/local/tmp 看一下" used to be eaten by the notice below.
        if (body.contains('/')) return false
        // [T-android-slash-literal-not-chat] A bare "/" or an unmatched
        // "/xyz" is a slash-panel interaction, not a chat message. Sending
        // it to the model made the assistant answer the literal string.
        // Swallow it with a local notice instead; the composer's slash
        // panel stays open so the user can pick a real command.
        appendSystemInfo(
            text = if (args.isEmpty()) {
                "未知命令: $trimmed（可用命令见 / 面板）"
            } else {
                "未知命令: /$name（可用命令见 / 面板）"
            },
            iconKind = "info",
        )
        return true
    }
