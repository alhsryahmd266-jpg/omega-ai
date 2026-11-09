package com.tacticai.bot

/**
 * This app is for testing your OWN game, offline, against its built-in bots — not for use against real
 * people in a commercial multiplayer game. This is a statement of intent enforced in code, not a security
 * boundary (a determined user could rename a package to dodge it) — same limit noted in GameBot's
 * Safety.kt, which this list intentionally matches so the two projects agree on what "known online
 * shooter" means.
 */
object Safety {
    private val BLOCKED_PACKAGES = setOf(
        "com.activision.callofduty.shooter", // Call of Duty: Mobile (Garena build)
        "com.garena.game.codm",              // Call of Duty: Mobile (Garena, alt id)
        "com.tencent.tmgp.cod",              // Call of Duty: Mobile (Tencent)
        "com.tencent.ig",                    // PUBG Mobile
        "com.tencent.iglite",                // PUBG Mobile Lite
        "com.pubg.krmobile",                 // PUBG Mobile KR
        "com.pubg.imobile",                  // PUBG Mobile (alt id)
        "com.vng.pubgmobile",                // PUBG Mobile VNG
        "com.dts.freefiremax",               // Free Fire MAX
        "com.dts.freefireth",                // Free Fire (alt id)
        "com.epicgames.fortnite",            // Fortnite
        "com.ea.gp.apexlegendsmobilefps",    // Apex Legends Mobile
    )

    fun isBlocked(packageName: String?): Boolean = packageName != null && packageName in BLOCKED_PACKAGES

    fun reason(packageName: String?): String =
        "TacticAI اترفض يشتغل: '$packageName' لعبة أونلاين تجارية معروفة. التطبيق مخصّص لاختبار لعبتك إنت بس."
}
