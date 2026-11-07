/*
 * Custom fork addition: settings entry that opens a dialog to pick
 * how the app's theme color is chosen — wallpaper-based Material You
 * colors, one of the preset theme colors, or a fully custom color
 * built with hue / saturation / value sliders. The choice is stored
 * by ThemeSeed and applied as a generated Material You scheme.
 */

package me.zhanghai.android.files.theme.custom

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.getColorCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.valueCompat

class ThemeSeedPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    // The summary is refreshed from onAttached() and after the dialog
    // saves, never from onBindViewHolder: Preference.setSummary() calls
    // notifyChanged(), which must not happen while the settings list is
    // binding views.
    override fun onAttached() {
        super.onAttached()
        refreshSummary()
    }

    private fun refreshSummary() {
        summary = when (ThemeSeed.mode(context)) {
            ThemeSeed.MODE_PRESET, ThemeSeed.MODE_CUSTOM -> context.getString(
                R.string.theme_seed_summary_color, hexOf(ThemeSeed.color(context))
            )
            else -> context.getString(R.string.theme_seed_summary_dynamic)
        }
    }

    override fun onClick() {
        val activity = context as? Activity ?: return
        showDialog(activity)
    }

    private fun hexOf(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    private fun showDialog(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        var mode = ThemeSeed.mode(activity)
        val presetColors = ThemeColor.entries.map {
            activity.getColorCompat(it.resourceId)
        }
        var selectedPreset = if (mode == ThemeSeed.MODE_PRESET) {
            ThemeSeed.color(activity)
        } else {
            presetColors.first()
        }
        val customHsv = FloatArray(3)
        Color.colorToHSV(
            if (mode == ThemeSeed.MODE_CUSTOM) {
                ThemeSeed.color(activity)
            } else {
                selectedPreset
            },
            customHsv
        )

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        if (!Settings.MATERIAL_DESIGN_3.valueCompat) {
            root.addView(TextView(activity).apply {
                text = activity.getString(R.string.theme_seed_md3_hint)
                setPadding(0, 0, 0, dp(12))
            })
        }

        val radioGroup = RadioGroup(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val radioDynamic = RadioButton(activity).apply {
            id = ID_DYNAMIC
            text = activity.getString(R.string.theme_seed_dynamic)
        }
        val radioPreset = RadioButton(activity).apply {
            id = ID_PRESET
            text = activity.getString(R.string.theme_seed_preset)
        }
        val radioCustom = RadioButton(activity).apply {
            id = ID_CUSTOM
            text = activity.getString(R.string.theme_seed_custom)
        }
        radioGroup.addView(radioDynamic)
        radioGroup.addView(radioPreset)
        radioGroup.addView(radioCustom)
        root.addView(radioGroup)

        // Preset swatches.
        val swatches = mutableListOf<Pair<View, Int>>()
        val presetGrid = GridLayout(activity).apply {
            columnCount = 5
            setPadding(0, dp(8), 0, dp(8))
        }
        fun refreshSwatches() {
            for ((view, color) in swatches) {
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selectedPreset) {
                        setStroke(
                            dp(3),
                            activity.getColorByAttr(android.R.attr.colorPrimary)
                        )
                    }
                }
            }
        }
        for (color in presetColors) {
            val view = View(activity).apply {
                layoutParams = ViewGroup.MarginLayoutParams(dp(44), dp(44)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener {
                    selectedPreset = color
                    radioGroup.check(ID_PRESET)
                    refreshSwatches()
                }
            }
            swatches += view to color
            presetGrid.addView(view)
        }
        refreshSwatches()
        root.addView(presetGrid)

        // Custom color: preview + HSV sliders.
        val customBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val preview = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
        }
        val hexText = TextView(activity)
        customBox.addView(preview)
        customBox.addView(hexText)
        fun refreshPreview() {
            val color = Color.HSVToColor(customHsv)
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(color)
            }
            hexText.text = hexOf(color)
        }
        val sliderLabels = intArrayOf(
            R.string.theme_seed_hue, R.string.theme_seed_saturation,
            R.string.theme_seed_value
        )
        val sliderMax = intArrayOf(360, 100, 100)
        for (index in 0..2) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(TextView(activity).apply {
                text = activity.getString(sliderLabels[index])
                layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(SeekBar(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                max = sliderMax[index]
                progress = if (index == 0) {
                    customHsv[0].toInt()
                } else {
                    (customHsv[index] * 100).toInt()
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?, progress: Int, fromUser: Boolean
                    ) {
                        customHsv[index] =
                            if (index == 0) progress.toFloat() else progress / 100f
                        if (radioGroup.checkedRadioButtonId != ID_CUSTOM) {
                            radioGroup.check(ID_CUSTOM)
                        }
                        refreshPreview()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
            customBox.addView(row)
        }
        refreshPreview()
        root.addView(customBox)

        // Sections stay touchable: touching a swatch or a slider
        // selects that mode through the radio group.
        fun refreshSections() {
            presetGrid.alpha =
                if (radioGroup.checkedRadioButtonId == ID_PRESET) 1f else 0.4f
            customBox.alpha =
                if (radioGroup.checkedRadioButtonId == ID_CUSTOM) 1f else 0.4f
        }
        radioGroup.setOnCheckedChangeListener { _, _ -> refreshSections() }
        radioGroup.check(
            when (mode) {
                ThemeSeed.MODE_PRESET -> ID_PRESET
                ThemeSeed.MODE_CUSTOM -> ID_CUSTOM
                else -> ID_DYNAMIC
            }
        )
        refreshSections()

        val scroll = ScrollView(activity).apply { addView(root) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_theme_seed_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                mode = when (radioGroup.checkedRadioButtonId) {
                    ID_PRESET -> ThemeSeed.MODE_PRESET
                    ID_CUSTOM -> ThemeSeed.MODE_CUSTOM
                    else -> ThemeSeed.MODE_DYNAMIC
                }
                val color = when (mode) {
                    ThemeSeed.MODE_PRESET -> selectedPreset
                    ThemeSeed.MODE_CUSTOM -> Color.HSVToColor(customHsv)
                    else -> ThemeSeed.color(activity)
                }
                ThemeSeed.save(activity, mode, color)
                refreshSummary()
                activity.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val ID_DYNAMIC = 101
        private const val ID_PRESET = 102
        private const val ID_CUSTOM = 103
    }
}
