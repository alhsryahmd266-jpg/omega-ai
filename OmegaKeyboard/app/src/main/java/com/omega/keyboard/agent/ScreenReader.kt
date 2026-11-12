package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.omega.keyboard.core.Screen
import com.omega.keyboard.core.UiNode

/** A read of the screen: the pure [Screen] model plus the live nodes behind it (index == UiNode.id) for performing actions. */
class Snapshot(val screen: Screen, private val live: List<AccessibilityNodeInfo>) {
    fun node(id: Int): AccessibilityNodeInfo? = live.getOrNull(id)

    fun release() {
        if (Build.VERSION.SDK_INT < 33) {
            for (n in live) try { @Suppress("DEPRECATION") n.recycle() } catch (_: Throwable) {}
        }
    }
}

object ScreenReader {
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 80

    private class Frame(val node: AccessibilityNodeInfo, val parentId: Int, val depth: Int)

    /**
     * Reads every visible app window (not the keyboard, not our own overlays, not the status bar).
     * Returns null if the system gives us nothing to read.
     */
    fun read(service: AccessibilityService): Snapshot? {
        val dm = service.resources.displayMetrics
        val roots = ArrayList<AccessibilityNodeInfo>()
        var activePkg = ""
        var bestArea = -1L
        try {
            for (w in service.windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == service.packageName || pkg == "com.android.systemui") { recycle(root); continue }
                val b = Rect()
                w.getBoundsInScreen(b)
                val area = b.width().toLong() * b.height().toLong()
                if (w.isActive || w.isFocused) { activePkg = pkg; bestArea = Long.MAX_VALUE } else if (area > bestArea) { activePkg = pkg; bestArea = area }
                roots.add(root)
            }
        } catch (_: Throwable) {
        }
        if (roots.isEmpty()) {
            val r = service.rootInActiveWindow ?: return null
            val pkg = r.packageName?.toString().orEmpty()
            if (pkg == service.packageName || pkg == "com.android.systemui") { recycle(r); return null }
            activePkg = pkg
            roots.add(r)
        }

        val live = ArrayList<AccessibilityNodeInfo>()
        val out = ArrayList<UiNode>()
        for (root in roots) {
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(root, -1, 0))
            while (stack.isNotEmpty() && out.size < MAX_NODES) {
                val f = stack.removeLast()
                val n = f.node
                val id = out.size
                val r = Rect()
                n.getBoundsInScreen(r)
                val clickable = n.isClickable
                val dir = if (r.height() >= dm.heightPixels * 0.25) scrollDirections(n) else 0
                out.add(
                    UiNode(
                        id = id,
                        parentId = f.parentId,
                        depth = f.depth,
                        text = n.text?.toString().orEmpty(),
                        desc = n.contentDescription?.toString().orEmpty(),
                        viewId = n.viewIdResourceName.orEmpty(),
                        cls = n.className?.toString().orEmpty(),
                        pkg = n.packageName?.toString().orEmpty(),
                        left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                        editable = n.isEditable,
                        clickable = clickable,
                        actionable = clickable || hasClick(n),
                        enabled = n.isEnabled,
                        visible = n.isVisibleToUser,
                        focused = n.isFocused,
                        password = n.isPassword,
                        showingHint = if (Build.VERSION.SDK_INT >= 26) n.isShowingHintText else false,
                        childCount = n.childCount,
                        scrollDown = dir and SCROLL_DOWN != 0,
                        scrollUp = dir and SCROLL_UP != 0,
                        hint = hintOf(n),
                    )
                )
                live.add(n)
                if (f.depth < MAX_DEPTH) {
                    for (i in n.childCount - 1 downTo 0) {
                        val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                        stack.addLast(Frame(c, id, f.depth + 1))
                    }
                }
            }
            // anything left on the stack was cut off by the node limit
            while (stack.isNotEmpty()) recycle(stack.removeLast().node)
        }
        if (out.isEmpty()) return null
        val rtl = service.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val screen = Screen(activePkg.ifEmpty { out.first().pkg }, dm.widthPixels, dm.heightPixels, dm.density, out, rtl)
        return Snapshot(screen, live)
    }

    /** The prompt of a text box ("Message"), only asked of editable nodes (Android 8+). */
    private fun hintOf(n: AccessibilityNodeInfo): String = try {
        if (Build.VERSION.SDK_INT >= 26 && n.isEditable) n.hintText?.toString().orEmpty() else ""
    } catch (_: Throwable) {
        ""
    }

    private const val SCROLL_DOWN = 1
    private const val SCROLL_UP = 2

    /**
     * Which way a view can still scroll, from the actions it offers. Lists only offer a direction while there is
     * content left in it, so "can scroll down" really means "there is more below what is on screen".
     * A view that only offers forward/backward (older RecyclerViews) counts as vertical unless it also offers left/right.
     */
    private fun scrollDirections(n: AccessibilityNodeInfo): Int = try {
        var down = false; var up = false; var fwd = false; var back = false; var sideways = false
        for (a in n.actionList) {
            when (a.id) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> down = true
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> up = true
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> sideways = true
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> fwd = true
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> back = true
            }
        }
        var r = 0
        if (down || (fwd && !sideways)) r = r or SCROLL_DOWN
        if (up || (back && !sideways)) r = r or SCROLL_UP
        r
    } catch (_: Throwable) {
        0
    }

    private fun hasClick(n: AccessibilityNodeInfo): Boolean = try {
        n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    } catch (_: Throwable) {
        false
    }

    private fun recycle(n: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) try { @Suppress("DEPRECATION") n.recycle() } catch (_: Throwable) {}
    }

    /** The app in front, even when its screen has nothing to read (a game draws its own interface). Empty when unknown. */
    fun foregroundPackage(service: AccessibilityService): String {
        var best = ""
        var bestArea = -1L
        try {
            for (w in service.windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                recycle(root)
                if (pkg.isEmpty() || pkg == service.packageName || pkg == "com.android.systemui") continue
                if (w.isActive || w.isFocused) return pkg
                val b = Rect()
                w.getBoundsInScreen(b)
                val area = b.width().toLong() * b.height().toLong()
                if (area > bestArea) { best = pkg; bestArea = area }
            }
        } catch (_: Throwable) {
        }
        if (best.isNotEmpty()) return best
        return try {
            val r = service.rootInActiveWindow
            val pkg = r?.packageName?.toString().orEmpty()
            if (r != null) recycle(r)
            if (pkg == service.packageName || pkg == "com.android.systemui") "" else pkg
        } catch (_: Throwable) {
            ""
        }
    }

    /** True while a keyboard window is on screen. */
    fun keyboardVisible(service: AccessibilityService): Boolean = try {
        val minH = (80 * service.resources.displayMetrics.density).toInt()
        service.windows.any {
            if (it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false else {
                val b = Rect(); it.getBoundsInScreen(b); b.height() > minH
            }
        }
    } catch (_: Throwable) {
        false
    }

    /** Waits (up to [timeoutMs]) for the keyboard to disappear, so the whole app is visible. */
    fun waitKeyboardGone(service: AccessibilityService, timeoutMs: Long = 2500): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!keyboardVisible(service)) return true
            try { Thread.sleep(80) } catch (_: InterruptedException) { return false }
        }
        return !keyboardVisible(service)
    }
}
