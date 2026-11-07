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
        // the app simply keeps its default colors. Each step is tried in
        // isolation so one failure cannot block the fallback.
        val seedMode = try {
            mode(activity)
        } catch (e: Exception) {
            return
        }
        if (seedMode == MODE_DYNAMIC) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val seed = color(activity)
        val overlay = com.google.android.material.R.style
            .ThemeOverlay_Material3_DynamicColors_DayNight
        // First choice: a fidelity scheme, whose primary color IS the
        // picked color. The built-in content-based scheme remaps the
        // seed to a fixed mid tone, which turned pastel picks into
        // deep, saturated colors; with fidelity, a light pastel pink
        // the user picked stays a light pastel pink in the UI.
        val fidelityApplied = try {
            val isDark = activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val scheme = SchemeFidelity(Hct.fromInt(seed), isDark, 0.0)
            val colorMap = MaterialColorUtilitiesHelper
                .createColorResourcesIdsToColorValues(scheme)
            val ok = ColorResourcesOverride.getInstance()
                ?.applyIfPossible(activity, colorMap) == true
            // No theme overlay on success: the official content-based
            // flow in DynamicColors also applies none, because the
            // app's Material3 base theme already resolves its colors
            // from the dynamic color resources this override replaces.
            // Applying the DayNight dynamic overlay on top re-pointed
            // the attributes and silently broke the override.
            debugLog(
                activity,
                "fidelity ok=$ok seed=${hexOf(seed)}" +
                    " schemePrimary=${hexOf(scheme.primary)}" +
                    " themePrimary=${resolvePrimary(activity)}" +
                    " mapSize=${colorMap.size}"
            )
            ok
        } catch (e: Exception) {
            debugLog(activity, "fidelity threw: $e")
            false
        }
        if (!fidelityApplied) {
            try {
                val options = DynamicColorsOptions.Builder()
                    .setContentBasedSource(seed)
                    .setThemeOverlay(overlay)
                    .build()
                DynamicColors.applyToActivityIfAvailable(activity, options)
                debugLog(activity, "fallback content-based applied")
            } catch (e: Exception) {
                debugLog(activity, "fallback threw: $e")
            }
        }
    }

    private fun hexOf(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    // The colorPrimary the activity theme actually resolves to after
    // the override, for the diagnostic log.
    private fun resolvePrimary(activity: Activity): String =
        try {
            val value = android.util.TypedValue()
            val found = activity.theme.resolveAttribute(
                androidx.appcompat.R.attr.colorPrimary, value, true
            )
            if (found &&
                value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
            ) {
                hexOf(value.data)
            } else {
                "unresolved"
            }
        } catch (e: Exception) {
            "error"
        }

    // Diagnostic trail in Downloads (mf_theme_debug.txt) so a device
    // where the colors do not apply can tell us which step failed.
    private fun debugLog(activity: Activity, message: String) {
        try {
            val stamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date())
            @Suppress("DEPRECATION")
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
            java.io.File(dir, "mf_theme_debug.txt").appendText(
                "$stamp ${activity.javaClass.simpleName}: $message\n"
            )
        } catch (e: Exception) {
            // Diagnostics must never break the app.
        }
    }
}
