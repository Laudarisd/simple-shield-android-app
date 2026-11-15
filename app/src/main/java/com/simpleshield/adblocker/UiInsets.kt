package com.simpleshield.adblocker

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object UiInsets {
    fun apply(view: View) {
        val initialTop = view.paddingTop
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(target.paddingLeft, initialTop + bars.top, target.paddingRight, initialBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
