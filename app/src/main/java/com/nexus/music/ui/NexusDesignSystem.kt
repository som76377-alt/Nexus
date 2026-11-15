package com.nexus.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Small semantic token set for the Nexus material direction.
 * The scanned canvas and metal control are represented by flat placeholders until
 * a licence-verified photograph is committed. UI code must never reference texture files.
 */
object NexusDesignSystem {
    object Palette {
        // M1: matte canvas and supporting flat surfaces.
        val canvas = Color.rgb(20, 23, 25)       // #141719
        val surface = Color.rgb(29, 34, 36)      // #1D2224
        val raised = Color.rgb(38, 44, 47)       // #262C2F
        val foreground = Color.rgb(241, 242, 239)
        val muted = Color.rgb(164, 174, 176)

        // M2: a single brushed-metal placeholder, reserved for the main Player knob.
        val metal = Color.rgb(184, 192, 194)
        val metalForeground = Color.rgb(22, 26, 28)

        // Compatibility names used by existing presentation-only screens.
        val archiveIvory: Int get() = canvas
        val carbonInk: Int get() = foreground
        val deepVinylGreen: Int get() = metal
        val sleeveWhite: Int get() = surface
        val oxblood: Int get() = muted
        val cobalt: Int get() = metal
        val terracotta: Int get() = metal
        val ochre: Int get() = metal
        val jade: Int get() = muted
        val dustyRose: Int get() = muted
        val plum: Int get() = muted
        val petrol: Int get() = muted
        val saffron: Int get() = metal
        val nightBackground: Int get() = canvas
        val nightSurface: Int get() = surface
        val nightElevated: Int get() = raised
        val nightText: Int get() = foreground
        val nightMuted: Int get() = muted
        val nightGreen: Int get() = metal
        val nightCopper: Int get() = metal
        val nightPlum: Int get() = muted
        val nightGold: Int get() = metal

        val background: Int get() = canvas
        val accent: Int get() = metal
        val accentForeground: Int get() = metalForeground
    }

    object Type {
        // One family throughout the interface. The bundled DM Sans file is the single source.
        fun interfaceText(context: Context): Typeface = bundledOrFallback(
            context, "dm_sans", Typeface.create("sans-serif", Typeface.NORMAL)
        )
        fun display(context: Context): Typeface = interfaceText(context)
        fun experimental(context: Context): Typeface = interfaceText(context)
        fun editorial(context: Context): Typeface = interfaceText(context)
        fun technical(context: Context): Typeface = interfaceText(context)

        private fun bundledOrFallback(context: Context, resourceName: String, fallback: Typeface): Typeface {
            val id = context.resources.getIdentifier(resourceName, "font", context.packageName)
            if (id == 0) return fallback
            return runCatching { ResourcesCompat.getFont(context, id) ?: fallback }.getOrDefault(fallback)
        }
    }

    object Spacing {
        const val screenHorizontal = 22
        const val screenVertical = 24
        const val sectionGap = 28
        const val compactGap = 8
        const val controlHeight = 48
        const val navigationHeight = 64
        const val miniPlayerArtwork = 48
        const val minimumTouchTarget = 48
    }

    object Shape {
        const val control = 8
        const val media = 5
        const val large = 18
        // Compatibility aliases; new components should use the three roles above.
        const val artworkCorner = media
        const val controlCorner = control
        const val panelCorner = large
    }
}
