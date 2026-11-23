package com.ahmedxg.gamebot

/**
 * GameBot is a QA tool for YOUR OWN game. It refuses to run on well-known commercial online shooters
 * (live matches with real players and anti-cheat). Not a security boundary - a statement of intent that
 * also protects you from account bans.
 */
object Safety {
    private val blocked = setOf(
        "com.activision.callofduty.shooter",
        "com.garena.game.codm",
        "com.tencent.tmgp.cod",
        "com.tencent.ig",
        "com.tencent.iglite",
        "com.pubg.krmobile",
        "com.pubg.imobile",
        "com.vng.pubgmobile",
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.criticalforceentertainment.criticalops",
        "com.axlebolt.standoff2"
    )

    const val MESSAGE = "ده لعبة أونلاين تجارية فيها لاعبين حقيقيين. GameBot لاختبار لعبتك إنت بس."

    fun isBlocked(pkg: String): Boolean = pkg in blocked
}
