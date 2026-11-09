package com.omega.keyboard.core

/**
 * After a picture was handed to a chat app, most apps open a preview with a round "send" button. This finds that button.
 * Only buttons that call themselves "send" (by id or label, in many languages) qualify: guessing could press something else.
 */
object PreviewSendFinder {

    fun find(screen: Screen): UiNode? {
        var best: UiNode? = null
        var bestScore = 0
        for (n in screen.nodes) {
            if (!n.visible || !n.enabled || !n.actionable) continue
            if (n.width < screen.dp(24) || n.height < screen.dp(24) || n.width > screen.dp(200) || n.height > screen.dp(200)) continue
            val s = SendFinder.nodeScore(n)
            if (s < 80) continue
            // several matches (the preview and the chat behind it): the lowest one on the screen is the preview's own button
            val score = s * 10_000 + n.centerY.coerceIn(0, 9_999)
            if (score > bestScore) { bestScore = score; best = n }
        }
        return best
    }
}
