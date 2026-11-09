package com.nexus.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.HapticFeedbackConstants

/** Small, lifecycle-owned motion helpers. Call stop() from Activity.onStop(). */
object Motion {
    fun enter(view: View, durationMs: Long = 220L) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = TypeDp.px(view, 12f)
        view.animate().alpha(1f).translationY(0f)
            .setDuration(durationMs).setInterpolator(DecelerateInterpolator()).start()
    }

    fun press(view: View, pressed: Boolean) {
        view.animate().cancel()
        view.animate().scaleX(if (pressed) .97f else 1f)
            .scaleY(if (pressed) .97f else 1f)
            .setDuration(90L).setInterpolator(DecelerateInterpolator()).start()
    }

    fun playHaptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun stop(view: View) {
        view.animate().cancel()
        view.animate().setListener(null)
    }

    fun slideSheetIn(view: View) {
        view.animate().cancel()
        view.translationY = view.height.toFloat().coerceAtLeast(TypeDp.px(view, 240f))
        view.animate().translationY(0f).setDuration(300L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun fadeOut(view: View, onEnd: () -> Unit) {
        view.animate().alpha(0f).setDuration(180L).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = onEnd()
        }).start()
    }
}

private object TypeDp {
    fun px(view: View, dp: Float): Float = dp * view.resources.displayMetrics.density
}
