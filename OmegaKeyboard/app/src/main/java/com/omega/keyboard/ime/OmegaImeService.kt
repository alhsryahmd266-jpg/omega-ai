package com.omega.keyboard.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.omega.keyboard.core.ColorMath
import com.omega.keyboard.core.Dictionary
import com.omega.keyboard.core.Key
import com.omega.keyboard.core.KeyKind
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.Layout
import com.omega.keyboard.core.LayoutKind
import com.omega.keyboard.core.Layouts
import com.omega.keyboard.core.LlmPrompts
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
class OmegaImeService : InputMethodService(), KeyboardView.Listener, StripView.Listener, EmojiPanel.Listener, VoiceSession.Sink {

    companion object {
        @Volatile var instance: OmegaImeService? = null
            private set

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

    // typing helpers
    private val dicts = ConcurrentHashMap<String, Dictionary>()
    private var dictsDirty = false
    private var spaceAfterSuggestion = false
    private var skipCorrectionFor: String? = null

    private class Undo(val original: String, val fixed: String, val sep: String)

    private var undoCorrection: Undo? = null

    // strip state
    private var toolbarForced = false
    private var translateBar = false
    private var typedBusy = false
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
    }

    override fun onDestroy() {
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
        val ct = BaseFrame(this)
        val k = KeyboardView(this).apply {
            listener = this@OmegaImeService
            theme = this@OmegaImeService.theme
        }
        ct.addView(k, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        root = frame; strip = st; content = ct; kbd = k; overlay = ov; this.column = column
        panel = null
        emojiPanel = null; clipPanel = null; langPanel = null; aiPanel = null
        appearance = null; appearanceBelow = false; aurora = null; bgSignature = emptyList()
        applyTheme()
        applyViewPrefs()
        return frame
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            fieldGen++
            translateBar = false
            lastReplacement = null
            typedBusy = false
        }
        undoCorrection = null
        spaceAfterSuggestion = false
        skipCorrectionFor = null
        editor = attribute
        analyseField(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editor = info
        analyseField(info)
        applyTheme()
        applyViewPrefs()
        if (!restarting) hidePanels()
        page = if (numeric) LayoutKind.NUMERIC else LayoutKind.LETTERS
        applyLayout()
        updateAutoShift()
        refreshSuggestions()
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
            val sig = listOf(theme.bgStyle, theme.bg, theme.bg2) + theme.aurora
            if (sig != bgSignature || r.background == null) {
                bgSignature = sig
                aurora?.stop()
                aurora = null
                r.background = when (theme.bgStyle) {
                    2 -> AuroraDrawable(theme.bg, theme.bg2, theme.aurora).also { aurora = it }
                    1 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bg, theme.bg2))
                    else -> ColorDrawable(theme.bg)
                }
            }
        }
        strip?.theme = theme
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

    private fun applyViewPrefs() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val k = kbd ?: return
        k.heightScale = prefs.heightPct.coerceIn(70, 150) / 100f * (if (landscape) 0.78f else 1f)
        k.fontScale = prefs.fontPct.coerceIn(80, 140) / 100f
        k.widthScale = prefs.widthPct.coerceIn(70, 100) / 100f
        k.alignMode = prefs.kbAlign
        k.fxGlow = prefs.fxGlow
        k.fxRipple = prefs.fxRipple
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
        override fun onLook() { applyTheme(); applyViewPrefs() }
        override fun onSize() { applyViewPrefs() }
        override fun onClose() { hideAppearance() }
    }

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
            col.addView(sh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 214f).toInt()))
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

    private fun showTypedLanguages() {
        val p = langPanel ?: LangPanel(this).also { lp ->
            langPanel = lp
            lp.onClose = { hidePanels() }
            lp.onPick = { code -> prefs.typedTarget = code; hidePanels(); refreshStrip() }
        }
        p.theme = theme
        p.show("ترجم كتابتك إلى", prefs.typedTarget)
        showPanel(p)
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
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) return
        if (text.length == 1 && SEPARATORS.indexOf(text[0]) >= 0) {
            separator(text)
            afterTyped()
            return
        }
        undoCorrection = null
        spaceAfterSuggestion = false
        ic.commitText(styled(text), 1)
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
        currentInputConnection?.commitText(s, 1)
        undoCorrection = null
        spaceAfterSuggestion = false
        afterTyped()
    }

    private fun doBackspace(first: Boolean) {
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
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    private fun doSpace() {
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
        if (translateBar && prefs.translateOnSend && !typedBusy) {
            translateField(thenEnter = true)
            return
        }
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
        if (!plain && !numeric) {
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
        if (prefs.suggestions && !plain && !numeric && page != LayoutKind.NUMERIC) {
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
    private fun buildTools(): List<StripView.Tool> = listOf(
        StripView.Tool("voice", "mic", "صوت", voiceActive),
        StripView.Tool("screen", "g_translate", "الشاشة", ScreenTranslateController.active),
        StripView.Tool("typed", "translate", "ترجم", translateBar),
        StripView.Tool("theme", "palette", "المظهر", appearanceOpen()),
        StripView.Tool("auto", "smart_toy", "ذاتي", AutoPilot.running),
        StripView.Tool("ai", "auto_awesome", "تحسين"),
        StripView.Tool("clip", "content_paste", "الحافظة"),
        StripView.Tool("settings", "settings", "إعدادات"),
    )

    private fun refreshStrip() {
        val st = strip ?: return
        st.tools = buildTools()
        st.canUndo = translateBar && lastReplacement != null
        st.targetName = Languages.arabicName(prefs.typedTarget)

        fun status(icon: String, text: String, warn: Boolean, label: String, id: String) {
            st.statusIcon = icon
            st.statusWarn = warn
            st.statusActionLabel = label
            st.statusActionId = id
            st.statusText = text
            st.mode = StripView.Mode.STATUS
        }

        val busy = busyText
        val ft = flashText
        when {
            voiceActive -> status("mic", voiceText.ifBlank { "بسمعك..." }, false, "إيقاف", "voice_stop")
            busy != null -> status("auto_awesome", busy, false, "إلغاء", "cancel_busy")
            ft != null -> status(if (flashWarn) "warning" else "info", ft, flashWarn, flashActionLabel, flashActionId)
            AutoPilot.running -> status("smart_toy", AutoPilot.status.ifBlank { "الوضع الذاتي شغال" }, false, "إيقاف", "auto_stop")
            translateBar -> st.mode = StripView.Mode.TRANSLATE
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

    override fun onTool(id: String) {
        when (id) {
            "auto" -> if (AutoPilot.running) AutoPilot.stop("وقفته من الكيبورد") else AutoPilot.start(this)
            "screen" -> ScreenTranslateController.toggle(this)
            "typed" -> { hidePanels(); translateBar = true; refreshStrip() }
            "voice" -> startVoice()
            "ai" -> showAi()
            "clip" -> showClipboard()
            "theme" -> showAppearance()
            "settings" -> try {
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStatusAction(id: String) {
        when (id) {
            "voice_stop" -> {
                VoiceSession.stopListening()
                // if the recogniser is already gone there is nothing left to wait for: leave the listening state now
                if (!VoiceSession.active) { voiceActive = false; voiceText = ""; refreshStrip() }
            }
            "auto_stop" -> AutoPilot.stop("وقفته من الكيبورد")
            "cancel_busy" -> { Llm.cancel(); busyText = null; refreshStrip() }
            "undo_text" -> { undoReplacement(); flashText = null; refreshStrip() }
        }
    }

    override fun onTranslateAction(id: String) {
        when (id) {
            "close" -> { translateBar = false; lastReplacement = null; refreshStrip() }
            "go" -> translateField()
            "lang" -> showTypedLanguages()
            "undo" -> undoReplacement()
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

    override fun onDictation(text: String) {
        voiceActive = false
        voiceText = ""
        val ic = currentInputConnection
        if (ic == null) { refreshStrip(); return }
        ic.commitText(styled("$text "), 1)
        undoCorrection = null
        spaceAfterSuggestion = false
        afterTyped()
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

    private fun translateField(thenEnter: Boolean = false) {
        if (typedBusy) return
        val ic = currentInputConnection ?: return
        val (text, sel) = readField(ic)
        if (text.isBlank()) { flash("اكتب النص الأول وبعدين اضغط \"ترجم\"", true); return }
        val target = prefs.typedTarget
        val gen = fieldGen
        val app = applicationContext
        typedBusy = true
        strip?.busy = true
        Bg.io {
            var out: Translator.Out? = null
            var err: String? = null
            try {
                out = Translator.translateText(app, text, target)
            } catch (e: Exception) {
                err = Translator.friendly(e)
            }
            main.post {
                typedBusy = false
                strip?.busy = false
                if (gen != fieldGen) return@post
                val o = out
                when {
                    o == null -> flash(err ?: "الترجمة فشلت", true)
                    o.engine == "same" -> {
                        flash("النص ده بالفعل بـ ${Languages.arabicName(target)}")
                        if (thenEnter) performEnter()
                    }
                    o.text.isBlank() -> flash("الترجمة رجعت فاضية", true)
                    else -> {
                        applyReplacement(text, o.text, sel)
                        refreshStrip()
                        if (thenEnter) performEnter()
                    }
                }
            }
        }
    }

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
