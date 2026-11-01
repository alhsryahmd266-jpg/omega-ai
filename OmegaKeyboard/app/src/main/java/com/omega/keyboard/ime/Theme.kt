package com.omega.keyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

/** Colours of the keyboard. Modelled after Gboard's light / dark / Material-You looks. */
class KbTheme(
    val id: String,
    val dark: Boolean,
    val bg: Int,
    val key: Int,
    val keyFn: Int,
    val keyPressed: Int,
    val text: Int,
    val textSub: Int,
    val accent: Int,
    val onAccent: Int,
    val popupBg: Int,
    val divider: Int,
    val shadow: Int,
)

object Themes {
    val choices = listOf(
        "auto" to "تلقائي (ألوان النظام)",
        "light" to "فاتح",
        "dark" to "داكن",
        "amoled" to "أسود (AMOLED)",
        "ocean" to "المحيط",
        "sunset" to "الغروب",
    )

    private fun c(s: String) = Color.parseColor(s)

    val LIGHT = KbTheme("light", false, c("#E8EBF0"), c("#FFFFFF"), c("#CDD3DC"), c("#B9C1CE"), c("#1B1C1F"), c("#6A707C"),
        c("#0B57D0"), c("#FFFFFF"), c("#FFFFFF"), c("#D6DAE1"), c("#26000000"))
    val DARK = KbTheme("dark", true, c("#1F2024"), c("#3A3C42"), c("#2A2C31"), c("#55585F"), c("#ECEDF1"), c("#A2A6B0"),
        c("#8AB4F8"), c("#0A1C3A"), c("#4A4D55"), c("#34363C"), c("#59000000"))
    val AMOLED = KbTheme("amoled", true, c("#000000"), c("#1C1C1E"), c("#111113"), c("#3A3A3D"), c("#F2F2F5"), c("#8E8E96"),
        c("#8AB4F8"), c("#001533"), c("#2A2A2D"), c("#202022"), c("#00000000"))
    val OCEAN = KbTheme("ocean", true, c("#0E2A47"), c("#1F4F7E"), c("#173F66"), c("#2D6AA3"), c("#FFFFFF"), c("#A8C8E8"),
        c("#4FC3F7"), c("#00293F"), c("#2A6199"), c("#1B4673"), c("#66000000"))
    val SUNSET = KbTheme("sunset", true, c("#2A1A33"), c("#4A2D55"), c("#38224A"), c("#6B4478"), c("#FFF1F5"), c("#D9B5C8"),
        c("#FF8A65"), c("#3A1306"), c("#5C3A6A"), c("#42285A"), c("#66000000"))

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun resolve(ctx: Context, id: String): KbTheme = when (id) {
        "light" -> LIGHT
        "dark" -> DARK
        "amoled" -> AMOLED
        "ocean" -> OCEAN
        "sunset" -> SUNSET
        else -> auto(ctx)
    }

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
                col(android.R.color.system_neutral1_600), col(android.R.color.system_neutral2_700), base.shadow
            ) else KbTheme(
                "auto", false,
                col(android.R.color.system_accent2_100), col(android.R.color.system_neutral1_10),
                col(android.R.color.system_accent2_200), col(android.R.color.system_accent2_300),
                col(android.R.color.system_neutral1_900), col(android.R.color.system_neutral2_600),
                col(android.R.color.system_accent1_600), col(android.R.color.system_neutral1_0),
                col(android.R.color.system_neutral1_0), col(android.R.color.system_accent2_200), base.shadow
            )
        } catch (_: Throwable) {
            base
        }
    }
}
