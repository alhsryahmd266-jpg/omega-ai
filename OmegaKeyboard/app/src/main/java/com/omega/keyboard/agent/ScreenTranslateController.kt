package com.omega.keyboard.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
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
 * Whole-screen translation, by hand: one press and the keyboard hides completely (and stays away, so nothing is covered), every
 * text on the screen is read through the accessibility service, its language is recognised, and it is translated into Arabic and
 * painted over the original. The translation stays exactly where it is until the user drags the page: then it goes away and the
 * keyboard comes back. Nothing translates on its own; pressing the button again translates what is on screen then.
 */
object ScreenTranslateController {
    @Volatile var active = false
        private set

    @Volatile private var generation = 0
    @Volatile private var busy = false
    @Volatile private var painted = false
    @Volatile private var paintedAt = 0L
    /** events before this moment come from our own doing (hiding the keyboard, painting), not from the user */
    @Volatile private var quietUntil = 0L
    /** the page moved after it was read: whatever gets translated now would sit in the wrong place */
    @Volatile private var moved = false
    /** a keyboard was on the screen when the button was pressed: it comes back when the translation goes away */
    @Volatile private var keyboardWasUp = false
    @Volatile private var checking = false
    @Volatile private var lastCheckAt = 0L
    private class Shown(val text: String, val rect: Rect)
    @Volatile private var shown: List<Shown> = emptyList()
    /** what this session translates into: the Settings value (Arabic unless it was changed there); the bar's language button changes it for this session only */
    @Volatile private var sessionTarget = "ar"
    private var appCtx: Context? = null
    private val quickTargets = listOf("ar", "en", "fr", "es", "de", "tr", "ru", "zh")
    private const val QUIET_MS = 2600L        // after pressing the button: the keyboard going away moves the page by itself
    private const val SETTLE_MS = 1500L       // after a box is painted: the app settling is not the user moving on

    fun toggle(ctx: Context) { if (active) stop() else start(ctx) }

    private fun actions() = listOf(
        ControlBar.Action("refresh", "refresh", "ترجم", 0xFF1A73E8.toInt()),
        ControlBar.Action("lang", "language", "اللغة"),
        ControlBar.Action("close", "close", "إغلاق", 0xFFD93025.toInt()),
    )

    fun start(ctx: Context) {
        val svc = ScreenAgentService.instance
        if (svc == null) { Notice.needAccessibility(ctx); return }
        if (AutoPilot.running) { Notice.toast(ctx, "وقّف الوضع الذاتي الأول وبعدين ترجم الشاشة"); return }
        if (active) { translateNow(); return }
        appCtx = ctx.applicationContext
        keyboardWasUp = ScreenReader.keyboardVisible(svc)
        active = true
        generation++
        painted = false
        moved = false
        shown = emptyList()
        quietUntil = System.currentTimeMillis() + QUIET_MS
        sessionTarget = Prefs.get(ctx).screenTarget.ifBlank { "ar" }
        Overlays.withBar(svc) { bar ->
            bar.setTitle("ترجمة الشاشة ← ${Languages.arabicName(sessionTarget)}")
            bar.setStatus("بجهّز الشاشة...")
            bar.setActions(actions())
            bar.onAction = { id ->
                when (id) {
                    "close" -> stop()
                    "refresh" -> translateNow()
                    "lang" -> nextLanguage()
                }
            }
        }
        // the keyboard goes away completely and stays away (every keyboard, not only ours) while the translation is on screen
        Keyboards.setMode(svc, true)
        OmegaImeService.instance?.hideKeyboard()
        Bg.io { runOnce() }
    }

    fun stop() {
        if (!active && !Overlays.barVisible) return
        val was = active
        active = false
        painted = false
        moved = false
        shown = emptyList()
        generation++
        Llm.cancel()
        if (!AutoPilot.running) Overlays.hideAll() else Overlays.hideLayer()
        if (was) giveKeyboardBack()
    }

    /** The translation is over: keyboards may show again, and ours comes back when it was on the screen before. */
    private fun giveKeyboardBack() {
        val svc = ScreenAgentService.instance
        if (svc != null) Keyboards.release(svc)
        val wasUp = keyboardWasUp
        keyboardWasUp = false
        if (!wasUp) return
        // the system needs a moment to take the "no keyboard" mode off before the keyboard is asked to show again
        Bg.uiDelayed(350) { if (!active && !AutoPilot.running) OmegaImeService.instance?.showKeyboard() }
    }

    /** Translates what is on the screen now (the button on the bar, or the keyboard's button pressed again). */
    fun translateNow() {
        if (!active) return
        if (busy) { setStatus("لسه بترجم، ثواني..."); return }
        Bg.io { runOnce() }
    }

    private fun nextLanguage() {
        val i = quickTargets.indexOf(sessionTarget)
        sessionTarget = quickTargets[(i + 1) % quickTargets.size]
        val svc = ScreenAgentService.instance ?: return
        Overlays.withBar(svc) { it.setTitle("ترجمة الشاشة ← ${Languages.arabicName(sessionTarget)}") }
        translateNow()
    }

    /**
     * The user dragged the page (or the app opened something new): the painted boxes no longer sit on their text, so the
     * translation goes away and the keyboard comes back. Called for every accessibility event of other apps.
     */
    fun onScreenEvent(type: Int) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now < quietUntil || now - paintedAt < SETTLE_MS) return
        when (type) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> pageMoved()
            // apps that do not announce their scrolling still change their content: look whether the translated texts moved
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (painted) scheduleCheck()
        }
    }

    private fun pageMoved() {
        if (busy && !painted) { moved = true; return }      // still reading / translating: the first result will be dropped
        stop()
    }

    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        if (checking || now - lastCheckAt < 1200) return
        checking = true
        lastCheckAt = now
        Bg.io {
            try {
                Thread.sleep(450)
                if (!active || !painted) return@io
                val svc = ScreenAgentService.instance ?: return@io
                val list = shown
                if (list.isEmpty()) return@io
                val snap = ScreenReader.read(svc) ?: return@io
                val nodes = snap.screen.nodes
                snap.release()
                val tol = (8 * svc.resources.displayMetrics.density).toInt()
                var gone = 0
                for (sh in list) {
                    val still = nodes.any { n ->
                        n.visible && abs(n.top - sh.rect.top) <= tol && abs(n.left - sh.rect.left) <= tol &&
                            (n.text.trim() == sh.text || n.desc.trim() == sh.text)
                    }
                    if (!still) gone++
                }
                if (active && gone * 3 >= list.size) stop()      // a third of what was translated is no longer where it was
            } finally {
                lastCheckAt = System.currentTimeMillis()
                checking = false
            }
        }
    }

    private fun setStatus(s: String, warn: Boolean = false) {
        val svc = ScreenAgentService.instance ?: return
        Overlays.withBar(svc) { it.setStatus(s, warn) }
    }

    private fun runOnce() {
        if (!active) return
        if (busy) return
        val svc = ScreenAgentService.instance ?: return
        val ctx = appCtx ?: svc
        busy = true
        val gen = ++generation
        try {
            val target = sessionTarget
            // the whole app must be on screen: hide the keyboard (any keyboard) and let the app take its room back
            if (!Keyboards.ensureHidden(svc)) setStatus("الكيبورد مش راضي يختفي، هقرا اللي ظاهر", warn = true)
            else setStatus("بقرا الشاشة...")
            painted = false
            Overlays.withLayer(svc) { it.clear() }
            val snap = ScreenReader.read(svc)
            if (snap == null) { setStatus("مش قادر أقرا الشاشة دي. افتح صفحة فيها كلام وجرّب تاني.", warn = true); return }
            val items = ScreenFilter.translatable(snap.screen, target)
            snap.release()
            moved = false
            shown = items.map { Shown(it.text, Rect(it.node.left, it.node.top, it.node.right, it.node.bottom)) }
            if (items.isEmpty()) {
                setStatus("مفيش على الشاشة نص بلغة تانية محتاج ترجمة (أو الكلام صورة مش نص).")
                return
            }
            if (gen != generation || !active) return

            // colours behind the text (so a translation looks like it was always there)
            var shot: Bitmap? = null
            if (Build.VERSION.SDK_INT >= 30) {
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
            setStatus("لقيت ${items.size} نص، بترجم...")

            val res = Translator.translateBatch(
                ctx, items.map { it.text }, target,
                cancelled = { gen != generation || !active },
                onItem = { i, tr ->
                    if (moved) {
                        stop()                      // the page was dragged while this was being translated: drop it
                    } else {
                        painted = true
                        paintedAt = System.currentTimeMillis()
                        Overlays.withLayer(svc) { it.put(i, tr) }
                    }
                },
                onProgress = { d, t, _ -> if (gen == generation) setStatus("بترجم $d / $t") },
                onStage = { if (gen == generation) setStatus(it) },
            )
            if (gen != generation || !active) return
            paintedAt = System.currentTimeMillis()
            val note = res.notes.firstOrNull()
            when {
                res.translated == 0 && res.failed > 0 -> setStatus(note ?: "مقدرتش أترجم الشاشة دي. جرّب تاني.", warn = true)
                res.translated == 0 -> setStatus("مفيش حاجة محتاجة ترجمة هنا.")
                res.failed > 0 -> setStatus("اتترجم ${res.translated} نص، و${res.failed} مقدرتش" + (note?.let { " ($it)" } ?: ""), warn = true)
                else -> setStatus("اتترجم ${res.translated} نص. اسحب الشاشة وهتختفي الترجمة وترجع اللوحة.")
            }
        } catch (t: Throwable) {
            AppLog.e("screen-translate", "failed", t)
            setStatus("حصلت مشكلة: " + (t.message ?: t.javaClass.simpleName).take(80), warn = true)
        } finally {
            busy = false
            if (moved && active) stop()          // the page was dragged while this ran and nothing was left to drop
        }
    }
}
