package com.omega.keyboard.ime

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Material icons shipped as vector drawables (res/drawable/ic_<name>.xml). Looked up by name so the Kotlin code
 * never depends on the generated R class. Every call returns an independent drawable that can be tinted freely.
 */
object Icons {
    private val states = HashMap<String, Drawable.ConstantState?>()

    fun get(ctx: Context, name: String): Drawable? {
        val app = ctx.applicationContext
        val cs = states.getOrPut(name) {
            val id = app.resources.getIdentifier("ic_$name", "drawable", app.packageName)
            if (id == 0) null else app.getDrawable(id)?.constantState
        } ?: return null
        return cs.newDrawable(app.resources).mutate()
    }
}
