/*
 * Custom fork addition: edge-to-edge inset handling for the custom
 * screens. Applies the system bar insets as padding on the root layout
 * so content (toolbars, lists, bottom bars) is never drawn underneath
 * the status bar or the navigation bar.
 */

package me.zhanghai.android.files.home

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.applySystemBarInsetsPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom
        )
        insets
    }
}
