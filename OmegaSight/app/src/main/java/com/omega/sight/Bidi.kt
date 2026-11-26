package com.omega.sight

/**
 * Inside Arabic text, a piece that is written left to right (a size like 576×1280, a unit, a file type, a library name) can come out
 * reordered. Wrapped like this it keeps its own order, whatever the words around it are.
 */
object Bidi {
    private const val LRE = '‪'
    private const val PDF = '‬'

    fun ltr(s: String): String = "$LRE$s$PDF"

    /** width × height */
    fun dim(w: Int, h: Int): String = ltr("$w×$h")
}
