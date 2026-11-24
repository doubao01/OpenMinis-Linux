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
 * Resolve the cold-start deep link. On a configuration change the original
 * Intent is still attached; re-dispatching it would mount its destination on
 * top of the restored NavController stack, so it is deliberately ignored.
 */
internal fun resolveLaunchDeepLink(
    intent: Intent?,
    restoredChatSessionId: String?,
    isActivityRecreation: Boolean,
): DeepLinkAction {
    if (isActivityRecreation) return DeepLinkAction.Unknown
    val explicitDeepLink = when {
        intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND -> DeepLinkAction.NewChat
        else -> DeepLinkHandler.parse(intent?.data)
    }
    return if (explicitDeepLink !is DeepLinkAction.Unknown) {
        explicitDeepLink
    } else {
        restoredChatSessionId?.let { DeepLinkAction.OpenSession(it) } ?: DeepLinkAction.Unknown
    }
}
