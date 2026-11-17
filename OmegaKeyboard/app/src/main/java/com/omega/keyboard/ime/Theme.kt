package com.omega.keyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import com.omega.keyboard.Prefs
import com.omega.keyboard.core.ColorMath

/**
 * Everything the keyboard draws is coloured from one of these. The first block is what Gboard-like themes need; the
 * rest ([bg2] ... [neon]) drives the extras: gradients, the moving aurora, glass keys and glowing letters.
 */
data class KbTheme(
    val id: String,
    val dark: Boolean,
    val bg: Int,                       // background (the top colour when it is a gradient)
    val key: Int,
    val keyFn: Int,
    val keyPressed: Int,
    val text: Int,                     // letters on the keys
    val textSub: Int,                  // small hints on the keys
    val accent: Int,
    val onAccent: Int,
    val popupBg: Int,                  // key preview bubble (always opaque)
    val divider: Int,
    val shadow: Int,
    val bg2: Int = bg,                 // bottom colour of a gradient
    val onBg: Int = text,              // text and icons drawn straight on the background (suggestion strip, panels)
    val onBgSub: Int = textSub,
    val onFn: Int = text,              // icons and labels on function keys
    val bgStyle: Int = 0,              // 0 flat, 1 gradient, 2 aurora, 3 picture ([scene])
    val aurora: List<Int> = emptyList(),
    val glass: Boolean = false,        // see-through keys: no shadow, a bright rim and a stronger sheen
    val rim: Int = 0x26FFFFFF,         // light edge along the top of every key
    val neon: Int = 0,                 // non-zero: letters and key edges glow in this colour
    // the "luxe" look (solid themes only): keys with a gradient body and a lit, bevelled edge, a capsule behind the tool row,
    // a glowing enter key, a soft light and a thin line at the top. 0 means "work it out from the colours above".
    val luxe: Boolean = false,
    val keyLo: Int = 0,                // lower colour of a letter key's gradient ([key] is the upper one)
    val fnLo: Int = 0,                 // the same for function keys
    val rimLo: Int = 0,                // dark line along the bottom edge of every key and of the capsule
    val metal: List<Int> = emptyList(), // the enter key and a running tool shine like metal: five colours, top to bottom (default: shades of [accent])
    val capTop: Int = 0,               // capsule behind the tool row: top and bottom colour (default: from the function keys)
    val capBottom: Int = 0,
    val spot: Int = 0,                 // soft light at the top of the background
    val hair: Int = 0,                 // thin line at the very top of the keyboard
    // picture themes: [scene] is painted behind everything when [bgStyle] is 3 ("photo" is the user's own picture)
    val scene: String = "",
    val corner: Int = 1,               // how round the keys are: an index of [Themes.CORNER_DP]
    val onPopup: Int = 0,              // letters inside the key-preview bubble (0: the letter colour)
) {
    /** the luxe look needs solid keys: glass and glowing themes keep their own */
    val fancy: Boolean get() = luxe && !glass && neon == 0

    /**
     * Colours of the gradient body of a letter key (grade 0), a function key (1) and the enter key / a running tool (2), top to bottom.
     * The first two fade from the key colour to a darker one; grade 2 is the metal: a bright edge, the body of the colour, a deep base.
     */
    fun stops(grade: Int): IntArray = when (grade) {
        0 -> intArrayOf(key, if (keyLo != 0) keyLo else ColorMath.shade(key, if (dark) -0.30f else -0.07f))
        1 -> intArrayOf(keyFn, if (fnLo != 0) fnLo else ColorMath.shade(keyFn, if (dark) -0.30f else -0.09f))
        else -> if (metal.size == 5) metal.toIntArray() else intArrayOf(
            ColorMath.shade(accent, 0.55f), ColorMath.shade(accent, 0.22f), accent, ColorMath.shade(accent, -0.20f), ColorMath.shade(accent, -0.38f),
        )
    }

    /** where the [stops] sit along the key (null = evenly): the bright edge is narrow, the body of the metal is wide */
    fun stopPos(grade: Int): FloatArray? = if (grade == 2) floatArrayOf(0f, 0.2f, 0.5f, 0.78f, 1f) else null

    /** dark line along the bottom edge of keys and capsule */
    val rimDark: Int get() = if (rimLo != 0) rimLo else ColorMath.withAlpha(0xFF000000.toInt(), if (dark) 0x96 else 0x46)

    val capHi: Int get() = if (capTop != 0) capTop else ColorMath.shade(keyFn, if (dark) 0.06f else 0.30f)
    val capLo: Int get() = if (capBottom != 0) capBottom else ColorMath.shade(keyFn, if (dark) -0.22f else -0.04f)
}

object Themes {
    /** how round the keys are, in dp: sharp, soft square (the default, like Gboard), rounded, and fully round */
    val CORNER_DP = floatArrayOf(3f, 6f, 10f, 99f)
    val SHAPE_NAMES = listOf("حاد", "مربع ناعم", "مدوّر", "دائري")

    val choices = listOf(
        "auto" to "تلقائي (أسود فخم بالليل · لؤلؤي بالنهار)",
        "luxe" to "أسود فخم (ذهبي)",
        "pearl" to "لؤلؤي (ذهبي)",
        "beach" to "شاطئ الغروب",
        "deep" to "أعماق البحر",
        "paint" to "ألوان سائلة",
        "galaxy" to "مجرة",
        "synth" to "ريترو نيون",
        "photo" to "صورتي من المعرض",
        "classic" to "كلاسيك (أزرار رمادي فاتح)",
        "gboard" to "جوجل (أبيض ومفاتيح غامقة)",
        "light" to "فاتح",
        "dark" to "داكن",
        "amoled" to "أسود (AMOLED)",
        "ocean" to "المحيط",
        "sunset" to "الغروب",
        "aurora" to "أورورا (زجاج متحرك)",
        "neon" to "نيون",
        "rose" to "وردي",
        "forest" to "غابة",
        "mint" to "نعناع",
        "system" to "ألوان النظام (Material You)",
    )

    /** short names under the cards of the theme picker */
    val cardNames = mapOf(
        "beach" to "شاطئ", "deep" to "أعماق", "paint" to "ألوان", "galaxy" to "مجرة", "synth" to "ريترو", "photo" to "صورتي",
        "classic" to "كلاسيك", "gboard" to "جوجل",
        "auto" to "تلقائي", "luxe" to "أسود فخم", "pearl" to "لؤلؤي", "light" to "فاتح", "dark" to "داكن", "amoled" to "أسود",
        "ocean" to "المحيط", "sunset" to "الغروب", "aurora" to "أورورا", "neon" to "نيون", "rose" to "وردي", "forest" to "غابة",
        "mint" to "نعناع", "system" to "النظام",
    )

    /** the id the picker knows a saved theme by ("gold", the old black-and-gold, is the luxe look now) */
    fun canon(id: String): String = if (id == "gold") "luxe" else id

    private fun c(s: String) = Color.parseColor(s)

    val LIGHT = KbTheme("light", false, c("#E8EBF0"), c("#FFFFFF"), c("#CDD3DC"), c("#B9C1CE"), c("#1B1C1F"), c("#6A707C"),
        c("#0B57D0"), c("#FFFFFF"), c("#FFFFFF"), c("#D6DAE1"), c("#26000000"), rim = c("#99FFFFFF"))
    val DARK = KbTheme("dark", true, c("#1F2024"), c("#3A3C42"), c("#2A2C31"), c("#55585F"), c("#ECEDF1"), c("#A2A6B0"),
        c("#8AB4F8"), c("#0A1C3A"), c("#4A4D55"), c("#34363C"), c("#59000000"), rim = c("#24FFFFFF"))
    val AMOLED = KbTheme("amoled", true, c("#000000"), c("#1C1C1E"), c("#111113"), c("#3A3A3D"), c("#F2F2F5"), c("#8E8E96"),
        c("#8AB4F8"), c("#001533"), c("#2A2A2D"), c("#202022"), c("#00000000"), rim = c("#1CFFFFFF"))
    val OCEAN = KbTheme("ocean", true, c("#0E2A47"), c("#1F4F7E"), c("#173F66"), c("#2D6AA3"), c("#FFFFFF"), c("#A8C8E8"),
        c("#4FC3F7"), c("#00293F"), c("#2A6199"), c("#1B4673"), c("#66000000"), bg2 = c("#0A1F36"), bgStyle = 1, rim = c("#33FFFFFF"))
    val SUNSET = KbTheme("sunset", true, c("#2A1A33"), c("#4A2D55"), c("#38224A"), c("#6B4478"), c("#FFF1F5"), c("#D9B5C8"),
        c("#FF8A65"), c("#3A1306"), c("#5C3A6A"), c("#42285A"), c("#66000000"), bg2 = c("#3A1F3A"), bgStyle = 1, rim = c("#33FFFFFF"))

    /** deep night with northern lights behind see-through keys; the lights drift a little with every key you press */
    val AURORA = KbTheme("aurora", true, c("#060A1A"), c("#2EFFFFFF"), c("#1AFFFFFF"), c("#66FFFFFF"), c("#F4F7FF"), c("#B3BDDD"),
        c("#5CF2D6"), c("#00201B"), c("#222A52"), c("#26FFFFFF"), c("#00000000"), bg2 = c("#0B1230"),
        bgStyle = 2, aurora = listOf(c("#00E5C3"), c("#7C4DFF"), c("#FF4FA3")), glass = true, rim = c("#80FFFFFF"))

    /** near-black with tube-light edges and glowing letters */
    val NEON = KbTheme("neon", true, c("#05060C"), c("#0F1220"), c("#0B0D18"), c("#1B2142"), c("#E6F7FF"), c("#6F86A8"),
        c("#FF2BD6"), c("#FFFFFF"), c("#141830"), c("#1E2442"), c("#00000000"), bg2 = c("#0C0522"), bgStyle = 1,
        rim = c("#4D00F0FF"), neon = c("#00F0FF"))

    val ROSE = KbTheme("rose", false, c("#FBE4EC"), c("#FFFFFF"), c("#F3C4D2"), c("#E8A9BC"), c("#3B1623"), c("#96606F"),
        c("#D81B60"), c("#FFFFFF"), c("#FFFFFF"), c("#EBC3D0"), c("#2A000000"), bg2 = c("#F6CEDB"), bgStyle = 1, rim = c("#B3FFFFFF"))

    val FOREST = KbTheme("forest", true, c("#0C2318"), c("#1D4A35"), c("#143826"), c("#2B6A4B"), c("#EAFBF1"), c("#8FC4A8"),
        c("#5EE6A0"), c("#04301A"), c("#25583F"), c("#1A4630"), c("#66000000"), bg2 = c("#08170F"), bgStyle = 1, rim = c("#2EFFFFFF"))

    /** real gold, top to bottom: a bright edge, the gold itself, a deep amber base (the enter key, a running tool, the lines) */
    private val GOLD_METAL = listOf(c("#FFEDA6"), c("#F7CF50"), c("#E0A621"), c("#C2870F"), c("#96620A"))

    /** the default at night: deep black, a thin gold line, bevelled keys and an enter key of polished gold that glows */
    val LUXE = KbTheme("luxe", true, c("#0D0F15"), c("#30343F"), c("#242730"), c("#4B5060"), c("#F4ECDD"), c("#DDB04A"),
        c("#E0A621"), c("#2A1E00"), c("#3A3F4C"), c("#2B2F3A"), c("#8C000000"),
        bg2 = c("#06070A"), onFn = c("#E9E1D0"), bgStyle = 1, rim = c("#40FFF1CF"),
        luxe = true, keyLo = c("#1F222B"), fnLo = c("#171A21"), rimLo = c("#96000000"), metal = GOLD_METAL,
        capTop = c("#22252F"), capBottom = c("#171A22"), spot = c("#22FFD678"), hair = c("#C8E0A621"))

    /** the default by day: warm pearl white with the same gold */
    val PEARL = KbTheme("pearl", false, c("#F3EFE6"), c("#FFFFFF"), c("#E6DFCF"), c("#D9D0BC"), c("#2A2620"), c("#9B7418"),
        c("#C98F17"), c("#2A1E00"), c("#FFFFFF"), c("#DDD5C2"), c("#38000000"),
        bg2 = c("#E4DDCD"), onFn = c("#3A3224"), bgStyle = 1, rim = c("#FFFFFFFF"),
        luxe = true, keyLo = c("#F1ECDF"), fnLo = c("#D8D0BB"), rimLo = c("#466E5828"), metal = GOLD_METAL,
        capTop = c("#FFFFFF"), capBottom = c("#F2EDE0"), spot = c("#78FFFFFF"), hair = c("#A8D3A12A"))

    val MINT = KbTheme("mint", false, c("#DDF3EE"), c("#FFFFFF"), c("#B6DDD3"), c("#9CCFC2"), c("#0D2B26"), c("#5B8780"),
        c("#00897B"), c("#FFFFFF"), c("#FFFFFF"), c("#BBDDD5"), c("#26000000"), bg2 = c("#C9EAE2"), bgStyle = 1, rim = c("#B3FFFFFF"))

    // ------------------------------------------------------------------ picture themes

    /**
     * The look of a picture theme, like the photo themes of phone keyboards: a picture behind everything, light see-through keys with
     * coloured letters, a vivid bubble over the key that is pressed. [bg] and [bg2] show while the picture is being made.
     */
    private fun picture(
        id: String, scene: String, bg: String, bg2: String, ink: String, accent: String, onAccent: String, popup: String, keyA: Int,
    ): KbTheme {
        val white = 0xFFFFFFFF.toInt()
        val inkC = c(ink)
        return KbTheme(
            id, true, c(bg), ColorMath.withAlpha(white, keyA), ColorMath.withAlpha(white, keyA * 2 / 3), ColorMath.withAlpha(white, 0xE0),
            inkC, ColorMath.withAlpha(inkC, 0xB0), c(accent), c(onAccent), c(popup), ColorMath.withAlpha(white, 0x33), 0,
            bg2 = c(bg2), onBg = white, onBgSub = ColorMath.withAlpha(white, 0xBB), onFn = inkC,
            bgStyle = 3, glass = true, rim = ColorMath.withAlpha(white, 0xD8), scene = scene, onPopup = white,
        )
    }

    val BEACH = picture("beach", "beach", "#3F1F78", "#0B0824", "#4A1F7A", "#FFB347", "#3A1A00", "#FF8A1F", 0x9E)
    val DEEP = picture("deep", "deep", "#0A5A7E", "#021A2E", "#0A3F5C", "#22D3EE", "#00323F", "#0FA3C4", 0x99)
    val PAINT = picture("paint", "paint", "#3B1B6B", "#150A26", "#5B2BC0", "#FF4F9A", "#FFFFFF", "#8B3DFF", 0xB8)
    val GALAXY = picture("galaxy", "galaxy", "#1C0C3E", "#04020C", "#3B1D8A", "#8B5CF6", "#FFFFFF", "#7C4DFF", 0xB2)
    val SYNTH = picture("synth", "synth", "#37106A", "#070114", "#7A1A6E", "#FF3FB5", "#FFFFFF", "#FF2F9E", 0xA8)

    /** the user's own picture from the gallery (a galaxy until one is chosen) */
    val PHOTO = picture("photo", "photo", "#1A1A2E", "#0E0E1A", "#3B1D8A", "#FF9F43", "#3A1A00", "#8B3DFF", 0xB0)

    /** a picture theme is painted by [SceneDrawable]; a photo is darkened a little so the strip's text stays readable */
    const val PHOTO_SCRIM = 0x3A

    /** flat, light and quiet: white page, light grey keys with a soft shadow, an orange bubble over the pressed key */
    val CLASSIC = KbTheme("classic", false, c("#FAFBFC"), c("#EDEFF2"), c("#E1E4E9"), c("#CDD2DA"), c("#262A36"), c("#8A90A0"),
        c("#7C4DFF"), c("#FFFFFF"), c("#FF8F1F"), c("#E3E6EB"), c("#22000000"), bg2 = c("#F1F3F6"), rim = c("#99FFFFFF"), onPopup = 0xFFFFFFFF.toInt())

    /** Gboard's light look: grey page, white keys, dark function keys */
    val GBOARD = KbTheme("gboard", false, c("#E8EAED"), c("#FFFFFF"), c("#3C4043"), c("#C8CCD2"), c("#1F2125"), c("#6B7078"),
        c("#1A73E8"), c("#FFFFFF"), c("#FFFFFF"), c("#D5D8DE"), c("#2E000000"), bg2 = c("#DDE0E4"), onFn = c("#FFFFFF"), rim = c("#99FFFFFF"))

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** the theme as the picker offers it, without the user's own colours */
    fun base(ctx: Context, id: String): KbTheme = when (canon(id)) {
        "luxe" -> LUXE
        "pearl" -> PEARL
        "beach" -> BEACH
        "deep" -> DEEP
        "paint" -> PAINT
        "galaxy" -> GALAXY
        "synth" -> SYNTH
        "photo" -> PHOTO
        "classic" -> CLASSIC
        "gboard" -> GBOARD
        "light" -> LIGHT
        "dark" -> DARK
        "amoled" -> AMOLED
        "ocean" -> OCEAN
        "sunset" -> SUNSET
        "aurora" -> AURORA
        "neon" -> NEON
        "rose" -> ROSE
        "forest" -> FOREST
        "mint" -> MINT
        "system" -> system(ctx)
        else -> if (isNight(ctx)) LUXE else PEARL
    }

    /** forgets every colour the user changed on top of a theme (the colour of the typed text is not part of the look) */
    fun resetCustom(p: Prefs) {
        p.keyColor = 0; p.fnColor = 0; p.accentColor = 0; p.letterColor = 0; p.bgColor = 0
        p.bgStyle = -1; p.glassKeys = -1; p.neonLetters = -1
    }

    /** the theme with everything the user changed on top of it (colours, background, glass, glow) */
    fun resolve(ctx: Context, p: Prefs): KbTheme = customise(base(ctx, p.theme), p)

    // ------------------------------------------------------------------ the user's own colours

    private fun flatten(c: Int, under: Int): Int =
        ColorMath.opaque(ColorMath.blend(ColorMath.opaque(under), ColorMath.opaque(c), ColorMath.alpha(c) / 255f))

    private fun auroraFor(bg: Int, accent: Int): List<Int> {
        val h = ColorMath.toHsv(accent)
        val s = maxOf(0.55f, h[1])
        return listOf(
            ColorMath.opaque(accent),
            ColorMath.fromHsv(h[0] + 140f, s, 1f),
            ColorMath.fromHsv(h[0] - 100f, s, 1f),
        )
    }

    private fun withStyle(t: KbTheme, style: Int): KbTheme {
        if (style == t.bgStyle) return t
        val bg2 = when {
            style == 0 -> t.bg
            t.bg2 == t.bg -> ColorMath.gradientPartner(t.bg)
            else -> t.bg2
        }
        val aurora = if (style == 2 && t.aurora.isEmpty()) auroraFor(t.bg, t.accent) else t.aurora
        return t.copy(bgStyle = style, bg2 = bg2, aurora = aurora)
    }

    fun customise(base: KbTheme, p: Prefs): KbTheme {
        var t = base

        // background colour first: the glass keys and the aurora are made from it
        if (p.bgColor != 0) {
            val b = ColorMath.opaque(p.bgColor)
            val on = ColorMath.onColor(b)
            t = t.copy(
                bg = b,
                bg2 = if (t.bgStyle >= 1) ColorMath.gradientPartner(b) else b,
                onBg = on,
                onBgSub = ColorMath.withAlpha(on, 170),
                divider = ColorMath.withAlpha(on, 0x26),
                dark = !ColorMath.isLight(b),
                aurora = if (t.bgStyle == 2) auroraFor(b, t.accent) else t.aurora,
                spot = 0,                                   // the soft gold light only suits the theme's own background
                bgStyle = if (t.bgStyle == 3) 1 else t.bgStyle,      // a colour of one's own replaces a picture
            )
        }
        if (p.bgStyle in 0..2) t = withStyle(t, p.bgStyle)
        if (p.keyShape in 0 until CORNER_DP.size) t = t.copy(corner = p.keyShape)

        // solid or glass keys
        val wantGlass = if (p.glassKeys >= 0) p.glassKeys == 1 else t.glass
        if (wantGlass && !t.glass) {
            t = t.copy(
                glass = true, popupBg = ColorMath.opaque(t.key),
                key = ColorMath.withAlpha(t.key, 0x66), keyFn = ColorMath.withAlpha(t.keyFn, 0x4D),
                keyPressed = ColorMath.withAlpha(t.keyPressed, 0x99), shadow = 0, rim = ColorMath.withAlpha(0xFFFFFFFF.toInt(), 0x80),
            )
        } else if (!wantGlass && t.glass) {
            t = t.copy(
                glass = false,
                key = flatten(t.key, t.bg), keyFn = flatten(t.keyFn, t.bg), keyPressed = flatten(t.keyPressed, t.bg),
                shadow = if (ColorMath.alpha(t.shadow) == 0) 0x59000000 else t.shadow,
                rim = ColorMath.withAlpha(t.rim, 0x30),
            )
        }

        // keys, function keys, letters, highlight colour
        if (p.keyColor != 0) {
            val k = ColorMath.opaque(p.keyColor)
            val light = ColorMath.isLight(k)
            val on = ColorMath.onColor(k)
            t = t.copy(
                key = k,
                keyPressed = ColorMath.shade(k, if (light) -0.16f else 0.22f),
                keyFn = ColorMath.shade(k, if (light) -0.09f else 0.10f),
                text = on, textSub = ColorMath.withAlpha(on, 150), onFn = on,
                popupBg = k,
                keyLo = 0, fnLo = 0, capTop = 0, capBottom = 0,       // the luxe gradients are worked out from the new colours
            )
            if (t.glass) t = t.copy(key = ColorMath.withAlpha(k, 0x66), keyFn = ColorMath.withAlpha(t.keyFn, 0x4D), keyPressed = ColorMath.withAlpha(t.keyPressed, 0x99))
        }
        if (p.fnColor != 0) {
            val f = ColorMath.opaque(p.fnColor)
            t = t.copy(keyFn = if (t.glass) ColorMath.withAlpha(f, 0x66) else f, onFn = ColorMath.onColor(f), fnLo = 0, capTop = 0, capBottom = 0)
        }
        if (p.letterColor != 0) {
            val l = ColorMath.opaque(p.letterColor)
            t = t.copy(text = l, textSub = ColorMath.withAlpha(l, 150))
        }
        if (p.accentColor != 0) {
            val a = ColorMath.opaque(p.accentColor)
            t = t.copy(
                accent = a, onAccent = ColorMath.onColor(a), metal = emptyList(),
                hair = if (t.hair != 0) ColorMath.withAlpha(a, ColorMath.alpha(t.hair)) else 0,
            )
        }

        // glowing letters
        when (p.neonLetters) {
            1 -> if (t.neon == 0) t = t.copy(neon = t.accent)
            0 -> t = t.copy(neon = 0)
        }
        return t
    }

    // ------------------------------------------------------------------ Material You

    /** Follows day/night and, on Android 12+, takes the colours from the wallpaper (Material You) like Gboard does. */
    private fun system(ctx: Context): KbTheme {
        val night = isNight(ctx)
        val base = if (night) DARK else LIGHT
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        return try {
            fun col(id: Int) = ctx.resources.getColor(id, ctx.theme)
            if (night) KbTheme(
                "system", true,
                col(android.R.color.system_neutral1_900), col(android.R.color.system_neutral1_700),
                col(android.R.color.system_neutral2_800), col(android.R.color.system_neutral1_600),
                col(android.R.color.system_neutral1_50), col(android.R.color.system_neutral2_300),
                col(android.R.color.system_accent1_200), col(android.R.color.system_accent1_900),
                col(android.R.color.system_neutral1_600), col(android.R.color.system_neutral2_700), base.shadow,
                rim = base.rim,
            ) else KbTheme(
                "system", false,
                col(android.R.color.system_accent2_100), col(android.R.color.system_neutral1_10),
                col(android.R.color.system_accent2_200), col(android.R.color.system_accent2_300),
                col(android.R.color.system_neutral1_900), col(android.R.color.system_neutral2_600),
                col(android.R.color.system_accent1_600), col(android.R.color.system_neutral1_0),
                col(android.R.color.system_neutral1_0), col(android.R.color.system_accent2_200), base.shadow,
                rim = base.rim,
            )
        } catch (_: Throwable) {
            base
        }
    }
}
