package com.openminis.app.ui.lemon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-lemon-integration] The panel is a packaged asset, not Kotlin, so the
 * compiler cannot see that its fetch must carry the built-in server's Bearer
 * token. Without this header every catalog load is a 401 and the UI reports
 * "not connected" even though the server is running.
 */
class LemonPanelContractTest {

    @Test
    fun panelReadsTheTokenAndSendsItAsABearerHeader() {
        val html = asset("lemon/panel.html")
        assertTrue(
            "panel must read the token from its launch URL",
            html.contains("const TOKEN = new URLSearchParams(location.search).get('token')"),
        )
        assertTrue(
            "panel must attach the token to every JSON-RPC request",
            html.contains("headers['Authorization'] = 'Bearer ' + TOKEN"),
        )
    }

    private fun asset(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir missing"))
        repeat(8) {
            val candidate = File(dir, "src/main/assets/$relative")
            if (candidate.isFile) return candidate.readText()
            val viaModule = File(dir, "src/android/app/src/main/assets/$relative")
            if (viaModule.isFile) return viaModule.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("missing asset $relative from ${File("").absolutePath}")
    }
}
