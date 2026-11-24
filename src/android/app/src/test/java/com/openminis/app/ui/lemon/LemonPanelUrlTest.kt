package com.openminis.app.ui.lemon

import org.junit.Assert.assertEquals
import org.junit.Test

class LemonPanelUrlTest {

    @Test
    fun omitsTheTokenWhenAuthIsOff() {
        assertEquals(
            "file:///android_asset/lemon/panel.html?port=8765",
            lemonPanelUrl(8765, null),
        )
    }

    @Test
    fun carriesTheLoopbackTokenWhenConfigured() {
        assertEquals(
            "file:///android_asset/lemon/panel.html?port=8765&token=abc123",
            lemonPanelUrl(8765, "abc123"),
        )
    }

    @Test
    fun omitsABlankToken() {
        assertEquals(
            "file:///android_asset/lemon/panel.html?port=9000",
            lemonPanelUrl(9000, "  "),
        )
    }
}
