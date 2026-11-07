/*
 * Custom fork addition: MediaStore-backed media categories for the home
 * screen (File Manager Plus style). Images unifies DCIM, Pictures,
 * Screenshots and every other image on the device into a single listing;
 * documents collects document files by MIME type; recent files lists
 * everything modified in the last 30 days, newest first.
 */

package me.zhanghai.android.files.home

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull

enum class MediaCategory {
    IMAGES, VIDEOS, AUDIO, DOCUMENTS, RECENT
}

class MediaEntry(
    val path: Path,
    val mimeType: MimeType,
    val attributes: BasicFileAttributes
) {
    val name: String
        get() = path.fileName?.toString() ?: ""
}

// Minimal attributes backed by MediaStore columns, so thumbnails can be
// loaded through the same Coil fetcher the file list uses (it keys on the
// path plus last-modified time) without stat-ing every file.
private class MediaAttributes(
    private val entrySize: Long,
    lastModifiedMillis: Long,
    private val key: Any
) : BasicFileAttributes {
    private val time: FileTime = FileTime.fromMillis(lastModifiedMillis)

    override fun lastModifiedTime(): FileTime = time
    override fun lastAccessTime(): FileTime = time
    override fun creationTime(): FileTime = time
    override fun isRegularFile(): Boolean = true
    override fun isDirectory(): Boolean = false
    override fun isSymbolicLink(): Boolean = false
    override fun isOther(): Boolean = false
    override fun size(): Long = entrySize
    override fun fileKey(): Any = key
}

object MediaStoreQueries {
    private const val LIST_LIMIT = 2000
    private const val RECENT_DAYS = 30L

    data class Stats(val totalSize: Long, val count: Int)

    private class QuerySpec(
        val uri: Uri,
        val selection: String?,
        val selectionArgs: Array<String>?
    )

    private fun specFor(category: MediaCategory): QuerySpec =
        when (category) {
            MediaCategory.IMAGES -> QuerySpec(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null
            )
            MediaCategory.VIDEOS -> QuerySpec(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null
            )
            MediaCategory.AUDIO -> QuerySpec(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null
            )
            MediaCategory.DOCUMENTS -> QuerySpec(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.MIME_TYPE} IN " +
                    DOCUMENT_MIME_TYPES.joinToString(",", "(", ")") { "?" },
                DOCUMENT_MIME_TYPES.toTypedArray()
            )
            MediaCategory.RECENT -> QuerySpec(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ? AND " +
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?",
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(),
                    (System.currentTimeMillis() / 1000 - RECENT_DAYS * 24 * 60 * 60)
                        .toString()
                )
            )
        }

    fun queryEntries(context: Context, category: MediaCategory): List<MediaEntry> {
        val spec = specFor(category)
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val entries = mutableListOf<MediaEntry>()
        try {
            context.contentResolver.query(
                spec.uri, projection, spec.selection, spec.selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val mimeColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && entries.size < LIST_LIMIT) {
                    val data = cursor.getString(dataColumn)?.takeIf { it.isNotEmpty() }
                        ?: continue
                    val path = try {
                        Paths.get(data)
                    } catch (e: Exception) {
                        continue
                    }
                    val mimeType = cursor.getString(mimeColumn)?.asMimeTypeOrNull()
                        ?: FALLBACK_MIME_TYPE
                    val size = cursor.getLong(sizeColumn)
                    val modifiedMillis = cursor.getLong(modifiedColumn) * 1000
                    entries += MediaEntry(
                        path, mimeType, MediaAttributes(size, modifiedMillis, data)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return entries
    }

    fun queryStats(context: Context, category: MediaCategory): Stats? {
        val spec = specFor(category)
        var totalSize = 0L
        var count = 0
        try {
            context.contentResolver.query(
                spec.uri, arrayOf(MediaStore.MediaColumns.SIZE),
                spec.selection, spec.selectionArgs, null
            )?.use { cursor ->
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    totalSize += cursor.getLong(sizeColumn)
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        return if (count > 0) Stats(totalSize, count) else null
    }

    private val FALLBACK_MIME_TYPE: MimeType = "application/octet-stream".let {
        it.asMimeTypeOrNull() ?: MimeType.ANY
    }

    private val DOCUMENT_MIME_TYPES = listOf(
        "application/pdf",
        "text/plain",
        "text/csv",
        "application/rtf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/epub+zip"
    )
}
