package com.openminis.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.openminis.app.deeplink.DeepLinkAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LaunchRoutingTest {

    @Test
    fun recreationSkipsChatRestore() {
        val saved = Bundle().apply {
            putBoolean(KEY_ACTIVITY_RECREATION, true)
            putString(KEY_CURRENT_CHAT_SESSION_ID, "chat-1")
        }
        val restore = readActivityRestore(saved, forceHome = false)
        assertTrue(restore.isRecreation)
        assertNull(restore.chatSessionId)
    }

    @Test
    fun processDeathStillRestoresChat() {
        val saved = Bundle().apply {
            putBoolean(KEY_ACTIVITY_RECREATION, false)
            putString(KEY_CURRENT_CHAT_SESSION_ID, "chat-1")
        }
        val restore = readActivityRestore(saved, forceHome = false)
        assertFalse(restore.isRecreation)
        assertEquals("chat-1", restore.chatSessionId)
    }

    @Test
    fun forceHomeWinsOverChatRestore() {
        val saved = Bundle().apply {
            putString(KEY_CURRENT_CHAT_SESSION_ID, "chat-1")
        }
        val restore = readActivityRestore(saved, forceHome = true)
        assertNull(restore.chatSessionId)
    }

    @Test
    fun recreationDoesNotRedispatchTheLaunchIntent() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://action/new_chat"))
        assertEquals(
            DeepLinkAction.Unknown,
            resolveLaunchDeepLink(intent, restoredChatSessionId = "chat-1", isActivityRecreation = true),
        )
    }

    @Test
    fun coldStartPrefersAnExplicitDeepLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://action/new_chat"))
        assertEquals(
            DeepLinkAction.NewChat,
            resolveLaunchDeepLink(intent, restoredChatSessionId = "chat-1", isActivityRecreation = false),
        )
    }

    @Test
    fun coldStartFallsBackToTheRestoredChat() {
        assertEquals(
            DeepLinkAction.OpenSession("chat-1"),
            resolveLaunchDeepLink(null, restoredChatSessionId = "chat-1", isActivityRecreation = false),
        )
    }
}
