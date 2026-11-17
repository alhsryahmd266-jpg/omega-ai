package com.omega.keyboard.ime

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.Prefs
import com.omega.keyboard.agent.AutoPilot
import com.omega.keyboard.agent.Notice
import com.omega.keyboard.agent.ScreenTranslateController
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.ai.Translator
import com.omega.keyboard.core.ArabicText
import com.omega.keyboard.core.ColorCodes
import com.omega.keyboard.core.Decor
import com.omega.keyboard.core.ColorMath
import com.omega.keyboard.core.Dictionary
import com.omega.keyboard.core.Key
import com.omega.keyboard.core.KeyKind
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.Layout
import com.omega.keyboard.core.LayoutKind
import com.omega.keyboard.core.Layouts
import com.omega.keyboard.core.LlmPrompts
import com.omega.keyboard.core.TextBox
import com.omega.keyboard.core.TypedLang
import com.omega.keyboard.ui.PhotoPickActivity
import com.omega.keyboard.ui.SettingsActivity
import com.omega.keyboard.voice.VoiceSession
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The keyboard itself. Typing is plain commitText / key events (no composing regions) so it behaves the same in every app.
 * The strip above the keys shows suggestions, the tool row, the typed-text translation bar or short status messages.
 */
class OmegaImeService : InputMethodService(), KeyboardView.Listener, StripView.Listener, TranslateBar.Listener, EmojiPanel.Listener, VoiceSession.Sink {

    companion object {
        @Volatile var instance: OmegaImeService? = null
            private set

        /** in landscape the keys are lower, so the app above still has room */
        private const val LANDSCAPE_HEIGHT = 0.78f

        /** a moment after the last key of the translate bar, the text is translated */
        private const val TR_DELAY = 320L
        private const val TR_WAIT_AFTER = 2500L
        private const val TR_WAIT = "بيجهّز الترجمة… أول مرة بيحمّل حزمة اللغة (محتاج نت)"
        private const val TR_HINT = "اكتب هنا وهتترجم لوحدها"

        private const val SEPARATORS = ".,!?;:)\"…،؛؟"
        private const val SMART_PUNCT = ".,!?;:،؛؟"

        /** chat slang and Arabic written in Latin letters must never be "corrected" */
        private val NO_FIX = setOf(
            "ana", "enta", "inta", "enti", "inti", "howa", "heya", "ehna", "ento", "homma", "eh", "leh", "ezay", "ezayak", "ezayek",
            "kwayes", "kwaysa", "kwais", "tamam", "tmam", "yalla", "yala", "khalas", "bas", "mesh", "msh", "mish", "aywa", "habibi",
            "habibti", "shukran", "shokran", "afwan", "salam", "ahlan", "ahla", "marhaba", "wallah", "wallahi", "walahi", "inshallah",
            "mashallah", "alhamdulillah", "hamdulillah", "keda", "kda", "bokra", "bukra", "naharda", "yani", "malesh", "lesa", "ya",
            "haha", "hahaha", "hehe", "hmm", "mmm", "yep", "yup", "nope", "nah", "gonna", "wanna", "gotta", "dunno", "lemme", "gimme",
            "thx", "pls", "plz", "tmrw", "idk", "imo", "tbh", "btw", "brb", "fyi", "asap", "wtf", "lmao", "rofl", "smh", "irl", "ikr",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var theme: KbTheme = Themes.LIGHT

    // views
    private var root: FrameLayout? = null
    private var strip: StripView? = null
    private var content: FrameLayout? = null
    private var kbd: KeyboardView? = null
    private var overlay: PopupOverlay? = null
    private var panel: View? = null
    private var emojiPanel: EmojiPanel? = null
    private var clipPanel: ClipboardPanel? = null
    private var langPanel: LangPanel? = null
    private var aiPanel: AiPanel? = null
    private var decorPanel: DecorPanel? = null
    private var fontBar: FontBar? = null             // the bar of looks above the keys while a look is on
    private var fontChipsBuilt = false
    private var fontNoteShown = false                // the short note that a real font only shows on the keyboard, said once a session
    private var scene: SceneDrawable? = null         // the picture behind the keyboard in the picture themes
    private var decorLetters = 0                     // letters decorated so far while typing (so the marks vary from letter to letter)
    private var column: LinearLayout? = null
    private var appearance: AppearanceSheet? = null
    private var appearanceBelow = false              // true: the sheet is under the keyboard; false: it covers the keys (landscape)
    private var aurora: AuroraDrawable? = null       // moving background of the aurora look; every key press nudges it
    private var bgSignature: List<Int> = emptyList()
    private var rainbowN = 0                         // next colour of the rainbow

    // field state
    private var editor: EditorInfo? = null
    private var curLang = "ar"
    private var page = LayoutKind.LETTERS
    private var shift = 0
    private var lastShiftTap = 0L
    private var lastSpaceTime = 0L
    private var numeric = false
    private var phone = false
    private var plain = false          // password / e-mail / address field: no suggestions, no corrections, no learning
    private var fieldGen = 0
    /** every key the user presses counts */
    @Volatile private var keyPresses = 0
    @Volatile private var fieldLive = false        // a text box is attached to the keyboard right now (also while the keyboard itself is hidden)
    @Volatile private var fieldPkg: String? = null // the app that box belongs to
    @Volatile private var starts = 0               // how many times a box attached itself to the keyboard (a touch that opens one is proven by this going up)

    // typing helpers
    private val dicts = ConcurrentHashMap<String, Dictionary>()
    private var dictsDirty = false
    private var spaceAfterSuggestion = false
    private var skipCorrectionFor: String? = null

    private class Undo(val original: String, val fixed: String, val sep: String)

    private var undoCorrection: Undo? = null

    // strip state
    private var toolbarForced = false
    private var resizing = false                     // the keyboard is in "drag the edges to resize" mode

    // typed-text translation (the Gboard way): the keys type into a small box in the translate bar, and the translation of that box is
    // written live into the app's own field (as composing text, until the tick keeps it there)
    private var translateBar = false
    private var trBar: TranslateBar? = null
    private val tbox = TextBox()
    private var trGen = 0                            // the newest request: an older answer is dropped
    private var trShown = ""                         // the translation now in the app's field
    private var trShownFor = ""                      // the text of the box it belongs to
    private var trComposing = false                  // the field holds the live translation as composing text
    private var trNeedSpace = false                  // a sentence was just kept: the next one starts after a space
    private var trNote = ""
    private var trNoteWarn = false
    private var trBusy = false
    private var trDetected: String? = null           // the language the automatic source found in the text
    private val trRunnable = Runnable { trTranslate(full = false, thenCommit = false) }
    /** a translation is taking long (the first one downloads the language pack): the bar says so instead of only spinning */
    private val trWait = Runnable {
        if (translateBar && trBusy && trNote.isEmpty()) { trNote = TR_WAIT; trNoteWarn = false; trPush() }
    }
    private var lastReplacement: Triple<String, String, Boolean>? = null   // original, new text, was it a selection
    private var voiceActive = false
    private var voiceText = ""
    private var busyText: String? = null
    private var flashText: String? = null
    private var flashWarn = false
    private var flashActionLabel = ""
    private var flashActionId = ""
    private val flashEnd = Runnable { flashText = null; refreshStrip() }
    private val refreshRunnable = Runnable { refreshSuggestions() }

    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val apListener: (Boolean, String, Boolean) -> Unit = { _, _, _ -> main.post { refreshStrip() } }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs.get(this)
        loadDictionaries()
        registerClipboard()
        AutoPilot.addListener(apListener)
        registerDebugDump()
    }

    /** the phone's tilt for the shine of the luxe look; listens only while the keyboard is on the screen */
    private val tilt by lazy { Tilt(this) { x, y -> kbd?.setTilt(x, y) } }

    override fun onWindowShown() {
        super.onWindowShown()
        kbd?.playIntro()
        if (prefs.fxLuxe && theme.fancy) tilt.start()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        tilt.stop()
    }

    private var debugDump: BroadcastReceiver? = null
    private var debugActive = ""        // preview builds: a tool shown as running, so the emulator test can look at its gold disc


    /** Preview builds only (never in the release APK): lets the emulator test ask where the keys are. */
    private fun registerDebugDump() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == "com.omega.keyboard.DEBUG_FX") {
                    fun f(k: String): Float? = if (i.hasExtra(k)) i.getFloatExtra(k, 0f) else null
                    kbd?.debugFx(f("tx"), f("ty"), f("intro"), f("spark"))
                    return
                }
                if (i.action == "com.omega.keyboard.DEBUG_ACTIVE") {
                    debugActive = i.getStringExtra("tool") ?: ""
                    refreshStrip()
                    return
                }
                kbd?.dumpKeys()
                val bar = trBar
                if (bar != null && bar.visibility == View.VISIBLE) bar.dumpHits() else strip?.dumpHits()
                appearance?.let { if (it.parent != null) it.dumpTabs() }
                decorPanel?.let { if (it.parent != null) android.util.Log.d("OmegaSheet", it.rowsLine()) }
                fontBar?.let { if (it.visibility == View.VISIBLE) android.util.Log.d("OmegaFont", it.chipsLine()) }
            }
        }
        val f = IntentFilter("com.omega.keyboard.DEBUG_DUMP")
        f.addAction("com.omega.keyboard.DEBUG_ACTIVE")
        f.addAction("com.omega.keyboard.DEBUG_FX")
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            debugDump = r
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        tilt.stop()
        debugDump?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        persistUserWords()
        AutoPilot.removeListener(apListener)
        VoiceSession.detach(this)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipListener?.let { try { cm?.removePrimaryClipChangedListener(it) } catch (_: Throwable) {} }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        applyViewPrefs()
        applyLayout()
    }

    override fun onCreateInputView(): View {
        theme = Themes.resolve(this, prefs)
        // BaseFrame: the keyboard decides the height; previews and panels are laid over it (see BaseFrame)
        val frame = BaseFrame(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = StripView(this).apply { listener = this@OmegaImeService; theme = this@OmegaImeService.theme }
        val tr = TranslateBar(this).apply { listener = this@OmegaImeService; theme = this@OmegaImeService.theme; visibility = View.GONE }
        val fb = FontBar(this).apply { listener = fontBarListener; theme = this@OmegaImeService.theme; visibility = View.GONE }
        val ct = BaseFrame(this)
        val k = KeyboardView(this).apply {
            listener = this@OmegaImeService
            theme = this@OmegaImeService.theme
        }
        ct.addView(k, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(tr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(fb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(ct, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ov = PopupOverlay(this)
        frame.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        k.overlay = ov
        frame.setOnApplyWindowInsetsListener { v, insets ->
            // when the system draws the window behind the navigation bar, keep the keys above it
            v.setPadding(0, 0, 0, navInset(insets))
            insets
        }
        root = frame; strip = st; trBar = tr; content = ct; kbd = k; overlay = ov; this.column = column; fontBar = fb
        panel = null
        emojiPanel = null; clipPanel = null; langPanel = null; aiPanel = null; decorPanel = null
        fontChipsBuilt = false
        scene?.release(); scene = null
        appearance = null; appearanceBelow = false; aurora = null; bgSignature = emptyList()
        applyTheme()
        applyViewPrefs()
        return frame
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            fieldGen++
            decorLetters = 0
            lastReplacement = null
            trReset()
            translateBar = false
        }
        undoCorrection = null
        spaceAfterSuggestion = false
        skipCorrectionFor = null
        editor = attribute
        analyseField(attribute)
        fieldLive = attribute != null && attribute.inputType != EditorInfo.TYPE_NULL
        fieldPkg = attribute?.packageName
        if (fieldLive) starts++
    }

    override fun onFinishInput() {
        super.onFinishInput()
        fieldLive = false
        fieldPkg = null
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editor = info
        analyseField(info)
        if (translateBar && (plain || numeric)) { trReset(); translateBar = false }
        applyTheme()
        applyViewPrefs()
        if (!restarting) { hidePanels(); resizing = false; kbd?.resizeMode = false }
        page = if (numeric) LayoutKind.NUMERIC else LayoutKind.LETTERS
        applyLayout()
        updateAutoShift()
        refreshSuggestions()
        if (pendingDictation != null) main.postDelayed({ flushPendingDictation() }, 250)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        kbd?.cancelAll()
        aurora?.stop()
        main.removeCallbacks(refreshRunnable)
        persistUserWords()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 30)
    }

    private fun analyseField(info: EditorInfo?) {
        val t = info?.inputType ?: 0
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        phone = cls == InputType.TYPE_CLASS_PHONE
        val passwordText = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val passwordNum = cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val latinOnly = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI)
        val noSuggestions = (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        plain = passwordText || passwordNum || latinOnly || noSuggestions
        val langs = typingLangs()
        val wanted = if (passwordText || latinOnly) "en" else prefs.lastLang
        curLang = if (wanted in langs) wanted else langs.first()
    }

    @Suppress("DEPRECATION")
    private fun navInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom

    private fun typingLangs(): List<String> = prefs.langList().filter { it == "ar" || it == "en" }.ifEmpty { listOf("ar", "en") }

    // ------------------------------------------------------------------ look

    private fun applyTheme() {
        theme = Themes.resolve(this, prefs)
        val r = root
        if (r != null) {
            // the background (flat, gradient or the moving aurora) is painted once behind strip and keys; it is only
            // replaced when it really changed, so dragging a colour slider does not restart the animation
            val sig = listOf(theme.bgStyle, theme.bg, theme.bg2, theme.spot, theme.hair, theme.scene.hashCode(),
                if (theme.scene == "photo") prefs.bgPhotoRev else 0) + theme.aurora
            if (sig != bgSignature || r.background == null) {
                bgSignature = sig
                aurora?.stop()
                aurora = null
                scene?.release()
                scene = null
                r.background = when (theme.bgStyle) {
                    3 -> pictureFor(theme).also { scene = it }
                    2 -> AuroraDrawable(theme.bg, theme.bg2, theme.aurora).also { aurora = it }
                    1 -> if (theme.spot != 0 || theme.hair != 0) LuxeBackground(theme.bg, theme.bg2, theme.spot, theme.hair)
                         else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bg, theme.bg2))
                    else -> ColorDrawable(theme.bg)
                }
            }
        }
        strip?.theme = theme
        trBar?.theme = theme
        fontBar?.theme = theme
        kbd?.theme = theme
        emojiPanel?.theme = theme
        clipPanel?.theme = theme
        langPanel?.theme = theme
        aiPanel?.theme = theme
        appearance?.theme = theme
        try {
            val w = window?.window ?: return
            w.navigationBarColor = if (theme.bgStyle >= 1) theme.bg2 else theme.bg
            if (Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                val f = w.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility =
                    if (!theme.dark) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } catch (_: Throwable) {
        }
    }

    /** the picture behind the keys: one of the scenes, or the user's own photo (a galaxy until one is chosen) */
    private fun pictureFor(t: KbTheme): SceneDrawable =
        if (t.scene == "photo") SceneDrawable("photo", t.bg, t.bg2, photo = { Photos.load(applicationContext) }, scrim = Themes.PHOTO_SCRIM)
        else SceneDrawable(t.scene, t.bg, t.bg2)

    private fun applyViewPrefs() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val k = kbd ?: return
        k.heightScale = prefs.heightPct.coerceIn(KeyboardView.MIN_HEIGHT_PCT, KeyboardView.MAX_HEIGHT_PCT) / 100f * (if (landscape) LANDSCAPE_HEIGHT else 1f)
        k.fontScale = prefs.fontPct.coerceIn(80, 140) / 100f
        k.widthScale = prefs.widthPct.coerceIn(KeyboardView.MIN_WIDTH_PCT, 100) / 100f
        k.alignFrac = prefs.kbOffset / 100f
        k.fxGlow = prefs.fxGlow
        k.fxRipple = prefs.fxRipple
        k.fxLuxe = prefs.fxLuxe
        if (!prefs.fxLuxe || !theme.fancy) tilt.stop() else if (isInputViewShown) tilt.start()
        k.showPreview = prefs.keyPreview
    }

    private fun layoutFor(p: LayoutKind): Layout = when (p) {
        LayoutKind.LETTERS -> if (curLang == "ar") Layouts.arabic(prefs.numberRow, prefs.indicDigits) else Layouts.english(prefs.numberRow)
        LayoutKind.SYMBOLS1 -> Layouts.symbols1(curLang, prefs.indicDigits)
        LayoutKind.SYMBOLS2 -> Layouts.symbols2(curLang)
        LayoutKind.ARABIC_ALT -> Layouts.arabicAlt()
        LayoutKind.NUMERIC -> Layouts.numeric(phone)
    }

    private fun applyLayout() {
        val k = kbd ?: return
        k.setLayout(layoutFor(page))
        k.spaceLabel = if (page == LayoutKind.NUMERIC) "" else (Languages.find(curLang)?.native ?: "")
        k.shiftState = shift
        k.enterIcon = enterIconName()
    }

    private fun setPage(p: LayoutKind) {
        page = p
        applyLayout()
    }

    private fun enterIconName(): String {
        if (translateBar) return "check"
        val o = editor?.imeOptions ?: return "keyboard_return"
        if ((o and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "keyboard_return"
        return when (o and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS -> "arrow_forward"
            EditorInfo.IME_ACTION_DONE -> "check"
            else -> "keyboard_return"
        }
    }

    private fun cycleLanguage() {
        val langs = typingLangs()
        curLang = langs[(langs.indexOf(curLang) + 1) % langs.size]
        prefs.lastLang = curLang
        page = LayoutKind.LETTERS
        shift = 0
        applyLayout()
        updateAutoShift()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ panels

    private fun showPanel(v: View) {
        hidePanels()
        content?.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kbd?.cancelAll()
        kbd?.visibility = View.INVISIBLE
        panel = v
    }

    private fun hidePanels() {
        panel?.let { content?.removeView(it) }
        panel = null
        appearance?.let { if (appearanceBelow) column?.removeView(it) }
        appearanceBelow = false
        kbd?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ appearance sheet

    private val sheetHost = object : AppearanceSheet.Host {
        override fun onLook() { applyTheme(); applyViewPrefs(); refreshStrip() }
        override fun onSize() { applyViewPrefs() }
        override fun onClose() { hideAppearance() }
        override fun onDragResize() { startResize() }
        override fun onPickPhoto() {
            try {
                startActivity(Intent(this@OmegaImeService, PhotoPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                flash("مقدرتش أفتح المعرض", true)
            }
        }
    }

    // ------------------------------------------------------------------ resize by hand

    /** Handles appear around the keys: the user drags the top edge for the height and the side edges for the width. */
    private fun startResize() {
        hidePanels()                 // closes the appearance sheet when it was open and shows the keys again
        if (translateBar) trClose()
        resizing = true
        kbd?.resizeMode = true
        refreshStrip()
    }

    private fun stopResize() {
        if (!resizing) return
        resizing = false
        kbd?.resizeMode = false
        refreshStrip()
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onResized(heightScale: Float, widthScale: Float, alignFrac: Float) {
        prefs.heightPct = Math.round(heightScale / (if (isLandscape()) LANDSCAPE_HEIGHT else 1f) * 100f)
            .coerceIn(KeyboardView.MIN_HEIGHT_PCT, KeyboardView.MAX_HEIGHT_PCT)
        prefs.widthPct = Math.round(widthScale * 100f).coerceIn(KeyboardView.MIN_WIDTH_PCT, 100)
        prefs.kbOffset = Math.round(alignFrac * 100f)
    }

    override fun onResizeReset() {
        prefs.heightPct = 100
        prefs.widthPct = 100
        prefs.kbOffset = 50
        applyViewPrefs()
    }

    override fun onResizeDone() { stopResize() }

    private fun appearanceOpen(): Boolean = appearance?.parent != null

    /** The colours / themes / size sheet. In portrait it opens under the keyboard so every change is seen live above it. */
    private fun showAppearance() {
        val col = column ?: return
        if (appearanceOpen()) { hideAppearance(); return }
        hidePanels()
        val sh = appearance ?: AppearanceSheet(this, prefs, sheetHost).also { appearance = it }
        sh.theme = theme
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            showPanel(sh)
        } else {
            appearanceBelow = true
            // tall enough for the whole colours page (spectrum + switches), but never more than about a third of a small screen
            val dm = resources.displayMetrics
            val want = AppearanceSheet.PORTRAIT_DP * dm.density
            val cap = maxOf(200f * dm.density, dm.heightPixels * 0.36f)
            col.addView(sh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(want, cap).toInt()))
        }
        sh.reopen()
        refreshStrip()
    }

    private fun hideAppearance() {
        if (!appearanceOpen()) return
        hidePanels()
        refreshStrip()
    }

    private fun showEmoji() {
        val p = emojiPanel ?: EmojiPanel(this).also { emojiPanel = it; it.listener = this }
        p.theme = theme
        p.reload()                  // fills the grid (and the "recent" row); without this the panel stays empty
        showPanel(p)
    }

    private fun showClipboard() {
        val p = clipPanel ?: ClipboardPanel(this).also { cp ->
            clipPanel = cp
            cp.onClose = { hidePanels() }
            cp.onPick = { s -> hidePanels(); commit(s) }
            cp.onClear = { saveClips(emptyList()); clipPanel?.show(emptyList()) }
        }
        p.theme = theme
        p.show(loadClips())
        showPanel(p)
    }

    /** the list of languages for the left chip of the translate bar ([source], with "automatic" on top) or the right one */
    private fun showTypedLanguages(source: Boolean) {
        val p = langPanel ?: LangPanel(this).also { lp ->
            langPanel = lp
            lp.onClose = { hidePanels() }
        }
        p.onPick = { code ->
            val (s, t) = if (source) TypedLang.pickSource(prefs.typedSource, prefs.typedTarget, code)
            else TypedLang.pickTarget(prefs.typedSource, prefs.typedTarget, code)
            prefs.typedSource = s
            prefs.typedTarget = t
            hidePanels()
            trLanguagesChanged()
        }
        p.theme = theme
        if (source) p.show("ترجم من", prefs.typedSource, withAuto = true) else p.show("ترجم كتابتك إلى", prefs.typedTarget)
        showPanel(p)
    }

    // ------------------------------------------------------------------ text decoration (زخرفة)

    /** the look the letters get as they are typed (null: off, or a box where decoration makes no sense) */
    private fun decorStyleLive(): Decor.Style? {
        if (!prefs.decorLive || plain || numeric) return null
        return Decor.byId(prefs.decorStyle)?.takeIf { it.live }
    }

    private val fontBarListener = object : FontBar.Listener {
        override fun onChip(id: String) { fontChipPicked(id) }
        override fun onMore() { showDecor() }
    }

    /** a chip of the bar of looks was tapped: "normal" (nothing), a real font (shown on the keyboard only), or a decoration (it travels) */
    private fun fontChipPicked(id: String) {
        when {
            id == "normal" -> { prefs.decorLive = false; prefs.arFont = ""; decorLetters = 0 }
            id.startsWith("f:") -> {
                prefs.arFont = id.substring(2)
                prefs.decorLive = false
                if (!fontNoteShown) { fontNoteShown = true; flash("الخط بيظهر على الكيبورد بس، مش في الرسالة") }
            }
            id.startsWith("d:") -> {
                val st = Decor.byId(id.substring(2)) ?: return
                if (st.live) { prefs.decorStyle = st.id; prefs.decorLive = true; prefs.arFont = ""; decorLetters = 0 }
                else decorPicked(st.id)
            }
        }
        refreshDecor()
        refreshSuggestions()
    }

    /** the chips: normal, the real fonts, then the decorations (Arabic, English, frames), each drawn in its own look */
    private fun buildFontChips(fb: FontBar) {
        val list = ArrayList<FontBar.Chip>()
        list.add(FontBar.Chip("normal", "عادي", group = 0))
        for (f in FontLib.fonts) list.add(FontBar.Chip("f:" + f.id, f.label, FontLib.face(this, f.id), if (f.scale < 0.8f) 0.9f else if (f.scale > 1.1f) 1.15f else 1f, group = 1))
        for (st in Decor.styles) {
            val group = when (st.kind) { Decor.Kind.ARABIC -> 2; Decor.Kind.LATIN -> 3; Decor.Kind.FRAME -> 4 }
            val sample = when (st.kind) { Decor.Kind.ARABIC -> "عربي"; Decor.Kind.LATIN -> "Abc"; Decor.Kind.FRAME -> "نص" }
            list.add(FontBar.Chip("d:" + st.id, Decor.apply(st, sample), group = group))
        }
        fb.chips = list
        fontChipsBuilt = true
    }

    /**
     * The keys, the key bubble and the bar of looks follow the look that is on: a decoration of English letters shows its letters on
     * the keys, a real Arabic font draws the keys and the typing preview in it. The bar is up while a look is on.
     */
    private fun refreshLook() {
        val k = kbd ?: return
        val live = decorStyleLive()
        val font = if (plain || numeric) null else FontLib.byId(prefs.arFont)
        k.capStyle = live?.takeIf { it.kind == Decor.Kind.LATIN }
        k.capFace = font?.let { FontLib.face(this, it.id) }
        k.capScale = font?.scale ?: 1f
        val fb = fontBar ?: return
        val show = (live != null || font != null) && !translateBar && !resizing && panel == null
        val v = if (show) View.VISIBLE else View.GONE
        if (fb.visibility != v) fb.visibility = v
        if (!show) return
        if (!fontChipsBuilt) buildFontChips(fb)
        fb.selected = if (font != null) "f:" + font.id else "d:" + (live?.id ?: "")
        fb.previewFace = k.capFace
        fb.previewScale = k.capScale
        val typed = try { currentInputConnection?.getTextBeforeCursor(24, 0)?.toString().orEmpty() } catch (_: Throwable) { "" }
        fb.preview = typed.replace('\n', ' ').trimStart().takeLast(16).ifEmpty { if (font != null) "نص تجريبي" else "" }
    }

    private fun showDecor() {
        val p = decorPanel ?: DecorPanel(this).also { dp ->
            decorPanel = dp
            dp.onClose = { hidePanels(); refreshStrip() }
            dp.onPick = { id -> decorPicked(id) }
            dp.onLive = { on -> decorLiveChanged(on) }
            dp.onFont = { id -> fontChipPicked("f:$id") }
            dp.onUndo = { undoReplacement(); refreshDecor() }
        }
        p.theme = theme
        refreshDecor()
        showPanel(p)
        refreshStrip()
    }

    /** fills the panel again: the previews use the words now in the box, and the look the box has is marked */
    private fun refreshDecor() {
        val p = decorPanel ?: return
        val ic = currentInputConnection
        val field = if (ic != null) readField(ic).first else ""
        val has = if (field.isBlank()) null else Decor.styles.firstOrNull { Decor.apply(it, field) == field }?.id
        val selected = has ?: if (decorStyleLive() != null) prefs.decorStyle else ""
        p.show(field, selected, prefs.decorLive, lastReplacement != null, prefs.arFont)
    }

    /** a look was tapped: the whole text of the box takes it (or loses it, for "بدون"); with an empty box the look waits for what is typed */
    private fun decorPicked(id: String) {
        val ic = currentInputConnection
        if (ic == null) { flash("اضغط على خانة الكتابة الأول", true); return }
        val style = Decor.byId(id)
        if (style != null) prefs.decorStyle = id
        // a look that works letter by letter stays on for what is typed next; "بدون" switches it off
        if (style != null && style.live) { prefs.decorLive = true; prefs.arFont = ""; decorLetters = 0 }
        else if (style == null) { prefs.decorLive = false; prefs.arFont = "" }
        val (text, wasSelection) = readField(ic)
        if (text.isBlank()) {
            if (style != null && style.live) flash("الحروف هتتزخرف وإنت بتكتب")
            else if (style != null) flash("الإطار بيتحط على الكلام بعد ما تكتبه", true)
            refreshDecor(); refreshSuggestions()
            return
        }
        val out = if (style == null) Decor.plain(text) else Decor.apply(style, text)
        if (out != text) {
            val first = lastReplacement?.takeIf { it.second == text }?.first ?: text      // trying look after look: undo goes back to the very first text
            applyReplacement(first, out, wasSelection)
        }
        refreshDecor()
    }

    private fun decorLiveChanged(on: Boolean) {
        if (on && Decor.byId(prefs.decorStyle)?.live != true) prefs.decorStyle = "royal"
        prefs.decorLive = on
        decorLetters = 0
        refreshDecor()
        refreshSuggestions()
    }

    private fun showAi() {
        val p = aiPanel ?: AiPanel(this).also { ap ->
            aiPanel = ap
            ap.onClose = { hidePanels() }
            ap.onPick = { mode -> hidePanels(); runAi(mode) }
        }
        p.theme = theme
        p.show()
        showPanel(p)
    }

    /** Called by screen translation / autonomous mode so nothing is covered. Safe from any thread. */
    fun hideKeyboard() {
        main.post { try { requestHideSelf(0) } catch (_: Throwable) {} }
    }

    /** Brings the keyboard back after screen translation, when a field is still waiting for text. Safe from any thread. */
    fun showKeyboard() {
        main.post {
            try {
                if (currentInputConnection == null) return@post
                if (Build.VERSION.SDK_INT >= 28) {
                    requestShowSelf(InputMethodManager.SHOW_IMPLICIT)
                } else {
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInputFromInputMethod(window.window?.attributes?.token, InputMethodManager.SHOW_IMPLICIT)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ keys

    override fun onKey(kind: KeyKind, key: Key) {
        when (kind) {
            KeyKind.SHIFT -> toggleShift()
            KeyKind.BACKSPACE -> doBackspace(true)
            KeyKind.SPACE -> doSpace()
            KeyKind.ENTER -> doEnter()
            KeyKind.SYMBOLS -> setPage(LayoutKind.SYMBOLS1)
            KeyKind.SYMBOLS2 -> setPage(LayoutKind.SYMBOLS2)
            KeyKind.LETTERS -> setPage(LayoutKind.LETTERS)
            KeyKind.ALT -> setPage(if (page == LayoutKind.ARABIC_ALT) LayoutKind.LETTERS else LayoutKind.ARABIC_ALT)
            KeyKind.LANG -> cycleLanguage()
            KeyKind.EMOJI -> showEmoji()
            KeyKind.CHAR, KeyKind.SPACER -> {}
        }
    }

    override fun onText(text: String) {
        if (translateBar) { trType(text); return }
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) return
        if (text.length == 1 && SEPARATORS.indexOf(text[0]) >= 0) {
            separator(text)
            afterTyped()
            return
        }
        undoCorrection = null
        spaceAfterSuggestion = false
        val live = decorStyleLive()
        val out = if (live != null) {
            val before = ic.getTextBeforeCursor(2, 0) ?: ""
            Decor.typed(live, text, before, decorLetters).also { decorLetters += Decor.letterCount(text) }
        } else text
        ic.commitText(styled(out), 1)
        afterTyped()
    }

    /**
     * What gets committed for typed text: coloured as the user chose (red, blue, fluorescent, rainbow, or a highlighter pen).
     * The colour travels as ordinary text spans, so it shows in every field that keeps formatting; in password-like
     * fields the text stays plain.
     */
    private fun styled(text: String): CharSequence {
        if (text.isEmpty() || plain || numeric) return text
        val color = prefs.textColor
        val rainbow = prefs.textRainbow
        if (color == 0 && !rainbow) return text
        val sp = SpannableString(text)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (rainbow) {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = Character.charCount(cp)
                if (!Character.isWhitespace(cp)) sp.setSpan(ForegroundColorSpan(ColorMath.rainbow(rainbowN++)), i, i + n, flags)
                i += n
            }
        } else if (prefs.textHighlight) {
            sp.setSpan(BackgroundColorSpan(color), 0, text.length, flags)
            sp.setSpan(ForegroundColorSpan(ColorMath.onColor(color)), 0, text.length, flags)
        } else {
            sp.setSpan(ForegroundColorSpan(color), 0, text.length, flags)
        }
        return sp
    }

    override fun onBackspaceRepeat() { doBackspace(false) }

    override fun onBackspace() { doBackspace(true) }

    override fun onSpaceSwipe(steps: Int) {
        if (translateBar) { tbox.move(steps); trPush(); return }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps).coerceAtMost(6)) { sendDownUpKeyEvents(code) }
        undoCorrection = null
        spaceAfterSuggestion = false
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 40)
    }

    override fun onGlobeLong() {
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() } catch (_: Throwable) {}
    }

    override fun onFeedback(kind: KeyKind) {
        keyPresses++
        aurora?.kick()
        if (prefs.haptic) {
            try {
                @Suppress("DEPRECATION")
                kbd?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            } catch (_: Throwable) {
            }
        }
        if (prefs.sound) {
            try {
                val fx = when (kind) {
                    KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                    KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                    KeyKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(fx, -1f)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onEmoji(e: String) {
        if (translateBar) { trType(e); return }
        currentInputConnection?.commitText(e, 1)
        undoCorrection = null
        spaceAfterSuggestion = false
        afterTyped()
    }

    override fun onBackToLetters() { hidePanels() }

    private fun toggleShift() {
        if (curLang != "en") return
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == 2 -> 0
            shift == 1 && now - lastShiftTap < 350 -> 2
            shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
        kbd?.shiftState = shift
    }

    /** Automatic capital letter at the start of a sentence, when the field asks for it. */
    private fun updateAutoShift() {
        if (curLang != "en" || !prefs.autoCap || numeric || shift == 2) return
        if (translateBar) {
            // the capital letter follows the text of the translate bar's own box
            val t = tbox.text
            val want = if (t.isEmpty() || (t.endsWith(" ") && t.trimEnd().lastOrNull()?.let { it == '.' || it == '!' || it == '?' } == true)) 1 else 0
            if (want != shift) { shift = want; kbd?.shiftState = shift }
            return
        }
        val ei = editor ?: return
        val ic = currentInputConnection ?: return
        val want = try { if (ic.getCursorCapsMode(ei.inputType) != 0) 1 else 0 } catch (_: Throwable) { 0 }
        if (want != shift) {
            shift = want
            kbd?.shiftState = shift
        }
    }

    private fun afterTyped() {
        toolbarForced = false
        if (shift == 1) { shift = 0; kbd?.shiftState = 0 }
        afterEdit()
    }

    private fun afterEdit() {
        updateAutoShift()
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 25)
    }

    private fun commit(s: String) {
        if (translateBar) { trType(s); return }
        currentInputConnection?.commitText(s, 1)
        undoCorrection = null
        spaceAfterSuggestion = false
        afterTyped()
    }

    private fun doBackspace(first: Boolean) {
        if (translateBar) { trBackspace(); return }
        val ic = currentInputConnection ?: return
        val undo = undoCorrection
        if (first && undo != null) {
            // right after an automatic correction: backspace gives the typed word back
            val joined = undo.fixed + undo.sep
            val before = ic.getTextBeforeCursor(joined.length, 0)?.toString()
            if (before == joined) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(joined.length, 0)
                ic.commitText(styled(undo.original), 1)
                ic.endBatchEdit()
                skipCorrectionFor = undo.original
                undoCorrection = null
                dicts["en"]?.learn(undo.original)
                dictsDirty = true
                afterEdit()
                return
            }
        }
        undoCorrection = null
        spaceAfterSuggestion = false
        if (deleteDecoratedUnit(ic)) { afterEdit(); return }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    /** A decorated letter goes together with its marks (and its stretch bars) in one press; plain text is left to the normal key. */
    private fun deleteDecoratedUnit(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(24, 0) ?: return false
        if (before.isEmpty()) return false
        val n = Decor.unitBefore(before)
        if (n <= 1 || !Decor.hasDecoration(before.subSequence(before.length - n, before.length))) return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        ic.deleteSurroundingText(n, 0)
        return true
    }

    private fun doSpace() {
        if (translateBar) { trType(" "); return }
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (prefs.doubleSpacePeriod && !plain && !numeric && now - lastSpaceTime < 650) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(styled(". "), 1)
                ic.endBatchEdit()
                lastSpaceTime = 0
                undoCorrection = null
                afterTyped()
                return
            }
        }
        separator(" ")
        lastSpaceTime = now
        afterTyped()
    }

    private fun doEnter() {
        if (translateBar) { trDone(); return }
        if (colourEnter()) { sendColoured(fromEnter = true); return }
        performEnter()
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        undoCorrection = null
        spaceAfterSuggestion = false
        val o = editor?.imeOptions ?: 0
        val action = o and EditorInfo.IME_MASK_ACTION
        val noAction = (o and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        afterTyped()
    }

    // ------------------------------------------------------------------ words, corrections, suggestions

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** the word that ends at the cursor ("" when the cursor is after a space or punctuation) */
    private fun currentWord(ic: InputConnection): String? {
        val before = ic.getTextBeforeCursor(40, 0) ?: return null
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i).trimStart('\'', '’')
    }

    private fun charBefore(ic: InputConnection, wordLen: Int): Char? {
        val s = ic.getTextBeforeCursor(wordLen + 1, 0) ?: return null
        return if (s.length > wordLen) s[0] else null
    }

    private fun matchCase(typed: String, cand: String): String = when {
        typed.length > 1 && typed.none { it.isLowerCase() } && typed.any { it.isUpperCase() } -> cand.uppercase()
        typed.isNotEmpty() && typed[0].isUpperCase() -> cand.replaceFirstChar { it.uppercase() }
        else -> cand
    }

    private fun dictFor(word: String): Dictionary? =
        dicts[if (word.isNotEmpty() && ArabicText.isArabicChar(word.first())) "ar" else "en"]?.takeIf { it.isLoaded }

    /** Types the separator (space or punctuation); the word before it gets its automatic correction first. */
    private fun separator(sep: String) {
        val ic = currentInputConnection ?: return
        undoCorrection = null
        var undo: Undo? = null
        if (!plain && !numeric && decorStyleLive() == null) {
            val word = currentWord(ic)
            if (!word.isNullOrEmpty()) {
                undo = tryAutoCorrect(ic, word)
                if (undo == null && prefs.suggestions && word.length >= 2) {
                    val d = dictFor(word)
                    if (d != null && d.contains(word)) { d.learn(word); dictsDirty = true }
                }
            }
        }
        if (spaceAfterSuggestion && sep.length == 1 && SMART_PUNCT.indexOf(sep[0]) >= 0) {
            if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
        }
        spaceAfterSuggestion = false
        ic.commitText(styled(sep), 1)
        undoCorrection = undo?.let { Undo(it.original, it.fixed, sep) }
    }

    private fun tryAutoCorrect(ic: InputConnection, word: String): Undo? {
        if (!prefs.autoCorrect) return null
        if (word.any { it.code >= 0x250 }) return null                // only Latin-script words are corrected
        // the lonely "i" and its contractions
        if (word == "i" || word.startsWith("i'") || word.startsWith("i’")) {
            val prev = charBefore(ic, word.length)
            if (prev == null || prev.isWhitespace()) {
                val fixed = "I" + word.substring(1)
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(styled(fixed), 1)
                return Undo(word, fixed, "")
            }
            return null
        }
        if (word.length < 3 || word.length > 20) return null
        if (word.any { it.isDigit() } || word[0].isUpperCase() || word.drop(1).any { it.isUpperCase() }) return null
        if (word == skipCorrectionFor) { skipCorrectionFor = null; return null }
        if (word.lowercase() in NO_FIX) return null
        val d = dicts["en"] ?: return null
        if (!d.isLoaded || d.contains(word)) return null
        val prev = charBefore(ic, word.length)
        if (prev != null && !(prev.isWhitespace() || "\"'([{“‘".indexOf(prev) >= 0)) return null   // part of an address, handle or path
        val cand = d.corrections(word, 1).firstOrNull() ?: return null
        if (d.frequency(cand) < (if (cand.length <= 3) 1_000_000 else 50_000)) return null
        val fixed = matchCase(word, cand)
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(styled(fixed), 1)
        return Undo(word, fixed, "")
    }

    private fun suggestionsFor(word: String): List<String> {
        val ar = ArabicText.isArabicChar(word.first())
        val d = dicts[if (ar) "ar" else "en"]?.takeIf { it.isLoaded } ?: return emptyList()
        val known = d.contains(word)
        val out = ArrayList<String>()
        if (!known) out.add(word)
        for (s in d.suggest(word, 4)) { val c = if (ar) s else matchCase(word, s); if (c !in out) out.add(c) }
        if (!known) for (s in d.corrections(word, 2)) { val c = if (ar) s else matchCase(word, s); if (c !in out) out.add(c) }
        return out.take(3)
    }

    private fun refreshSuggestions() {
        val st = strip ?: return
        var list: List<String> = emptyList()
        if (prefs.suggestions && !plain && !numeric && !translateBar && page != LayoutKind.NUMERIC && decorStyleLive() == null) {
            val ic = currentInputConnection
            val word = if (ic != null) currentWord(ic) else null
            if (!word.isNullOrEmpty() && word.none { it.isDigit() }) list = suggestionsFor(word)
        }
        st.suggestions = list
        refreshStrip()
    }

    override fun onSuggestion(text: String) {
        val ic = currentInputConnection ?: return
        val word = currentWord(ic) ?: ""
        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText(styled("$text "), 1)
        ic.endBatchEdit()
        dictFor(text)?.let { it.learn(text); dictsDirty = true }
        undoCorrection = null
        spaceAfterSuggestion = true
        afterTyped()
    }

    // ------------------------------------------------------------------ dictionaries

    private fun loadDictionaries() {
        Bg.io {
            for (lang in listOf("en", "ar")) {
                val d = Dictionary(lang)
                try {
                    assets.open("dict/$lang.txt").bufferedReader(Charsets.UTF_8).useLines { d.load(it) }
                } catch (t: Throwable) {
                    AppLog.e("ime", "dictionary $lang failed", t)
                }
                d.importUser(if (lang == "ar") prefs.userWordsAr else prefs.userWordsEn)
                dicts[lang] = d
            }
            main.post { refreshSuggestions() }
        }
    }

    private fun persistUserWords() {
        if (!dictsDirty) return
        dictsDirty = false
        dicts["ar"]?.let { prefs.userWordsAr = it.exportUser() }
        dicts["en"]?.let { prefs.userWordsEn = it.exportUser() }
    }

    // ------------------------------------------------------------------ clipboard history

    private fun registerClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val l = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged(cm) }
            clipListener = l
            cm.addPrimaryClipChangedListener(l)
        } catch (t: Throwable) {
            AppLog.e("ime", "clipboard listener failed", t)
        }
    }

    private fun onClipChanged(cm: ClipboardManager) {
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            if (clip.description?.label?.toString() == "omega") return                      // our own paste helper
            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
            if (plain) return
            val t = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            if (t.isEmpty() || t.length > 2000) return
            val list = loadClips().toMutableList()
            list.remove(t)
            list.add(0, t)
            saveClips(list.take(20))
        } catch (_: Throwable) {
        }
    }

    private fun loadClips(): List<String> = try {
        val a = JSONArray(prefs.clipboardHistory.ifBlank { "[]" })
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun saveClips(list: List<String>) {
        prefs.clipboardHistory = JSONArray(list).toString()
    }

    // ------------------------------------------------------------------ strip

    /** Same slots as Gboard's tool row (mic, themes, translate, settings, GIF, stickers, more), filled with our own tools. */
    private fun buildTools(): List<StripView.Tool> = listOfNotNull(
        StripView.Tool("voice", "mic", "صوت", voiceActive),
        if (colourButtonShown()) StripView.Tool("csend", "send", "ملوّن") else null,
        StripView.Tool("screen", "g_translate", "الشاشة", ScreenTranslateController.active),   // translates the whole screen
        StripView.Tool("theme", "palette", "المظهر", appearanceOpen()),
        StripView.Tool("resize", "open_in_full", "الحجم", resizing),
        StripView.Tool("auto", "robot", "ذاتي", AutoPilot.running),
        if (!plain && !numeric) StripView.Tool("decor", "brush", "زخرفة", decorPanel?.parent != null) else null,   // decorates the text (real text, any app)
        StripView.Tool("clip", "content_paste", "الحافظة"),
        if (!plain && !numeric) StripView.Tool("typed", "translate", "ترجم", translateBar) else null,   // translates what is typed: far from the screen one
        StripView.Tool("settings", "settings", "إعدادات"),
    ).map { if (it.id == debugActive) it.copy(active = true) else it }

    private fun refreshStrip() {
        val st = strip ?: return
        refreshLook()
        st.tools = buildTools()
        st.swatch = if (prefs.textColor == 0) 0xFF2979FF.toInt() else prefs.textColor
        st.swatchRainbow = prefs.textRainbow
        st.colourSend = colourButtonShown()

        fun status(icon: String, text: String, warn: Boolean, label: String, id: String, calm: Boolean = false) {
            st.statusIcon = icon
            st.statusWarn = warn
            st.statusCalm = calm
            st.statusActionLabel = label
            st.statusActionId = id
            st.statusText = text
            st.mode = StripView.Mode.STATUS
        }

        val busy = busyText
        val ft = flashText
        // the translate bar takes the place of the strip, unless the strip has something to say (listening, a message, autonomous mode...)
        val barUp = translateBar && !resizing && !voiceActive && busy == null && ft == null && !AutoPilot.running
        trBar?.let { b ->
            val v = if (barUp) View.VISIBLE else View.GONE
            if (b.visibility != v) b.visibility = v
        }
        val sv = if (barUp) View.GONE else View.VISIBLE
        if (st.visibility != sv) st.visibility = sv
        if (barUp) trPush()
        when {
            resizing -> status("open_in_full", "اسحب الحواف لتغيير الحجم", false, "تم", "resize_done", calm = true)
            voiceActive -> status("mic", voiceText.ifBlank { "بسمعك..." }, false, "إيقاف", "voice_stop")
            busy != null -> status("auto_awesome", busy, false, "إلغاء", "cancel_busy")
            ft != null -> status(if (flashWarn) "warning" else "info", ft, flashWarn, flashActionLabel, flashActionId)
            AutoPilot.running -> status("robot", AutoPilot.status.ifBlank { "الوضع الذاتي شغال" }, false, "إيقاف", "auto_stop")
            toolbarForced || (prefs.toolbarOpen && st.suggestions.isEmpty()) -> st.mode = StripView.Mode.TOOLBAR
            else -> st.mode = StripView.Mode.SUGGEST
        }
    }

    private fun flash(text: String, warn: Boolean = false, actionLabel: String = "", actionId: String = "") {
        var shown = text
        if (text.length > 42) {
            Notice.toast(this, text)
            shown = text.take(40) + "…"
        }
        flashText = shown
        flashWarn = warn
        flashActionLabel = actionLabel
        flashActionId = actionId
        main.removeCallbacks(flashEnd)
        main.postDelayed(flashEnd, if (actionLabel.isNotEmpty()) 7000L else 3800L)
        refreshStrip()
    }

    override fun onToggleToolbar() {
        val st = strip ?: return
        if (st.mode == StripView.Mode.TOOLBAR) {
            prefs.toolbarOpen = false
            toolbarForced = false
        } else {
            prefs.toolbarOpen = true
            toolbarForced = true
        }
        refreshStrip()
    }

    override fun onMic() { startVoice() }

    override fun onColourSend() { sendColoured() }

    override fun onTool(id: String) {
        if (resizing && id != "resize") stopResize()
        when (id) {
            "resize" -> if (resizing) stopResize() else startResize()
            "auto" -> if (AutoPilot.running) AutoPilot.stop("وقفته من الكيبورد") else AutoPilot.start(this)
            "screen" -> ScreenTranslateController.toggle(this)
            "typed" -> if (translateBar) trClose() else trOpen()
            "voice" -> startVoice()
            "ai" -> showAi()
            "clip" -> showClipboard()
            "decor" -> showDecor()
            "theme" -> showAppearance()
            "csend" -> sendColoured()
            "settings" -> try {
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ coloured send (a game's colour codes: real text, never a picture)

    /** a text colour (or the rainbow) is chosen, so what is typed is coloured */
    private fun colourActive(): Boolean = prefs.textColor != 0 || prefs.textRainbow

    /** the colour-code format of the box in front: the one the user picked, or the one the app is known to read (auto) */
    private fun codeFormat(): ColorCodes.Format = ColorCodes.resolve(ColorCodes.Format.of(prefs.colorCodes), fieldPkg ?: editor?.packageName)

    /** the round "send coloured" button is an extra: shown only when the user switched it on, a colour is chosen and the box in front reads colour codes */
    private fun colourButtonShown(): Boolean =
        prefs.colourButton && colourActive() && !plain && !numeric && codeFormat() != ColorCodes.Format.NONE

    /** colour of the n-th letter exactly as typing uses it */
    private fun letterColour(n: Int): Int = if (prefs.textRainbow) ColorMath.rainbow(n) else prefs.textColor

    /**
     * The Enter key of a chat box ("send" action) sends the text in the box's colour codes while a colour is chosen and the user
     * switched that on, if the box in front reads colour codes.
     */
    private fun colourEnter(): Boolean {
        if (!prefs.colourOnSend || !colourActive() || plain || numeric) return false
        val o = editor?.imeOptions ?: 0
        if ((o and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
        if ((o and EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_SEND) return false
        if (codeFormat() == ColorCodes.Format.NONE) return false
        val ic = currentInputConnection ?: return false
        return readField(ic).first.isNotBlank()
    }

    /** Sends what is written in the field as text with the colour's code (where the box reads codes). Plain text carries no colour otherwise. */
    fun sendColoured(@Suppress("UNUSED_PARAMETER") fromEnter: Boolean = false) {
        val ic = currentInputConnection
        if (ic == null) { flash("اضغط على خانة الكتابة الأول", true); return }
        if (plain || numeric) { flash("الخانة دي مش للنص الملون", true); return }
        if (!colourActive()) { flash("اختار لون الكتابة الأول من \"المظهر\"", true); return }
        val fmt = codeFormat()
        if (fmt == ColorCodes.Format.NONE) { flash("التطبيق ده مبيقبلش لون في النص. جرّب \"زخرفة\"", true); return }
        val (raw, wasSelection) = readField(ic)
        val text = raw.trim()
        if (text.isEmpty()) { flash("اكتب الكلام الأول وبعدين دوس زرار اللون", true); return }
        sendWithCodes(text, wasSelection, fmt)
    }

    /** the text becomes a game colour code message ("[FF0000]hello") and is sent */
    private fun sendWithCodes(text: String, wasSelection: Boolean, fmt: ColorCodes.Format) {
        val ic = currentInputConnection ?: return
        val coded = ColorCodes.wrap(text, fmt) { n -> letterColour(n) }
        if (wasSelection) ic.commitText(coded, 1) else replaceNow(coded)
        undoCorrection = null
        spaceAfterSuggestion = false
        performEnter()
        flash("اتبعتت بكود الألوان (${fmt.label.substringBefore("  ")})")
    }

    override fun onStatusAction(id: String) {
        when (id) {
            "voice_stop" -> {
                VoiceSession.stopListening()
                // if the recogniser is already gone there is nothing left to wait for: leave the listening state now
                if (!VoiceSession.active) { voiceActive = false; voiceText = ""; refreshStrip() }
            }
            "resize_done" -> stopResize()
            "auto_stop" -> AutoPilot.stop("وقفته من الكيبورد")
            "cancel_busy" -> { Llm.cancel(); busyText = null; refreshStrip() }
            "undo_text" -> { undoReplacement(); flashText = null; refreshStrip() }
        }
    }

    // ------------------------------------------------------------------ voice

    private fun startVoice() {
        hidePanels()
        VoiceSession.listenForKeyboard(this, this, curLang)
    }

    override fun onVoiceState(active: Boolean, text: String, warn: Boolean) {
        voiceActive = active
        voiceText = text
        if (!active && text.isNotBlank()) flash(text, warn) else refreshStrip()
    }

    /** words that were heard while the field was not attached (the system's voice dialog had the screen): typed as soon as it is back */
    private var pendingDictation: String? = null
    private var pendingAt = 0L

    override fun onDictation(text: String) {
        voiceActive = false
        voiceText = ""
        val ic = currentInputConnection
        if (ic == null || !typeDictation(ic, text)) {
            pendingDictation = text
            pendingAt = SystemClock.uptimeMillis()
            refreshStrip()
        }
    }

    private fun typeDictation(ic: InputConnection, text: String): Boolean {
        if (translateBar) { trType("$text "); return true }
        val ok = try { ic.commitText(styled("$text "), 1) } catch (_: Throwable) { false }
        if (!ok) return false
        undoCorrection = null
        spaceAfterSuggestion = false
        afterTyped()
        return true
    }

    private fun flushPendingDictation() {
        val t = pendingDictation ?: return
        if (SystemClock.uptimeMillis() - pendingAt > 30_000L) { pendingDictation = null; return }
        val ic = currentInputConnection ?: return
        if (typeDictation(ic, t)) pendingDictation = null
    }

    // ------------------------------------------------------------------ whole-field text (translation, rewriting, autonomous mode)

    private fun readField(ic: InputConnection): Pair<String, Boolean> {
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) return sel.toString() to true
        val b = ic.getTextBeforeCursor(8000, 0)?.toString() ?: ""
        val a = ic.getTextAfterCursor(8000, 0)?.toString() ?: ""
        return (b + a) to false
    }

    private fun replaceNow(text: String, coloured: Boolean = false): Boolean {
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        try {
            ic.commitText("", 1)                       // drops a selection if there is one
            var guard = 0
            while (guard++ < 8) {
                val b = ic.getTextBeforeCursor(5000, 0)?.length ?: 0
                val a = ic.getTextAfterCursor(5000, 0)?.length ?: 0
                if (b == 0 && a == 0) break
                ic.deleteSurroundingText(b, a)
            }
            ic.commitText(if (coloured) styled(text) else text, 1)
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    /** Replaces everything in the focused field. Used by autonomous mode when the app refuses its text actions. Any thread. */
    fun replaceFieldText(text: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return replaceNow(text)
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            ok = try { replaceNow(text) } catch (t: Throwable) { AppLog.e("ime", "replace failed", t); false }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    /**
     * Presses the "send" action of the focused box (the framework turns it into an Enter key when the app has no send
     * action of its own). True when it was delivered; whether the app sent anything is for the caller to check.
     */
    fun pressSend(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return sendNow()
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            ok = try { sendNow() } catch (t: Throwable) { AppLog.e("ime", "send failed", t); false }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun sendNow(): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
    }

    // ------------------------------------------------------------------ autonomous mode (called from its own thread)

    /** Runs [block] on the main thread and waits for it (the default is returned when it takes longer than [timeoutMs]). Any thread. */
    private fun <T> onMain(fallback: T, timeoutMs: Long = 3000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try { block() } catch (t: Throwable) { AppLog.e("ime", "main task failed", t); fallback }
        }
        val latch = CountDownLatch(1)
        var out = fallback
        main.post {
            out = try { block() } catch (t: Throwable) { AppLog.e("ime", "main task failed", t); fallback }
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    /** The app whose text box is attached to the keyboard right now (also while the keyboard itself is hidden), or null. */
    fun liveFieldPackage(): String? = if (fieldLive) fieldPkg else null

    /** How many boxes have attached themselves to the keyboard so far. Any thread. */
    fun startCount(): Int = starts

    /** The height of the keyboard on screen right now (0 when it is not shown). Any thread. */
    fun keyboardHeightPx(): Int = root?.takeIf { it.isShown }?.height ?: 0

    /** What is written in the attached box, or null when none is attached. Any thread. */
    fun liveFieldText(): String? = onMain<String?>(null) {
        val ic = currentInputConnection
        if (ic == null || !fieldLive) null else readField(ic).first
    }

    /** Presses the box's own action key (send / go / done), or Enter when it has none. Any thread. */
    fun pressEnter(): Boolean = onMain(false) {
        if (currentInputConnection == null) false else { performEnter(); true }
    }

    /**
     * The reply as the autonomous mode types it in a game: real text, in the game's colour codes when a colour is chosen and the game's
     * format is known (picked by the user, or recognised from the app). Never a picture. Any thread.
     */
    fun colourForGame(text: String): String {
        if (!prefs.apColour || !colourActive()) return text
        val fmt = codeFormat()
        if (fmt == ColorCodes.Format.NONE) return text
        return ColorCodes.wrap(text, fmt) { n -> letterColour(n) }
    }

    private fun applyReplacement(original: String, result: String, wasSelection: Boolean) {
        val ic = currentInputConnection ?: return
        if (wasSelection) ic.commitText(styled(result), 1) else replaceNow(result, coloured = true)
        lastReplacement = Triple(original, result, wasSelection)
        undoCorrection = null
        spaceAfterSuggestion = false
        afterEdit()
    }

    private fun undoReplacement() {
        val t = lastReplacement ?: return
        val ic = currentInputConnection ?: return
        val ok = if (t.third) {
            val before = ic.getTextBeforeCursor(t.second.length, 0)?.toString()
            if (before == t.second) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(t.second.length, 0)
                ic.commitText(styled(t.first), 1)
                ic.endBatchEdit()
                true
            } else false
        } else {
            if (readField(ic).first == t.second) { replaceNow(t.first, coloured = true); true } else false
        }
        lastReplacement = null
        if (!ok) flash("مقدرتش أرجّع النص (اتغيّر)", true) else refreshStrip()
        afterEdit()
    }

    // ------------------------------------------------------------------ typed-text translation (the Gboard way)

    private fun trOpen() {
        if (plain || numeric) { flash("الترجمة مش متاحة في الخانة دي", true); return }
        hidePanels()
        trReset()
        translateBar = true
        kbd?.enterIcon = enterIconName()
        updateAutoShift()
        refreshSuggestions()
        refreshStrip()
    }

    /** the bar is closed: what is in the field stays (it is what the user saw there); what is only in the box is dropped */
    private fun trClose() {
        if (!translateBar) return
        hidePanels()                                 // the list of languages may be open over the keys
        val ic = currentInputConnection
        if (ic != null && trComposing) { try { ic.finishComposingText() } catch (_: Throwable) {} }
        trReset()
        translateBar = false
        kbd?.enterIcon = enterIconName()
        updateAutoShift()
        refreshSuggestions()
        refreshStrip()
    }

    private fun trReset() {
        trGen++
        main.removeCallbacks(trRunnable)
        main.removeCallbacks(trWait)
        tbox.clear()
        trShown = ""
        trShownFor = ""
        trComposing = false
        trNeedSpace = false
        trNote = ""
        trNoteWarn = false
        trBusy = false
        trDetected = null
    }

    private fun trSourceLabel(): String {
        val s = prefs.typedSource
        if (s != TypedLang.AUTO) return Languages.arabicName(s)
        val d = trDetected
        return if (d != null) "تلقائي · ${Languages.arabicName(d)}" else "تلقائي"
    }

    /** shows the box, the languages and the translation in the bar */
    private fun trPush() {
        val b = trBar ?: return
        b.sourceLabel = trSourceLabel()
        b.targetLabel = Languages.arabicName(prefs.typedTarget)
        b.text = tbox.text
        b.caret = tbox.caret
        b.hint = TR_HINT
        b.translation = if (tbox.isEmpty) "" else trShown
        b.note = trNote
        b.noteWarn = trNoteWarn
        b.busy = trBusy
    }

    private fun trType(s: String) {
        if (s.isEmpty()) return
        tbox.insert(s)
        trChanged()
    }

    private fun trBackspace() {
        if (tbox.backspace()) { trChanged(); return }
        // an empty box: the key works on the app's own text (what was kept earlier can still be deleted)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** the text of the box changed: its translation follows a moment later (at once when the box was emptied) */
    private fun trChanged() {
        toolbarForced = false
        if (shift == 1) { shift = 0; kbd?.shiftState = 0 }
        trNote = ""
        trNoteWarn = false
        main.removeCallbacks(trRunnable)
        main.removeCallbacks(trWait)
        if (tbox.text.isBlank()) {
            trGen++                                  // an answer on its way is for text that is gone
            trBusy = false
            trClearField()
        } else {
            main.postDelayed(trRunnable, TR_DELAY)
        }
        updateAutoShift()
        trPush()
    }

    /** removes the live translation from the app's field */
    private fun trClearField() {
        val ic = currentInputConnection
        if (ic != null && trComposing) {
            try {
                ic.beginBatchEdit()
                ic.setComposingText("", 1)
                ic.finishComposingText()
                ic.endBatchEdit()
            } catch (_: Throwable) {
            }
        }
        trComposing = false
        trShown = ""
        trShownFor = ""
    }

    /** the languages were changed: what is in the box is translated again */
    private fun trLanguagesChanged() {
        trDetected = null
        trNote = ""
        trNoteWarn = false
        trShown = ""
        trShownFor = ""
        if (!tbox.isEmpty) trTranslate(full = false, thenCommit = false)
        trPush()
        refreshStrip()
    }

    /**
     * Translates the box. [full]: the model may answer when the quick engine cannot (only when the user asked for it with the tick).
     * [thenCommit]: the translation is kept in the field as soon as it is there.
     */
    private fun trTranslate(full: Boolean, thenCommit: Boolean) {
        main.removeCallbacks(trRunnable)
        if (!translateBar) return
        val text = tbox.text
        if (text.isBlank()) return
        if (!full && prefs.engineMode == "gemma") {
            // the model is slow: no live translation in that mode, the tick asks for it
            trNote = "دوس ✓ عشان يترجم بالنموذج الذكي"
            trNoteWarn = false
            trPush()
            return
        }
        val gen = ++trGen
        val fgen = fieldGen
        val target = prefs.typedTarget
        val source = prefs.typedSource
        val app = applicationContext
        trBusy = true
        trNote = ""
        trNoteWarn = false
        trPush()
        main.removeCallbacks(trWait)
        main.postDelayed(trWait, TR_WAIT_AFTER)
        Bg.io {
            var out: Translator.Out? = null
            var err: String? = null
            try {
                out = Translator.translateText(app, text, target, source, fastOnly = !full)
            } catch (e: Exception) {
                err = trError(e, full)
            }
            main.post { trArrived(gen, fgen, text, out, err, thenCommit) }
        }
    }

    /** the words for a translation that did not happen (the engines' own messages for a live translation are for developers) */
    private fun trError(e: Throwable, full: Boolean): String {
        val m = Translator.friendly(e)
        if (m.any { ArabicText.isArabicChar(it) }) return m
        return if (full) "الترجمة فشلت" else "الترجمة السريعة مش متاحة للنص ده. دوس ✓ يجرّب النموذج الذكي"
    }

    private fun trArrived(gen: Int, fgen: Int, text: String, out: Translator.Out?, err: String?, thenCommit: Boolean) {
        if (gen != trGen || fgen != fieldGen || !translateBar) return
        trBusy = false
        main.removeCallbacks(trWait)
        if (trNote == TR_WAIT) trNote = ""
        if (text != tbox.text) {
            // typed on while it was being translated: this answer is for older text
            if (thenCommit) trTranslate(full = true, thenCommit = true) else trPush()
            return
        }
        val o = out
        if (o == null) {
            trNote = err ?: "الترجمة فشلت"
            trNoteWarn = true
            trPush()
            return
        }
        if (prefs.typedSource == TypedLang.AUTO && o.source != null) trDetected = o.source
        val result = when {
            o.engine == "same" -> {
                trNote = "النص ده بـ ${Languages.arabicName(prefs.typedTarget)} بالفعل"
                trNoteWarn = false
                text                                // no translation is needed: the field gets what was typed
            }
            o.text.isBlank() -> {
                trNote = "الترجمة رجعت فاضية"
                trNoteWarn = true
                null
            }
            else -> o.text
        }
        if (result != null) trShow(result, text)
        trPush()
        if (thenCommit && result != null) trCommit()
    }

    /** writes the translation into the app's field as composing text: the next one replaces it, the tick keeps it */
    private fun trShow(result: String, forText: String) {
        val ic = currentInputConnection ?: return
        try {
            if (!trComposing && trNeedSpace) {
                val before = ic.getTextBeforeCursor(1, 0)
                if (!before.isNullOrEmpty() && !before[0].isWhitespace()) ic.commitText(" ", 1)
                trNeedSpace = false
            }
            ic.setComposingText(styled(result), 1)
            trComposing = true
        } catch (_: Throwable) {
        }
        trShown = result
        trShownFor = forText
    }

    /** the translation stays in the field; the box is empty again for the next sentence */
    private fun trCommit() {
        val ic = currentInputConnection
        if (ic != null && trComposing) { try { ic.finishComposingText() } catch (_: Throwable) {} }
        val kept = trComposing
        trComposing = false
        trNeedSpace = kept
        tbox.clear()
        trShown = ""
        trShownFor = ""
        trNote = ""
        trNoteWarn = false
        undoCorrection = null
        spaceAfterSuggestion = false
        updateAutoShift()
        trPush()
        if (kept && prefs.translateOnSend) performEnter()
    }

    /** Enter / the tick: keeps the translation; with nothing in the box it is the app's own Enter (so a second tick sends) */
    private fun trDone() {
        val text = tbox.text
        if (text.isBlank()) { performEnter(); return }
        if (trShownFor == text && trShown.isNotEmpty()) { trCommit(); return }
        trTranslate(full = true, thenCommit = true)
    }

    override fun onTrBack() { trClose() }

    override fun onTrLanguage(source: Boolean) { showTypedLanguages(source) }

    override fun onTrSwap() {
        val old = tbox.text
        val shown = if (trShownFor == old) trShown else ""
        val (s, t) = TypedLang.swap(prefs.typedSource, prefs.typedTarget, trDetected)
        prefs.typedSource = s
        prefs.typedTarget = t
        if (shown.isNotEmpty() && shown != old) tbox.set(shown)      // the translation becomes the text to translate back
        trLanguagesChanged()
    }

    override fun onTrClear() {
        tbox.clear()
        trChanged()
    }

    override fun onTrDone() { trDone() }

    override fun onTrCaret(pos: Int) {
        tbox.moveTo(pos)
        trPush()
    }

    override fun onTrMic() { startVoice() }

    private fun runAi(mode: String) {
        val app = applicationContext
        if (!Llm.isConfigured(app)) { Notice.needModel(app); return }
        val ic = currentInputConnection ?: return
        val (text, sel) = readField(ic)
        if (text.isBlank()) { flash("اكتب نص الأول وبعدين اختار التحسين", true); return }
        val gen = fieldGen
        busyText = "بحسّن النص... (أول مرة بياخد وقت لحد ما النموذج يتحمّل)"
        refreshStrip()
        Bg.io {
            var result: String? = null
            var err: String? = null
            try {
                val raw = Llm.generate(app, LlmPrompts.rewriteSystem(mode), text, maxTokens = 400, temperature = 0.3f, topK = 40, topP = 0.9f, timeoutMs = 180_000)
                result = LlmPrompts.cleanTranslation(raw).ifBlank { null }
            } catch (e: Exception) {
                err = e.message ?: "فشل"
            }
            main.post {
                if (busyText == null) return@post                      // cancelled by the user
                busyText = null
                if (gen != fieldGen) { refreshStrip(); return@post }
                val r = result
                if (r == null) { flash(err ?: "النموذج ما رجّعش نص", true); return@post }
                applyReplacement(text, r, sel)
                flash("تم التحسين", false, "تراجع", "undo_text")
            }
        }
    }
}
