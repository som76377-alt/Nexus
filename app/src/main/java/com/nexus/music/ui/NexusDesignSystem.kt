package com.nexus.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Central visual foundation for Nexus.
 *
 * Keep this file presentation-only. It must not own navigation, playback, catalogue,
 * persistence, or room state.
 */
object NexusDesignSystem {
    object Palette {
        // Foundation
        val archiveIvory: Int = Color.rgb(241, 232, 214) // #F1E8D6
        val carbonInk: Int = Color.rgb(38, 33, 30) // #26211E
        val deepVinylGreen: Int = Color.rgb(32, 82, 71) // #205247
        val sleeveWhite: Int = Color.rgb(248, 242, 230) // #F8F2E6

        // Expressive accents
        val oxblood: Int = Color.rgb(121, 59, 72) // #793B48
        val cobalt: Int = Color.rgb(64, 91, 145) // #405B91
        val terracotta: Int = Color.rgb(194, 93, 64) // #C25D40
        val ochre: Int = Color.rgb(211, 164, 77) // #D3A44D
        val jade: Int = Color.rgb(102, 131, 109) // #66836D
        val dustyRose: Int = Color.rgb(184, 120, 121) // #B87879
        val plum: Int = Color.rgb(98, 68, 94) // #62445E
        val petrol: Int = Color.rgb(57, 124, 123) // #397C7B
        val saffron: Int = Color.rgb(224, 182, 93) // #E0B65D

        // Dark appearance foundation
        val nightBackground: Int = Color.rgb(25, 25, 23) // #191917
        val nightSurface: Int = Color.rgb(37, 36, 32) // #252420
        val nightElevated: Int = Color.rgb(48, 45, 40) // #302D28
        val nightText: Int = Color.rgb(240, 230, 213) // #F0E6D5
        val nightMuted: Int = Color.rgb(185, 174, 157) // #B9AE9D
        val nightGreen: Int = Color.rgb(114, 152, 138) // #72988A
        val nightCopper: Int = Color.rgb(207, 128, 87) // #CF8057
        val nightPlum: Int = Color.rgb(167, 123, 146) // #A77B92
        val nightGold: Int = Color.rgb(208, 175, 105) // #D0AF69

        // Compatibility aliases for existing presentation code. These are deliberately
        // mapped to the new identity so callers can migrate incrementally.
        val background: Int get() = archiveIvory
        val surface: Int get() = sleeveWhite
        val raised: Int get() = sleeveWhite
        val accent: Int get() = terracotta
        val foreground: Int get() = carbonInk
        val muted: Int get() = Color.rgb(104, 94, 82)
        val accentForeground: Int get() = sleeveWhite
    }

    object Type {
        /**
         * Temporary platform fallbacks until the licensed display/interface font assets
         * are added. Callers use semantic roles now, so the actual font files can be
         * introduced without changing individual screens.
         */
        fun display(context: Context): Typeface =
            bundledOrFallback(context, "fraunces", Typeface.create("serif", Typeface.NORMAL))

        fun experimental(context: Context): Typeface =
            bundledOrFallback(context, "syne", Typeface.create("sans-serif", Typeface.BOLD))

        fun editorial(context: Context): Typeface =
            bundledOrFallback(context, "instrument_serif", Typeface.create("serif", Typeface.NORMAL))

        fun interfaceText(context: Context): Typeface =
            bundledOrFallback(context, "dm_sans", Typeface.create("sans-serif", Typeface.NORMAL))

        fun technical(context: Context): Typeface =
            bundledOrFallback(context, "space_mono", Typeface.create("monospace", Typeface.NORMAL))

        private fun bundledOrFallback(context: Context, resourceName: String, fallback: Typeface): Typeface {
            val id = context.resources.getIdentifier(resourceName, "font", context.packageName)
            if (id == 0) return fallback
            return runCatching { ResourcesCompat.getFont(context, id) ?: fallback }.getOrDefault(fallback)
        }
    }

    object Spacing {
        const val screenHorizontal = 22
        const val screenVertical = 24
        const val sectionGap = 30
        const val compactGap = 8
        const val controlHeight = 48
        const val navigationHeight = 68
        const val miniPlayerArtwork = 48
        const val minimumTouchTarget = 48
    }

    object Shape {
        const val artworkCorner = 6
        const val controlCorner = 10
        const val panelCorner = 18
    }
}
