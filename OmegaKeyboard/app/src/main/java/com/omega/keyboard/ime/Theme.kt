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
    val bgStyle: Int = 0,              // 0 flat, 1 gradient, 2 aurora
    val aurora: List<Int> = emptyList(),
    val glass: Boolean = false,        // see-through keys: no shadow, a bright rim and a stronger sheen
    val rim: Int = 0x26FFFFFF,         // light edge along the top of every key
    val neon: Int = 0,                 // non-zero: letters and key edges glow in this colour
)

object Themes {
    val choices = listOf(
        "auto" to "تلقائي (ألوان النظام)",
        "light" to "فاتح",
        "dark" to "داكن",
        "amoled" to "أسود (AMOLED)",
        "ocean" to "المحيط",
        "sunset" to "الغروب",
        "aurora" to "أورورا (زجاج متحرك)",
        "neon" to "نيون",
        "rose" to "وردي",
        "forest" to "غابة",
        "gold" to "أسود وذهبي",
        "mint" to "نعناع",
    )

    /** short names under the cards of the theme picker */
    val cardNames = mapOf(
        "auto" to "تلقائي", "light" to "فاتح", "dark" to "داكن", "amoled" to "أسود", "ocean" to "المحيط", "sunset" to "الغروب",
        "aurora" to "أورورا", "neon" to "نيون", "rose" to "وردي", "forest" to "غابة", "gold" to "ذهبي", "mint" to "نعناع",
    )

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

    val GOLD = KbTheme("gold", true, c("#12100A"), c("#2A2414"), c("#1E1A0E"), c("#4A3E1E"), c("#FFE9A8"), c("#B8A468"),
        c("#FFC107"), c("#2A1F00"), c("#3A3119"), c("#2E2813"), c("#66000000"), bg2 = c("#1C170B"), bgStyle = 1, rim = c("#66FFD54F"))

    val MINT = KbTheme("mint", false, c("#DDF3EE"), c("#FFFFFF"), c("#B6DDD3"), c("#9CCFC2"), c("#0D2B26"), c("#5B8780"),
        c("#00897B"), c("#FFFFFF"), c("#FFFFFF"), c("#BBDDD5"), c("#26000000"), bg2 = c("#C9EAE2"), bgStyle = 1, rim = c("#B3FFFFFF"))

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** the theme as the picker offers it, without the user's own colours */
    fun base(ctx: Context, id: String): KbTheme = when (id) {
        "light" -> LIGHT
        "dark" -> DARK
        "amoled" -> AMOLED
        "ocean" -> OCEAN
        "sunset" -> SUNSET
        "aurora" -> AURORA
        "neon" -> NEON
        "rose" -> ROSE
        "forest" -> FOREST
        "gold" -> GOLD
        "mint" -> MINT
        else -> auto(ctx)
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
            )
        }
        if (p.bgStyle in 0..2) t = withStyle(t, p.bgStyle)

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
            )
            if (t.glass) t = t.copy(key = ColorMath.withAlpha(k, 0x66), keyFn = ColorMath.withAlpha(t.keyFn, 0x4D), keyPressed = ColorMath.withAlpha(t.keyPressed, 0x99))
        }
        if (p.fnColor != 0) {
            val f = ColorMath.opaque(p.fnColor)
            t = t.copy(keyFn = if (t.glass) ColorMath.withAlpha(f, 0x66) else f, onFn = ColorMath.onColor(f))
        }
        if (p.letterColor != 0) {
            val l = ColorMath.opaque(p.letterColor)
            t = t.copy(text = l, textSub = ColorMath.withAlpha(l, 150))
        }
        if (p.accentColor != 0) {
            val a = ColorMath.opaque(p.accentColor)
            t = t.copy(accent = a, onAccent = ColorMath.onColor(a))
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
    private fun auto(ctx: Context): KbTheme {
        val night = isNight(ctx)
        val base = if (night) DARK else LIGHT
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        return try {
            fun col(id: Int) = ctx.resources.getColor(id, ctx.theme)
            if (night) KbTheme(
                "auto", true,
                col(android.R.color.system_neutral1_900), col(android.R.color.system_neutral1_700),
                col(android.R.color.system_neutral2_800), col(android.R.color.system_neutral1_600),
                col(android.R.color.system_neutral1_50), col(android.R.color.system_neutral2_300),
                col(android.R.color.system_accent1_200), col(android.R.color.system_accent1_900),
                col(android.R.color.system_neutral1_600), col(android.R.color.system_neutral2_700), base.shadow,
                rim = base.rim,
            ) else KbTheme(
                "auto", false,
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
