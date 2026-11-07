/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.home

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.getExternalStorageDirectory
import me.zhanghai.android.files.navigation.standardDirectories
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.valueCompat

/**
 * Launcher home screen with category shortcuts, shown on startup instead
 * of jumping straight into a folder. Media categories (images, videos,
 * audio, documents, recent files) are backed by MediaStore like in File
 * Manager Plus, so e.g. images unifies DCIM, Pictures and Screenshots;
 * internal storage, Downloads, the remaining standard directories and
 * bookmarks open their folders as before.
 */
class HomeActivity : AppActivity() {
    private var downloadsTile: Tile? = null

    // Folder picked from the home menu becomes a bookmark, which the
    // home screen shows as a tile. The bookmark list has no size cap,
    // so any number of folders can be added.
    private val addFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = DirectoryPickerActivity.getResultPath(result.data)
            ?: return@registerForActivityResult
        BookmarkDirectories.add(BookmarkDirectory(null, Paths.get(path)))
        recreate()
    }

    private data class Tile(
        val iconRes: Int,
        val title: String,
        var subtitle: String?,
        val path: Path?,
        val category: MediaCategory? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install()

        if (!Settings.SHOW_HOME_ON_STARTUP.valueCompat) {
            startActivity(
                FileListActivity.createViewIntent(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
            )
            finish()
            return
        }

        setContentView(R.layout.home_activity)
        findViewById<View>(R.id.root_layout).applySystemBarInsetsPadding()
        // Overflow menu like File Manager Plus: settings and refresh
        // reachable straight from the top of the home screen.
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.home_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_folder -> {
                    addFolderLauncher.launch(
                        DirectoryPickerActivity.createIntent(
                            this, DirectoryPickerActivity.MODE_BOOKMARK, null
                        )
                    )
                    true
                }
                R.id.action_settings -> {
                    startActivity(SettingsActivity::class.createIntent())
                    true
                }
                R.id.action_refresh -> {
                    recreate()
                    true
                }
                else -> false
            }
        }
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        val tiles = createTiles()
        val adapter = Adapter(tiles) { tile ->
            val category = tile.category
            if (category != null) {
                startActivity(MediaCategoryActivity.createIntent(this, category))
            } else if (tile.path != null) {
                startActivity(FileListActivity.createViewIntent(tile.path))
            }
        }
        recyclerView.adapter = adapter
        loadTileStats(tiles, adapter)
    }

    // Fills in the "size (count)" subtitles File Manager Plus shows: media
    // stats for the category tiles, and a folder walk for Downloads.
    private fun loadTileStats(tiles: List<Tile>, adapter: Adapter) {
        val statTiles = tiles.filter { it.category != null || it === downloadsTile }
        if (statTiles.isEmpty()) {
            return
        }
        lifecycleScope.launch {
            val subtitles = withContext(Dispatchers.IO) {
                statTiles.map { tile ->
                    val stats = if (tile.category != null) {
                        MediaStoreQueries.queryStats(this@HomeActivity, tile.category)
                    } else {
                        tile.path?.let { folderStats(it) }
                    }
                    stats?.let {
                        getString(
                            R.string.home_category_stats,
                            Formatter.formatFileSize(this@HomeActivity, it.totalSize),
                            "%,d".format(it.count)
                        )
                    }
                }
            }
            statTiles.forEachIndexed { index, tile ->
                tile.subtitle = subtitles[index]
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun folderStats(path: Path): MediaStoreQueries.Stats? {
        val root = try {
            path.toFile()
        } catch (e: Exception) {
            return null
        }
        if (!root.isDirectory) {
            return null
        }
        var totalSize = 0L
        var count = 0
        try {
            root.walkTopDown().forEach { file ->
                if (file.isFile) {
                    totalSize += file.length()
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        return if (count > 0) MediaStoreQueries.Stats(totalSize, count) else null
    }

    private fun createTiles(): List<Tile> {
        val tiles = mutableListOf<Tile>()
        @Suppress("DEPRECATION")
        val externalStoragePath = Environment.getExternalStorageDirectory().absolutePath
        val statFs = try {
            StatFs(externalStoragePath)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        val storageSubtitle = statFs?.let {
            val used = Formatter.formatFileSize(this, it.totalBytes - it.availableBytes)
            val total = Formatter.formatFileSize(this, it.totalBytes)
            getString(R.string.home_storage_used, used, total)
        }
        tiles += Tile(
            R.drawable.device_icon_white_24dp,
            getString(R.string.home_internal_storage),
            storageSubtitle,
            Paths.get(externalStoragePath)
        )
        tiles += categoryTile(
            MediaCategory.IMAGES, R.drawable.image_icon_white_24dp,
            R.string.navigation_standard_directory_pictures
        )
        tiles += categoryTile(
            MediaCategory.VIDEOS, R.drawable.video_icon_white_24dp,
            R.string.navigation_standard_directory_movies
        )
        tiles += categoryTile(
            MediaCategory.AUDIO, R.drawable.audio_icon_white_24dp,
            R.string.navigation_standard_directory_music
        )
        val enabledStandardDirectories = standardDirectories.filter { it.isEnabled }
        // Downloads stays a folder tile, in the position it used to have.
        enabledStandardDirectories.firstOrNull {
            it.relativePath == Environment.DIRECTORY_DOWNLOADS
        }?.let { downloads ->
            val tile = Tile(
                downloads.iconRes,
                downloads.getTitle(this),
                null,
                Paths.get(getExternalStorageDirectory(downloads.relativePath))
            )
            downloadsTile = tile
            tiles += tile
        }
        tiles += categoryTile(
            MediaCategory.DOCUMENTS, R.drawable.document_icon_white_24dp,
            R.string.navigation_standard_directory_documents
        )
        tiles += categoryTile(
            MediaCategory.RECENT, R.drawable.recent_icon_white_24dp,
            R.string.home_recent_files
        )
        // Standard directories already covered by a category or the
        // Downloads tile above are not repeated as folder tiles.
        val coveredRelativePaths = setOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC
        )
        for (standardDirectory in enabledStandardDirectories) {
            if (standardDirectory.relativePath in coveredRelativePaths) {
                continue
            }
            tiles += Tile(
                standardDirectory.iconRes,
                standardDirectory.getTitle(this),
                null,
                Paths.get(getExternalStorageDirectory(standardDirectory.relativePath))
            )
        }
        // Screenshots are part of the images category, so the screenshots
        // bookmark is not shown as its own tile here.
        val screenshotsTitle = getString(R.string.settings_bookmark_directory_screenshots)
        for (bookmarkDirectory in Settings.BOOKMARK_DIRECTORIES.valueCompat) {
            if (bookmarkDirectory.name == screenshotsTitle ||
                bookmarkDirectory.path.fileName?.toString()
                    .equals("Screenshots", ignoreCase = true)
            ) {
                continue
            }
            tiles += Tile(
                R.drawable.directory_icon_white_24dp,
                bookmarkDirectory.name,
                null,
                bookmarkDirectory.path
            )
        }
        return tiles
    }

    private fun categoryTile(
        category: MediaCategory,
        iconRes: Int,
        titleRes: Int
    ): Tile =
        Tile(iconRes, getString(titleRes), null, null, category)

    private class Adapter(
        private val tiles: List<Tile>,
        private val onClick: (Tile) -> Unit
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = parent.context.layoutInflater.inflate(R.layout.home_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tile = tiles[position]
            val context = holder.itemView.context
            holder.iconImage.setImageResource(tile.iconRes)
            holder.iconImage.setColorFilter(context.getColorByAttr(android.R.attr.colorPrimary))
            holder.titleText.text = tile.title
            holder.subtitleText.isVisible = tile.subtitle != null
            holder.subtitleText.text = tile.subtitle
            holder.itemView.setOnClickListener { onClick(tile) }
        }

        override fun getItemCount(): Int = tiles.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
            val titleText: TextView = itemView.findViewById(R.id.titleText)
            val subtitleText: TextView = itemView.findViewById(R.id.subtitleText)
        }
    }
}
