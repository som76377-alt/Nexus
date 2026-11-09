package com.nexus.music.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/** Font lookup is optional by design: builds without bundled font files use platform faces. */
object Type {
    private const val FRAUNCES = "fraunces"
    private const val DM_SANS = "dm_sans"

    fun display(context: Context): Typeface = resolve(context, FRAUNCES, Typeface.SERIF)
    fun title(context: Context): Typeface = resolve(context, FRAUNCES, Typeface.SERIF)
    fun body(context: Context): Typeface = resolve(context, DM_SANS, Typeface.SANS_SERIF)
    fun medium(context: Context): Typeface =
        Typeface.create(body(context), Typeface.BOLD)

    private fun resolve(context: Context, resourceName: String, fallback: Typeface): Typeface {
        val id = context.resources.getIdentifier(resourceName, "font", context.packageName)
        if (id == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return fallback
        return runCatching { context.resources.getFont(id) }.getOrDefault(fallback)
    }
}
