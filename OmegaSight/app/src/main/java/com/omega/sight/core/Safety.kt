package com.omega.sight.core

/**
 * The rule the app lives by, in the open: it only works in the one game the owner picked and said is his own offline game, and it
 * refuses to run in the well-known online multiplayer shooters. It reads the screen and touches from outside; it never hooks,
 * patches or reads the memory of any game, and it does nothing to hide from or get around any protection.
 */
object Safety {
    enum class Verdict { OK, NO_GAME, NOT_CONFIRMED, WRONG_APP, ONLINE_GAME }

    /** package prefixes of popular online multiplayer shooters / battle royales */
    val onlinePrefixes: List<String> = listOf(
        "com.activision.callofduty",
        "com.tencent.ig",
        "com.tencent.tmgp",
        "com.tencent.iglite",
        "com.pubg",
        "com.vng.pubgmobile",
        "com.rekoo.pubgm",
        "com.garena.game.freefire",
        "com.dts.freefire",
        "com.epicgames.fortnite",
        "com.ea.gp.apexlegendsmobilefps",
        "com.riotgames",
        "com.proximabeta",
        "com.levelinfinite",
        "com.krafton",
        "com.netease.dwrg",
        "com.gameloft.android.ANMP.GloftA9HM",
        "com.criticalforceentertainment.criticalops",
        "com.fungames.sniper3d",
        "com.xd.standoff",
        "com.axlebolt.standoff2",
    )

    fun isOnlineShooter(pkg: String): Boolean = onlinePrefixes.any { pkg == it || pkg.startsWith("$it.") || pkg.startsWith(it) }

    /**
     * @param picked the package the owner picked as his game ("" = none)
     * @param ownConfirmed he ticked "this is my own offline game"
     * @param foreground the package in front right now
     * @param self this app's own package
     */
    fun verdict(picked: String, ownConfirmed: Boolean, foreground: String, self: String): Verdict {
        if (picked.isEmpty()) return Verdict.NO_GAME
        if (isOnlineShooter(picked)) return Verdict.ONLINE_GAME
        if (!ownConfirmed) return Verdict.NOT_CONFIRMED
        if (foreground != picked && foreground != self) return Verdict.WRONG_APP
        return Verdict.OK
    }

    fun explain(v: Verdict): String = when (v) {
        Verdict.OK -> "جاهز"
        Verdict.NO_GAME -> "اختار لعبتك الأول"
        Verdict.NOT_CONFIRMED -> "أكّد إن دي لعبتك الأوفلاين الخاصة"
        Verdict.WRONG_APP -> "اللعبة مش قدّامك دلوقتي"
        Verdict.ONLINE_GAME -> "ده لعبة أونلاين معروفة — التطبيق مش بيشتغل فيها"
    }
}
