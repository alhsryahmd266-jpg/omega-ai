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
 * Whole-screen translation, by hand: one press and the keyboard hides (so nothing is covered), every text on the screen is
 * read through the accessibility service, translated into Arabic, and painted over the original. Nothing happens on its own
 * afterwards: after scrolling to something new, the "ترجم" button on the little bar translates what is on screen then.
 */
object ScreenTranslateController {
    @Volatile var active = false
        private set

    @Volatile private var generation = 0
    @Volatile private var busy = false
    @Volatile private var painted = false
    @Volatile private var paintedAt = 0L
    /** what this session translates into: the Settings value (Arabic unless it was changed there); the bar's language button changes it for this session only */
    @Volatile private var sessionTarget = "ar"
    private var appCtx: Context? = null
    private val quickTargets = listOf("ar", "en", "fr", "es", "de", "tr", "ru", "zh")

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
        active = true
        generation++
        painted = false
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
        OmegaImeService.instance?.hideKeyboard()
        Bg.io { runOnce() }
    }

    fun stop() {
        if (!active && !Overlays.barVisible) return
        active = false
        painted = false
        generation++
        Llm.cancel()
        if (!AutoPilot.running) Overlays.hideAll() else Overlays.hideLayer()
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

    /** A scroll or a new screen: the painted boxes no longer sit on their text, so they come off; translating again is up to the user. */
    fun onScreenEvent(type: Int) {
        if (!active || busy || !painted) return
        val changed = type == AccessibilityEvent.TYPE_VIEW_SCROLLED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!changed || System.currentTimeMillis() - paintedAt < 1500) return   // the app settling after we painted is not the user moving on
        val svc = ScreenAgentService.instance ?: return
        painted = false
        Overlays.withLayer(svc) { it.clear() }
        setStatus("الشاشة اتغيّرت. دوس «ترجم» عشان أترجم اللي ظاهر دلوقتي.")
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
            Keyboards.release(svc)
            if (snap == null) { setStatus("مش قادر أقرا الشاشة دي. افتح صفحة فيها كلام وجرّب تاني.", warn = true); return }
            val items = ScreenFilter.translatable(snap.screen, target)
            snap.release()
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
                    painted = true
                    paintedAt = System.currentTimeMillis()
                    Overlays.withLayer(svc) { it.put(i, tr) }
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
                else -> setStatus("اتترجم ${res.translated} نص. لو مرّرت لحاجة جديدة دوس «ترجم» تاني.")
            }
        } catch (t: Throwable) {
            AppLog.e("screen-translate", "failed", t)
            setStatus("حصلت مشكلة: " + (t.message ?: t.javaClass.simpleName).take(80), warn = true)
        } finally {
            busy = false
        }
    }
}
