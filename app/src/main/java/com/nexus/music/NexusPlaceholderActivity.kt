package com.nexus.music

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.Theme
import com.nexus.music.ui.Widgets

abstract class NexusPlaceholderActivity : Activity() {
    protected abstract val selectedTab: NexusUi.NavigationTab
    protected abstract val pageTitle: String
    protected abstract val eyebrow: String
    protected abstract val emptyTitle: String
    protected abstract val emptyMessage: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.ColorToken.parchment
        window.navigationBarColor = Theme.ColorToken.parchment
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.ColorToken.parchment)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        content.addView(NexusUi.text(this, "Nexus", 32f, Theme.ColorToken.ink).apply {
            typeface = Type.title(this@NexusPlaceholderActivity)
            letterSpacing = -0.02f
        })
        content.addView(NexusUi.text(this, eyebrow.uppercase(), 11f, Theme.ColorToken.mineral).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(24), 0, dp(8))
        })
        content.addView(NexusUi.text(this, pageTitle, 28f, Theme.ColorToken.ink, bold = true))
        val card = Widgets.paperCard(this, 22).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        card.addView(NexusUi.text(this, emptyTitle, 20f, Theme.ColorToken.ink, bold = true))
        card.addView(NexusUi.text(this, emptyMessage, 14f, Theme.ColorToken.ink2).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            NexusUi.bottomNavigation(
                context = this,
                selected = selectedTab,
                onHome = { navigate(MainActivity::class.java) },
                onLibrary = { navigate(LibraryActivity::class.java) },
                onRoom = { navigate(com.nexus.music.rooms.ListeningRoomActivity::class.java) },
                onYou = { navigate(ProfileActivity::class.java) }
            ),
            LinearLayout.LayoutParams(-1, dp(68)).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(10)
            }
        )
        setContentView(root)
        applySystemBarInsets(root)
    }

    private fun navigate(destination: Class<out Activity>) {
        if (destination == javaClass) return
        startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(R.anim.nexus_page_enter, R.anim.nexus_page_exit)
    }

    private fun applySystemBarInsets(root: LinearLayout) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

class LibraryActivity : NexusPlaceholderActivity() {
    override val selectedTab = NexusUi.NavigationTab.LIBRARY
    override val pageTitle = "Your library"
    override val eyebrow = "Collected and kept"
    override val emptyTitle = "A place for the music you keep"
    override val emptyMessage = "Liked tracks, saved songs and your own playlists will gather here."
}

class ProfileActivity : NexusPlaceholderActivity() {
    override val selectedTab = NexusUi.NavigationTab.YOU
    override val pageTitle = "Your space"
    override val eyebrow = "Nexus account"
    override val emptyTitle = "Your profile is ready when you are"
    override val emptyMessage = "Account details and preferences will live here. Sign-in and cloud backup are intentionally deferred."
}
