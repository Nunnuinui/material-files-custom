/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import java8.nio.file.Path
import me.zhanghai.android.files.settings.PathSettings
import me.zhanghai.android.files.settings.SettingLiveData
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

class FileListShowFileNameLiveData(pathLiveData: LiveData<Path>) : MediatorLiveData<Boolean>() {
    private lateinit var pathShowFileNameLiveData: SettingLiveData<Boolean?>

    private fun loadValue() {
        if (!this::pathShowFileNameLiveData.isInitialized) {
            // Not yet initialized.
            return
        }
        val value = pathShowFileNameLiveData.value ?: Settings.FILE_LIST_SHOW_FILE_NAME.valueCompat
        if (this.value != value) {
            this.value = value
        }
    }

    fun putValue(value: Boolean) {
        if (pathShowFileNameLiveData.value != null) {
            pathShowFileNameLiveData.putValue(value)
        } else {
            Settings.FILE_LIST_SHOW_FILE_NAME.putValue(value)
        }
    }

    init {
        addSource(Settings.FILE_LIST_SHOW_FILE_NAME) { loadValue() }
        addSource(pathLiveData) { path: Path ->
            if (this::pathShowFileNameLiveData.isInitialized) {
                removeSource(pathShowFileNameLiveData)
            }
            pathShowFileNameLiveData = PathSettings.getFileListShowFileName(path)
            addSource(pathShowFileNameLiveData) { loadValue() }
        }
    }
}
