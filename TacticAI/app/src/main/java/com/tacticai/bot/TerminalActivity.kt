package com.tacticai.bot

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * A terminal, without bundling a Linux distro: Android ships a real shell (toybox's `sh`, with ls, cat,
 * ps, grep, df, ping, ...) that any app can run via ProcessBuilder — sandboxed to this app's own UID,
 * same as the app itself. That covers on-device diagnostics (is the model file there, what is the engine
 * doing, how fast is it ticking) without needing a PC or ADB. A couple of pseudo-commands add things no
 * shell command can see from outside the app (the engine's live state).
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val scope = CoroutineScope(Dispatchers.Main)
    private val history = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        output = findViewById(R.id.termOutput)
        scroll = findViewById(R.id.termScroll)
        input = findViewById(R.id.termInput)
        val run = findViewById<Button>(R.id.termRun)
        findViewById<MaterialToolbar>(R.id.termToolbar).setNavigationOnClickListener { finish() }

        print("TacticAI shell. اكتب 'help' للأوامر الخاصة بالتطبيق. أي حاجة تانية بتتبعت لـ sh الجهاز.")
        run.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { submit(); true } else false
        }
    }

    private fun submit() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        input.text.clear()
        print("\$ $cmd")
        scope.launch { runCommand(cmd) }
    }

    private suspend fun runCommand(cmd: String) {
        when (cmd.lowercase()) {
            "help" -> {
                print(
                    "أوامر خاصة: status (حالة البوت الحية) · clear (مسح الشاشة)\n" +
                        "وأي أمر شل عادي: ls, cat, ps, df, pwd, grep, ping, echo ...\n" +
                        "مجلد العمل: ${filesDir.absolutePath}",
                )
            }
            "clear" -> { history.clear(); output.text = "" }
            "status" -> print(statusSnapshot())
            else -> print(execShell(cmd))
        }
    }

    private fun statusSnapshot(): String {
        val s = StatusBus.state.value
        val t = s.lastTick
        return buildString {
            appendLine("accessibility: ${if (s.accessibilityConnected) "متّصلة" else "غير متّصلة"}")
            appendLine("engine: ${if (s.running) "شغّال" else "واقف"}")
            if (t != null) {
                appendLine("آخر دورة #${t.tick}: ${t.detections} كشف، ${t.actionsTaken} فعل، ${t.tookMs}ms")
            } else {
                append("لسه مفيش دورة اتعملت")
            }
        }
    }

    /** Real Android shell, this app's own sandbox, 12s timeout, output capped so a runaway command can't flood the UI. */
    private suspend fun execShell(cmd: String): String = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(12_000) {
            try {
                val proc = ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .directory(filesDir)
                    .start()
                val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                proc.waitFor()
                if (text.length > MAX_OUTPUT) "${text.take(MAX_OUTPUT)}\n…[truncated]" else text.ifBlank { "(no output, exit ${proc.exitValue()})" }
            } catch (e: Exception) {
                "تعذّر التنفيذ: ${e.message}"
            }
        }
        result ?: "انتهت المهلة (12 ثانية) — الأمر اتوقف."
    }

    private fun print(line: String) {
        history.append(line).append('\n')
        output.text = history.toString()
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_OUTPUT = 20_000
    }
}
