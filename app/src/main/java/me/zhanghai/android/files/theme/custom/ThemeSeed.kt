/*
 * Custom fork addition: recolors the app from a user-chosen seed
 * color (a preset swatch or any custom color) by generating a
 * Material You color scheme from it at runtime, instead of only
 * using the wallpaper-based dynamic colors. Stored in its own
 * preferences file; a version counter lets activities recreate
 * themselves when the choice changes.
 */

package me.zhanghai.android.files.theme.custom

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.MaterialColorUtilitiesHelper
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeFidelity

object ThemeSeed {
    const val MODE_DYNAMIC = "dynamic"
    const val MODE_PRESET = "preset"
    const val MODE_CUSTOM = "custom"

    private const val PREFS = "theme_seed"
    private const val KEY_MODE = "mode"
    private const val KEY_COLOR = "color"
    private const val KEY_VERSION = "version"

    val DEFAULT_COLOR: Int = 0xFF6750A4.toInt()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_DYNAMIC) ?: MODE_DYNAMIC

    fun color(context: Context): Int =
        prefs(context).getInt(KEY_COLOR, DEFAULT_COLOR)

    fun version(context: Context): Int =
        prefs(context).getInt(KEY_VERSION, 0)

    fun save(context: Context, mode: String, color: Int) {
        prefs(context).edit()
            .putString(KEY_MODE, mode)
            .putInt(KEY_COLOR, color)
            .putInt(KEY_VERSION, version(context) + 1)
            .apply()
    }

    // Must be called before the activity's content view is created,
    // like CustomThemeHelper.apply. No-op in dynamic (wallpaper) mode.
    fun applyTo(activity: Activity) {
        // Never let theme application crash an activity: on any failure
        // the app simply keeps its default colors.
        try {
            if (mode(activity) == MODE_DYNAMIC) {
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return
            }
            val overlay = com.google.android.material.R.style
                .ThemeOverlay_Material3_DynamicColors_DayNight
            // Use a fidelity scheme, whose primary color IS the picked
            // color: the built-in content-based scheme remaps the seed
            // to a fixed mid tone, which turned pastel picks into deep,
            // saturated colors. With fidelity, a light pastel pink the
            // user picked stays a light pastel pink in the UI.
            val isDark = activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val scheme = SchemeFidelity(
                Hct.fromInt(color(activity)), isDark, 0.0
            )
            val colorMap = MaterialColorUtilitiesHelper
                .createColorResourcesIdsToColorValues(scheme)
            val applied = ColorResourcesOverride.getInstance()
                ?.applyIfPossible(activity, colorMap) == true
            if (applied) {
                activity.theme.applyStyle(overlay, true)
            } else {
                // Older devices without resources-loader support fall
                // back to the built-in content-based scheme.
                val options = DynamicColorsOptions.Builder()
                    .setContentBasedSource(color(activity))
                    .setThemeOverlay(overlay)
                    .build()
                DynamicColors.applyToActivityIfAvailable(activity, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
