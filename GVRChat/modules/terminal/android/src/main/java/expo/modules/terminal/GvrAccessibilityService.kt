package expo.modules.terminal

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets the agent read the visible screen and tap, type or go back.
 * It acts only inside apps the user allowed (screen_allow) and never inside
 * banking or payment apps.
 */
class GvrAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GvrAccessibilityService? = null
        @Volatile var allowed: Set<String> = emptySet()
        private val BANNED_HINTS = listOf("bank", "pay", "wallet", "fawry", "instapay", "cash", "finance", "binance", "paypal", "nbe", "cib", "ahly")
        private const val MAX_NODES = 300
    }

    private val nodes = mutableListOf<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun gate(pkg: String?): String? {
        if (pkg.isNullOrEmpty()) return "NO_APP"
        val p = pkg.lowercase()
        if (BANNED_HINTS.any { p.contains(it) }) {
            return "BLOCKED: banking and payment apps are never read or controlled ($pkg)"
        }
        if (!allowed.contains(pkg)) {
            return "BLOCKED: $pkg is not in the allowed apps list. Ask the user to allow it with screen_allow."
        }
        return null
    }

    fun dump(): String {
        val root = rootInActiveWindow ?: return "NO_WINDOW"
        gate(root.packageName?.toString())?.let { return it }
        nodes.clear()
        val out = StringBuilder("app: ${root.packageName}\n")
        walk(root, out, 0)
        if (nodes.isEmpty()) {
            out.append("(no readable elements; this screen may hide its content from accessibility)\n")
        }
        return out.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (n == null || nodes.size >= MAX_NODES || depth > 40) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val label = if (text.isNotEmpty()) text else desc
        val editable = n.className?.toString() == "android.widget.EditText" || n.isEditable
        if (label.isNotEmpty() || n.isClickable || editable) {
            nodes.add(n)
            val kind = when {
                editable -> "input"
                n.isClickable -> "button"
                else -> "text"
            }
            out.append("[${nodes.size}] $kind")
            if (label.isNotEmpty()) out.append(" \"").append(label.take(120)).append("\"")
            out.append('\n')
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    fun tap(index: Int): String {
        val root = rootInActiveWindow ?: return "NO_WINDOW"
        gate(root.packageName?.toString())?.let { return it }
        val n = nodes.getOrNull(index - 1) ?: return "No element [$index]. Call screen_read first."
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && hops < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped [$index]"
            c = c.parent
            hops++
        }
        return "element [$index] could not be tapped"
    }

    fun type(index: Int, text: String): String {
        val root = rootInActiveWindow ?: return "NO_WINDOW"
        gate(root.packageName?.toString())?.let { return it }
        val n = nodes.getOrNull(index - 1) ?: return "No element [$index]. Call screen_read first."
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed into [$index]" else "could not type into [$index]"
    }

    fun back(): String {
        val root = rootInActiveWindow ?: return "NO_WINDOW"
        gate(root.packageName?.toString())?.let { return it }
        return if (performGlobalAction(GLOBAL_ACTION_BACK)) "went back" else "back failed"
    }
}
