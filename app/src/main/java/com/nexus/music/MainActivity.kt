package com.nexus.music

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(12, 14, 18)
        window.navigationBarColor = Color.rgb(12, 14, 18)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(36))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        val brand = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 38f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }

        val tagline = TextView(this).apply {
            text = getString(R.string.tagline)
            textSize = 17f
            setTextColor(Color.rgb(185, 195, 207))
            setPadding(0, dp(8), 0, dp(30))
        }

        val status = TextView(this).apply {
            text = getString(R.string.foundation_status)
            textSize = 14f
            setTextColor(Color.rgb(150, 164, 180))
            setPadding(0, dp(18), 0, 0)
        }

        root.addView(brand)
        root.addView(tagline)
        root.addView(status)
        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
