package com.omega.keyboard.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.ai.Translator
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.ScreenFilter
import com.omega.keyboard.ime.OmegaImeService
import com.omega.keyboard.ime.Themes

/**
 * Whole-screen translation: the keyboard hides first (so nothing is covered), every text on screen is read through
 * the accessibility service, translated, and painted over the original. It follows scrolling and screen changes.
 */
object ScreenTranslateController {
    @Volatile var active = false
        private set

    @Volatile private var generation = 0
    @Volatile private var busy = false
    @Volatile private var lastSignature = ""
    @Volatile private var lastRunAt = 0L
    private var appCtx: Context? = null
    private var refreshTask: Runnable? = null
    private val quickTargets = listOf("ar", "en", "fr", "es", "de", "tr", "ru", "zh")

    fun toggle(ctx: Context) { if (active) stop() else start(ctx) }

    fun start(ctx: Context) {
        val svc = ScreenAgentService.instance
        if (svc == null) { Notice.needAccessibility(ctx); return }
        if (AutoPilot.running) { Notice.toast(ctx, "وقّف الوضع الذاتي الأول وبعدين ترجم الشاشة"); return }
        if (active) { refreshNow(); return }
        appCtx = ctx.applicationContext
        active = true
        generation++
        lastSignature = ""
        val target = Prefs.get(ctx).screenTarget
        Overlays.withBar(svc) { bar ->
            bar.setTitle("ترجمة الشاشة ← ${Languages.arabicName(target)}")
            bar.setStatus("بجهّز الشاشة...")
            bar.setActions(
                listOf(
                    ControlBar.Action("lang", "language"),
                    ControlBar.Action("refresh", "refresh"),
                    ControlBar.Action("close", "close", color = 0xFFD93025.toInt()),
                )
            )
            bar.onAction = { id ->
                when (id) {
                    "close" -> stop()
                    "refresh" -> refreshNow()
                    "lang" -> nextLanguage()
                }
            }
        }
        OmegaImeService.instance?.hideKeyboard()
        Bg.io { runOnce(force = true) }
    }

    fun stop() {
        if (!active && !Overlays.barVisible) return
        active = false
        generation++
        refreshTask?.let { Bg.main.removeCallbacks(it) }
        refreshTask = null
        Llm.cancel()
        if (!AutoPilot.running) Overlays.hideAll() else Overlays.hideLayer()
    }

    fun refreshNow() {
        if (!active) return
        Bg.io { runOnce(force = true) }
    }

    private fun nextLanguage() {
        val ctx = appCtx ?: return
        val prefs = Prefs.get(ctx)
        val i = quickTargets.indexOf(prefs.screenTarget)
        prefs.screenTarget = quickTargets[(i + 1) % quickTargets.size]
        Translator.clearCache()
        val svc = ScreenAgentService.instance ?: return
        Overlays.withBar(svc) { it.setTitle("ترجمة الشاشة ← ${Languages.arabicName(prefs.screenTarget)}") }
        refreshNow()
    }

    /** Called for every scroll / window / content change event of other apps. */
    fun onScreenEvent(type: Int) {
        if (!active) return
        val svc = ScreenAgentService.instance ?: return
        val scrolled = type == AccessibilityEvent.TYPE_VIEW_SCROLLED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (scrolled) Overlays.withLayer(svc) { it.clear() }          // boxes no longer sit on their text
        val delay = if (scrolled) 800L else 1800L
        Bg.ui {
            refreshTask?.let { Bg.main.removeCallbacks(it) }
            val r = Runnable { Bg.io { runOnce(force = false) } }
            refreshTask = r
            Bg.main.postDelayed(r, delay)
        }
    }

    private fun setStatus(s: String, warn: Boolean = false) {
        val svc = ScreenAgentService.instance ?: return
        Overlays.withBar(svc) { it.setStatus(s, warn) }
    }

    private fun runOnce(force: Boolean) {
        if (!active) return
        if (busy) return
        if (!force && System.currentTimeMillis() - lastRunAt < 1200) return
        val svc = ScreenAgentService.instance ?: return
        val ctx = appCtx ?: svc
        busy = true
        val gen = ++generation
        try {
            val prefs = Prefs.get(ctx)
            val target = prefs.screenTarget
            // the whole app must be on screen: hide the keyboard (any keyboard) and let the app take its room back
            if (!Keyboards.ensureHidden(svc)) setStatus("الكيبورد مش راضي يختفي، هقرا اللي ظاهر", warn = true)
            else if (force) setStatus("بقرا الشاشة...")
            val snap = ScreenReader.read(svc)
            Keyboards.release(svc)
            if (snap == null) { setStatus("مش قادر أقرا الشاشة دي", warn = true); return }
            val items = ScreenFilter.translatable(snap.screen, target)
            snap.release()
            if (items.isEmpty()) {
                Overlays.withLayer(svc) { it.clear() }
                setStatus("مفيش نص محتاج ترجمة هنا")
                lastSignature = ""
                return
            }
            val sig = items.joinToString("|") { "${it.text}@${it.node.left / 8},${it.node.top / 8}" }.hashCode().toString()
            if (!force && sig == lastSignature) return
            lastSignature = sig
            lastRunAt = System.currentTimeMillis()

            // colours behind the text (so a translation looks like it was always there)
            var shot: Bitmap? = null
            if (Build.VERSION.SDK_INT >= 30) {
                Overlays.withLayer(svc) { it.clear() }
                Thread.sleep(90)
                shot = Shot.take(svc)
            }
            if (gen != generation || !active) { shot?.recycle(); return }
            val night = Themes.isNight(ctx)
            val dm = svc.resources.displayMetrics
            val boxes = items.map {
                val r = Rect(
                    it.node.left.coerceIn(0, dm.widthPixels), it.node.top.coerceIn(0, dm.heightPixels),
                    it.node.right.coerceIn(0, dm.widthPixels), it.node.bottom.coerceIn(0, dm.heightPixels)
                )
                val bg = (if (shot != null && r.width() > 4 && r.height() > 4) Shot.backgroundAround(shot, r)
                else if (night) 0xFF1E1F22.toInt() else 0xFFF8F9FA.toInt()) or 0xFF000000.toInt()
                val fg = if (Shot.luminance(bg) > 0.55) 0xFF111111.toInt() else 0xFFF5F5F5.toInt()
                TranslationLayer.Box(r, bg, fg)
            }
            shot?.recycle()
            Overlays.withLayer(svc) { it.setBoxes(boxes) }
            setStatus("بترجم ${items.size} نص...")

            val res = Translator.translateBatch(
                ctx, items.map { it.text }, target,
                cancelled = { gen != generation || !active },
                onItem = { i, tr -> Overlays.withLayer(svc) { it.put(i, tr) } },
                onProgress = { d, t, _ -> if (gen == generation) setStatus("بترجم $d / $t") },
            )
            if (gen != generation || !active) return
            val note = res.notes.firstOrNull()
            when {
                res.translated == 0 && res.failed > 0 -> setStatus(note ?: "مقدرتش أترجم", warn = true)
                res.failed > 0 -> setStatus("اتترجم ${res.translated}، و${res.failed} مقدرتش" + (note?.let { " ($it)" } ?: ""), warn = true)
                else -> setStatus("اتترجم ${res.translated} نص. مرّر الشاشة وهكمّل لوحدي.")
            }
        } catch (t: Throwable) {
            AppLog.e("screen-translate", "failed", t)
            setStatus("حصلت مشكلة: " + (t.message ?: t.javaClass.simpleName).take(80), warn = true)
        } finally {
            busy = false
        }
    }
}
