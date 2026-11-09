package com.nexus.music.ui

import android.graphics.Color

/** Nexus's fixed editorial palette. Keep pigment colours for small accents only. */
object Theme {
    object ColorToken {
        val parchment = Color.rgb(0xEE, 0xE5, 0xD0)
        val paperStock = Color.rgb(0xF7, 0xF1, 0xE3)
        val ink = Color.rgb(0x26, 0x21, 0x1B)
        val ink2 = Color.rgb(0x6B, 0x61, 0x53)
        val ink3 = Color.rgb(0x9A, 0x8F, 0x7E)
        val mineral = Color.rgb(0x1F, 0x4A, 0x40)
        val copper = Color.rgb(0xA4, 0x55, 0x2F)
        val copperPressed = Color.rgb(0x8A, 0x44, 0x24)
        val saffron = Color.rgb(0xE3, 0xB0, 0x4B)
        val recordBlack = Color.rgb(0x17, 0x13, 0x0F)
        val groove = Color.rgb(0x22, 0x1D, 0x18)
        val vermilion = Color.rgb(0xC3, 0x5A, 0x40)
        val cobalt = Color.rgb(0x3D, 0x63, 0x8C)
        val teal = Color.rgb(0x3D, 0x7D, 0x78)
        val rose = Color.rgb(0xB1, 0x5F, 0x6B)
        val leaf = Color.rgb(0x8A, 0x8B, 0x55)
        val unread = Color.rgb(0xD6, 0x28, 0x28)
    }

    object Metrics {
        const val GRID_DP = 4
        const val GUTTER_DP = 20
        const val SECTION_GAP_DP = 28
        const val CARD_RADIUS_DP = 18
        const val BUTTON_RADIUS_DP = 14
        const val MIN_TOUCH_DP = 48
        const val NAV_HEIGHT_DP = 68
    }
}
