/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import me.zhanghai.android.files.home.CrashReporter
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeSeed
import me.zhanghai.android.files.theme.night.NightModeHelper

abstract class AppActivity : AppCompatActivity() {
    private var isDelegateCreated = false
    private var appliedSeedVersion = -1

    override fun getDelegate(): AppCompatDelegate {
        val delegate = super.getDelegate()

        if (!isDelegateCreated) {
            isDelegateCreated = true
            NightModeHelper.apply(this)
        }
        return delegate
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installed here so that every activity (including Settings,
        // which installs nothing itself) leaves a crash log behind.
        CrashReporter.install()
        CustomThemeHelper.apply(this)
        ThemeSeed.applyTo(this)
        appliedSeedVersion = ThemeSeed.version(this)

        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // The user picked another seed color while this activity was
        // in the back stack: rebuild it with the new colors.
        if (appliedSeedVersion >= 0 &&
            ThemeSeed.version(this) != appliedSeedVersion
        ) {
            recreate()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
