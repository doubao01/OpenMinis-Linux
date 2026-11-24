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
    fun recreationDoesNotSynthesizeAChatRestore() {
        assertEquals(
            DeepLinkAction.Unknown,
            resolveLaunchDeepLink(null, restoredChatSessionId = "chat-1", isActivityRecreation = true),
        )
    }

    @Test
    fun recreationKeepsAnExplicitDeepLinkForTheGraphStart() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://action/new_chat"))
        assertEquals(
            DeepLinkAction.NewChat,
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

    @Test
    fun appNavigationReceivesTheRecreationFlag() {
        val main = ProductionSources.read("MainActivity.kt")
        assertTrue(main.contains("skipInitialRouting = isActivityRecreation"))

        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(nav.contains("skipInitialRouting: Boolean = false"))
        assertTrue(
            nav.substringAfter("LaunchedEffect(initialDeepLink) {")
                .substringBefore("when (initialDeepLink)")
                .contains("if (skipInitialRouting) return@LaunchedEffect"),
        )
        assertTrue(
            nav.substringAfter("LaunchedEffect(Unit) {")
                .substringBefore("val hasDeepLink")
                .contains("if (skipInitialRouting) return@LaunchedEffect"),
        )
    }
}
