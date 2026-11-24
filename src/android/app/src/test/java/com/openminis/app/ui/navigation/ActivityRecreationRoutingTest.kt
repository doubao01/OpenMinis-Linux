package com.openminis.app.ui.navigation

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-config-change-chat-restore] Configuration changes (language,
 * font scale, …) recreate MainActivity. The NavController restores the visible
 * screen, but two older paths treated the same savedInstanceState as a
 * process-death restart:
 *
 *  1. MainActivity synthesised an OpenSession deep-link from the last chat id.
 *  2. AppNavigation re-ran its launch-session preference and auto-navigated to
 *     the newest chat.
 *
 * Either one replaces Settings/Appearance with a conversation when the user
 * changes the language there. These guards pin the separation: a real process
 * death still restores the chat, while an Activity recreation leaves the
 * restored NavController stack alone.
 */
class ActivityRecreationRoutingTest {

    @Test
    fun saveInstanceStateRecordsWhetherTheActivityIsBeingRecreated() {
        val source = ProductionSources.read("MainActivity.kt")
        assertTrue(
            "onSaveInstanceState must persist isChangingConfigurations; otherwise " +
                "onCreate cannot distinguish a language change from process death",
            source.contains("outState.putBoolean(KEY_ACTIVITY_RECREATION, isChangingConfigurations)"),
        )
    }

    @Test
    fun chatRestoreIsSkippedWhenTheActivityIsRecreated() {
        val source = ProductionSources.read("MainActivity.kt")
        val start = source.indexOf("val isActivityRecreation")
        assertTrue("isActivityRecreation decision must exist", start >= 0)
        val window = source.substring(start, (start + 900).coerceAtMost(source.length))
        assertTrue(
            "restoredChatSessionId must be null on Activity recreation — reading the saved " +
                "chat id here is what replaced Settings with the last conversation",
            window.contains("restoredChatSessionId = if (isActivityRecreation)") &&
                window.contains("null"),
        )
        assertTrue(
            "AppNavigation must receive the recreation flag",
            source.contains("skipInitialRouting = isActivityRecreation"),
        )
    }

    @Test
    fun appNavigationDoesNotReplayLaunchRoutingOnRecreation() {
        val source = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(
            "AppNavigation must declare skipInitialRouting",
            source.contains("skipInitialRouting: Boolean = false"),
        )
        val deepLinkEffect = source
            .substringAfter("LaunchedEffect(initialDeepLink) {")
            .substringBefore("when (initialDeepLink)")
        assertTrue(
            "the initial deep-link effect must not re-dispatch on recreation",
            deepLinkEffect.contains("if (skipInitialRouting) return@LaunchedEffect"),
        )
        val launchEffect = source
            .substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("val hasDeepLink")
        assertTrue(
            "the launch-session effect must not auto-open the newest chat on recreation",
            launchEffect.contains("if (skipInitialRouting) return@LaunchedEffect"),
        )
    }
}
