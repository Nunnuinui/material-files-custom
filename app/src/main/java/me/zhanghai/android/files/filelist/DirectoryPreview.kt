/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.util.LruCache
import java8.nio.file.DirectoryIteratorException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.ftp.isFtpPath
import java.io.IOException

/**
 * Finds a representative media file inside a directory so the directory item can show a
 * preview thumbnail of its contents, similar to gallery-style file managers.
 */
object DirectoryPreview {
    private sealed interface Result {
        data class Found(val file: FileItem) : Result
        data object None : Result
    }

    private const val MAX_ENTRIES_TO_SCAN = 60

    private val cache = LruCache<String, Result>(256)
    private val inFlight = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Calls [onReady] with the preview file for [directory], or null if there is none. */
    fun load(directory: FileItem, onReady: (FileItem?) -> Unit) {
        val path = directory.path
        val key = path.toString()
        when (val cached = cache.get(key)) {
            is Result.Found -> {
                onReady(cached.file)
                return
            }
            is Result.None -> {
                onReady(null)
                return
            }
            null -> {}
        }
        synchronized(inFlight) {
            if (!inFlight.add(key)) {
                // Another load for this directory is already running; its result is cached,
                // but this caller would never be notified, so just report no preview for now.
                return
            }
        }
        scope.launch {
            val preview = try {
                findPreview(directory)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            cache.put(key, if (preview != null) Result.Found(preview) else Result.None)
            synchronized(inFlight) { inFlight.remove(key) }
            withContext(Dispatchers.Main) { onReady(preview) }
        }
    }

    private fun findPreview(directory: FileItem): FileItem? {
        val path = directory.path
        // Listing archive contents or remote FTP directories can be very slow; skip those.
        if (path.isArchivePath || path.isFtpPath) {
            return null
        }
        var firstMedia: FileItem? = null
        path.newDirectoryStream().use { directoryStream ->
            var scanned = 0
            for (childPath in directoryStream) {
                if (++scanned > MAX_ENTRIES_TO_SCAN) {
                    break
                }
                val child = try {
                    childPath.loadFileItem()
                } catch (e: DirectoryIteratorException) {
                    e.printStackTrace()
                    continue
                } catch (e: IOException) {
                    e.printStackTrace()
                    continue
                }
                if (child.attributes.isDirectory || child.isHidden || !child.supportsThumbnail) {
                    continue
                }
                if (child.mimeType.isImage) {
                    return child
                }
                if (firstMedia == null) {
                    firstMedia = child
                }
            }
        }
        return firstMedia
    }
}
