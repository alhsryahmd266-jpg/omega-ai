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
    val scrim: Int = 0,                // picture themes: how much the whole picture is darkened (0..255)
    val scrimTop: Int = 0,             // ... and how much more at the very top, where the text of the suggestion strip sits (0..255)
    val letterShadow: Int = 0,         // non-zero: a soft dark shadow under the letters (white letters on a light picture)
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
        "beach" to "شاطئ باستيل",
        "waves" to "بحر رمادي",
        "paint" to "ألوان مائية",
        "lagoon" to "بحيرة (نعناع وأزرق)",
        "blush" to "بينك (وردي وخوخي)",
        "ruby" to "أحمر ياقوتي",
        "navy" to "كحلي",
        "aqua" to "ماء أزرق شفاف",
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
        "beach" to "شاطئ", "waves" to "بحر", "paint" to "ألوان", "lagoon" to "بحيرة", "blush" to "بينك", "ruby" to "أحمر", "navy" to "كحلي",
        "aqua" to "ماء شفاف", "photo" to "صورتي",
        "classic" to "كلاسيك", "gboard" to "جوجل",
        "auto" to "تلقائي", "luxe" to "أسود فخم", "pearl" to "لؤلؤي", "light" to "فاتح", "dark" to "داكن", "amoled" to "أسود",
        "ocean" to "المحيط", "sunset" to "الغروب", "aurora" to "أورورا", "neon" to "نيون", "rose" to "وردي", "forest" to "غابة",
        "mint" to "نعناع", "system" to "النظام",
    )

    /**
     * The id the picker knows a saved theme by: "gold" (the old black and gold) is the luxe look now, and the looks that were taken out
     * (the sunset beach with palms, the sea with fish, the retro sun, the galaxy) are replaced by the nearest one that is still there.
     */
    fun canon(id: String): String = when (id) {
        "gold" -> "luxe"
        "deep" -> "aqua"
        "synth" -> "blush"
        "galaxy" -> "navy"
        else -> id
    }

    /** the name of a theme for lists: one of [choices], or one of the user's own pictures ("صورتي 2"); null when the id is not known */
    fun nameOf(id: String): String? {
        val k = canon(id)
        choices.firstOrNull { it.first == k }?.let { return it.second }
        val slot = Photos.slotOf(k)
        return if (slot >= 0) Photos.nameOf(slot) else null
    }

    /** the cards of the picker: [choices], and after "photo" one more for every extra picture of the user's own that was added */
    fun cardIds(ctx: Context): List<String> {
        val out = ArrayList<String>()
        for ((id, _) in choices) {
            out.add(id)
            if (id == "photo") for (s in 1 until Photos.SLOTS) if (Photos.has(ctx, s)) out.add(Photos.idOf(s))
        }
        return out
    }

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

    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * The look of a picture theme, like the picture themes of phone keyboards: a picture behind everything, see-through keys, a vivid
     * bubble over the key that is pressed. [bg] and [bg2] show while the picture is being made and behind the panels, so they are dark
     * enough for white text (or light enough for dark text when the picture is [pale]).
     *  - [tint] is the colour of the see-through keys: white (pale keys with coloured letters) or a deep tone of the picture (dark keys
     *    with white letters); [keyA] is how much of it a letter key has (0..255), a function key has two thirds
     *  - [shade] darkens the whole picture and [shadeTop] its top, so the white text of the suggestion strip can be read
     *  - [shadow] puts a soft shadow under white letters
     */
    private fun glassTheme(
        id: String, scene: String, bg: Int, bg2: Int, ink: Int, accent: Int, onAccent: Int, popup: Int, keyA: Int,
        tint: Int = WHITE, pale: Boolean = false, shadow: Int = 0, shade: Int = 0, shadeTop: Int = 0,
    ): KbTheme {
        val on = if (pale) ink else WHITE
        // the function keys of a pale theme are a little clearer than the letter keys; those of a dark one a little darker, so their white
        // labels stay readable where the picture is light (the pale sand under the bottom row, say)
        val fnA = (if (pale) keyA * 2 / 3 else keyA * 9 / 8).coerceAtMost(0xFF)
        return KbTheme(
            id, !pale, bg, ColorMath.withAlpha(tint, keyA), ColorMath.withAlpha(tint, fnA), ColorMath.withAlpha(WHITE, 0xE0),
            ink, ColorMath.withAlpha(ink, 0xB0), accent, onAccent, popup, ColorMath.withAlpha(on, 0x33), 0,
            bg2 = bg2, onBg = on, onBgSub = ColorMath.withAlpha(on, 0xBB), onFn = ink,
            bgStyle = 3, glass = true, rim = ColorMath.withAlpha(WHITE, if (tint == WHITE) 0xD8 else 0xA0), scene = scene, onPopup = WHITE,
            scrim = shade, scrimTop = shadeTop, letterShadow = shadow,
        )
    }

    private fun picture(
        id: String, bg: String, bg2: String, ink: String, accent: String, onAccent: String, popup: String, keyA: Int,
        tint: String = "#FFFFFF", pale: Boolean = false, shadow: Int = 0, shade: Int = 0, shadeTop: Int = 0,
    ): KbTheme = glassTheme(id, id, c(bg), c(bg2), c(ink), c(accent), c(onAccent), c(popup), keyA, c(tint), pale, shadow, shade, shadeTop)

    private const val SOFT_SHADOW = 0x59000000

    // The same kinds of pictures as the picture themes of the phone keyboards (tools/themes draws them): a pastel beach, a grey sea,
    // paint, soft colour gradients. Dark see-through keys carry white letters; pale keys carry coloured letters.
    val BEACH = picture("beach", "#C4566F", "#245C86", "#FFFFFF", "#FFFFFF", "#C2456B", "#E0557E", 0x60,
        tint = "#1B2E58", shadow = SOFT_SHADOW, shadeTop = 0x4A)
    val WAVES = picture("waves", "#4B4F57", "#23262B", "#5A24C8", "#7B2FD6", "#FFFFFF", "#7B2FD6", 0xDC, shadeTop = 0x30)
    val PAINT = picture("paint", "#5B2B8E", "#1E0F36", "#6A2BD8", "#7B35E8", "#FFFFFF", "#8B3DFF", 0xD2, shade = 0x10, shadeTop = 0x78)
    val LAGOON = picture("lagoon", "#2D7F86", "#2E3F7C", "#FFFFFF", "#FFFFFF", "#1B6F78", "#1E9C7B", 0x62,
        tint = "#12405A", shadow = SOFT_SHADOW, shadeTop = 0x30)
    val BLUSH = picture("blush", "#B14588", "#4E3A8E", "#FFFFFF", "#FFFFFF", "#B03A85", "#C03F8E", 0x60,
        tint = "#4A1F66", shadow = SOFT_SHADOW, shadeTop = 0x34)
    val RUBY = picture("ruby", "#B02B30", "#521A44", "#9B1B22", "#8A1220", "#FFFFFF", "#C62828", 0xC8, shadeTop = 0x1C)
    val NAVY = picture("navy", "#0C1250", "#070A32", "#FFFFFF", "#4A6BFF", "#FFFFFF", "#3A57F0", 0x2E, shadow = 0x40000000)

    /** clear water: pale glass keys with deep blue letters, so the picture (the lightest of all) is dark-on-light everywhere */
    val AQUA = picture("aqua", "#9EE6F2", "#BFEFF8", "#0A3F73", "#0B73C4", "#FFFFFF", "#0F86D2", 0x78, pale = true)

    /** what the "my picture" looks fall back to when their picture cannot be read: the galaxy that is drawn by [Scenes] */
    private val GALAXY = picture("galaxy", "#1C0C3E", "#04020C", "#3B1D8A", "#8B5CF6", "#FFFFFF", "#7C4DFF", 0xB2)

    /** a theme made of the picture in [slot]: the colours come from the picture itself, so every picture gets a look that suits it */
    private fun photoTheme(ctx: Context, id: String, slot: Int): KbTheme {
        if (!Photos.has(ctx, slot)) return GALAXY.copy(id = id, scene = id)
        val look = Photos.look(ctx, slot)
        return glassTheme(
            id, id, look.top, look.bottom, look.ink, look.accent, look.onAccent, look.popup, 0xB0,
            shade = look.scrim, shadeTop = minOf(0xB0, look.scrim + 0x28),
        )
    }

    /** flat, light and quiet: white page, light grey keys with a soft shadow, an orange bubble over the pressed key */
    val CLASSIC = KbTheme("classic", false, c("#FAFBFC"), c("#EDEFF2"), c("#E1E4E9"), c("#CDD2DA"), c("#262A36"), c("#8A90A0"),
        c("#7C4DFF"), c("#FFFFFF"), c("#FF8F1F"), c("#E3E6EB"), c("#22000000"), bg2 = c("#F1F3F6"), rim = c("#99FFFFFF"), onPopup = 0xFFFFFFFF.toInt())

    /** Gboard's light look: grey page, white keys, dark function keys */
    val GBOARD = KbTheme("gboard", false, c("#E8EAED"), c("#FFFFFF"), c("#3C4043"), c("#C8CCD2"), c("#1F2125"), c("#6B7078"),
        c("#1A73E8"), c("#FFFFFF"), c("#FFFFFF"), c("#D5D8DE"), c("#2E000000"), bg2 = c("#DDE0E4"), onFn = c("#FFFFFF"), rim = c("#99FFFFFF"))

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** the theme as the picker offers it, without the user's own colours */
    fun base(ctx: Context, id: String): KbTheme {
        val key = canon(id)
        val slot = Photos.slotOf(key)
        if (slot >= 0) return photoTheme(ctx, key, slot)
        return when (key) {
            "luxe" -> LUXE
            "pearl" -> PEARL
            "beach" -> BEACH
            "waves" -> WAVES
            "paint" -> PAINT
            "lagoon" -> LAGOON
            "blush" -> BLUSH
            "ruby" -> RUBY
            "navy" -> NAVY
            "aqua" -> AQUA
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
