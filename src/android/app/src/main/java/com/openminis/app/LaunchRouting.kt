package com.openminis.app

import android.content.Intent
import android.os.Bundle
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler

internal const val KEY_CURRENT_CHAT_SESSION_ID = "minis.current_chat_session_id"
internal const val KEY_ACTIVITY_RECREATION = "minis.activity_recreation"

internal data class ActivityRestore(
    val isRecreation: Boolean,
    val chatSessionId: String?,
)

/**
 * [T-android-config-change-chat-restore] A configuration change recreates the
 * Activity and restores the NavController stack; a process death does not.
 * Only the latter should re-open the saved chat, otherwise changing the
 * language in Settings jumps back into the last conversation.
 */
internal fun readActivityRestore(savedInstanceState: Bundle?, forceHome: Boolean): ActivityRestore {
    val isRecreation = savedInstanceState?.getBoolean(KEY_ACTIVITY_RECREATION, false) == true
    val chatSessionId = if (isRecreation) {
        null
    } else {
        savedInstanceState?.getString(KEY_CURRENT_CHAT_SESSION_ID)
            ?.takeUnless { forceHome }
    }
    return ActivityRestore(isRecreation, chatSessionId)
}

/**
 * Resolve the cold-start deep link. An explicit Intent deep link is kept on a
 * configuration change because it also defines the NavHost start destination;
 * the caller skips its dispatch effect so it cannot mount a second copy. A
 * saved chat id, by contrast, must not be turned into a deep link on
 * recreation — that is what replaced Settings with the last conversation.
 * Every action returned here still needs a branch in AppNavigation's
 * cold-start `when` and in MainActivity.handleDeepLink.
 */
internal fun resolveLaunchDeepLink(
    intent: Intent?,
    restoredChatSessionId: String?,
    isActivityRecreation: Boolean,
): DeepLinkAction {
    val explicitDeepLink = when {
        intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND -> DeepLinkAction.NewChat
        else -> DeepLinkHandler.parse(intent?.data)
    }
    if (explicitDeepLink !is DeepLinkAction.Unknown) return explicitDeepLink
    if (isActivityRecreation) return DeepLinkAction.Unknown
    return restoredChatSessionId?.let { DeepLinkAction.OpenSession(it) } ?: DeepLinkAction.Unknown
}
