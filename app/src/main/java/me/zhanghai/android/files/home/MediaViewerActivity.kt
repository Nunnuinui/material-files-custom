/*
 * Custom fork addition: a File Manager Plus style media viewer.
 * Images and videos open here first — a full-screen pager with the
 * position and file name on top and share / rotate / delete / info /
 * more actions at the bottom. Videos show a poster frame with a big
 * play button; tapping it hands over to the built-in player, which
 * plays the video (with the other videos as its playlist).
 */

package me.zhanghai.android.files.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.player.PlayerActivity
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser

class MediaViewerActivity : AppActivity() {
    data class Entry(val path: Path, val mimeType: MimeType)

    private lateinit var pager: ViewPager2
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var positionText: TextView
    private lateinit var nameText: TextView
    private lateinit var rotateAction: View
    private lateinit var adapter: PagerAdapter

    private val entries = mutableListOf<Entry>()
    private val rotations = mutableMapOf<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install()
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES) ?: arrayListOf()
        entries += paths.mapIndexed { index, pathString ->
            Entry(
                Paths.get(pathString),
                mimes.getOrNull(index)?.asMimeTypeOrNull() ?: MimeType.ANY
            )
        }
        if (entries.isEmpty()) {
            finish()
            return
        }
        setContentView(R.layout.media_viewer_activity)
        topBar = findViewById(R.id.top_bar)
        topBar.applySystemBarInsetsPadding()
        bottomBar = findViewById(R.id.bottom_bar)
        bottomBar.applySystemBarInsetsPadding()
        positionText = findViewById(R.id.position_text)
        nameText = findViewById(R.id.name_text)
        pager = findViewById(R.id.pager)
        adapter = PagerAdapter()
        pager.adapter = adapter
        val start = intent.getIntExtra(EXTRA_POSITION, 0)
            .coerceIn(0, entries.size - 1)
        pager.setCurrentItem(start, false)
        pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateChrome()
                }
            }
        )
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.action_share).setOnClickListener { shareCurrent() }
        rotateAction = findViewById(R.id.action_rotate)
        rotateAction.setOnClickListener { rotateCurrent() }
        findViewById<View>(R.id.action_delete).setOnClickListener {
            confirmDeleteCurrent()
        }
        findViewById<View>(R.id.action_info).setOnClickListener { showInfo() }
        findViewById<View>(R.id.action_more).setOnClickListener { showMore(it) }
        updateChrome()
    }

    private fun currentEntry(): Entry? = entries.getOrNull(pager.currentItem)

    private fun updateChrome() {
        val entry = currentEntry() ?: return
        positionText.text = "${pager.currentItem + 1}/${entries.size}"
        nameText.text = entry.path.fileName?.toString() ?: ""
        rotateAction.isVisible = entry.mimeType.isImage
    }

    private fun toggleChrome() {
        val show = !topBar.isVisible
        topBar.isVisible = show
        bottomBar.isVisible = show
    }

    private fun rotateCurrent() {
        val entry = currentEntry() ?: return
        if (!entry.mimeType.isImage) {
            return
        }
        val key = entry.path.toString()
        rotations[key] = ((rotations[key] ?: 0) + 90) % 360
        adapter.notifyItemChanged(pager.currentItem)
    }

    private fun shareCurrent() {
        val entry = currentEntry() ?: return
        val intent = listOf(entry.path.fileProviderUri)
            .createSendStreamIntent(listOf(entry.mimeType))
            .withChooser()
        startActivitySafe(intent)
    }

    private fun confirmDeleteCurrent() {
        val entry = currentEntry() ?: return
        val index = pager.currentItem
        MaterialAlertDialogBuilder(this)
            .setMessage(
                getString(
                    R.string.media_viewer_delete_message,
                    entry.path.fileName?.toString() ?: ""
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                FileJobService.delete(listOf(entry.path), this)
                entries.removeAt(index)
                rotations.remove(entry.path.toString())
                if (entries.isEmpty()) {
                    finish()
                } else {
                    adapter.notifyDataSetChanged()
                    if (pager.currentItem >= entries.size) {
                        pager.setCurrentItem(entries.size - 1, false)
                    }
                    updateChrome()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInfo() {
        val entry = currentEntry() ?: return
        val attributes = try {
            Files.readAttributes(entry.path, BasicFileAttributes::class.java)
        } catch (e: Exception) {
            null
        }
        val message = buildString {
            append(entry.path.fileName?.toString() ?: "").append('\n')
            append(entry.path.toString()).append('\n')
            append(entry.mimeType.value).append('\n')
            if (attributes != null) {
                append(
                    Formatter.formatFileSize(
                        this@MediaViewerActivity, attributes.size()
                    )
                ).append('\n')
                append(
                    DateFormat.format(
                        "yyyy/MM/dd HH:mm",
                        attributes.lastModifiedTime().toMillis()
                    )
                )
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.media_viewer_info)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showMore(anchor: View) {
        val entry = currentEntry() ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_OPEN_WITH, 0, R.string.media_category_open_with)
        if (entry.mimeType.isVideo) {
            popup.menu.add(0, MENU_PLAYER, 1, R.string.media_viewer_play_with_player)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN_WITH -> {
                    val intent = entry.path.fileProviderUri
                        .createViewIntent(entry.mimeType)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        .withChooser()
                    startActivitySafe(intent)
                    true
                }
                MENU_PLAYER -> {
                    openPlayer(entry)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openPlayer(entry: Entry) {
        val videos = entries.filter { it.mimeType.isVideo }
        val position = videos.indexOf(entry)
        if (position == -1) {
            return
        }
        val (window, windowPosition) = windowed(
            videos.map { it.path }, position, PATH_LIST_SIZE_MAX
        )
        startActivitySafe(
            PlayerActivity.createIntent(window, windowPosition, entry.mimeType)
        )
    }

    private inner class PagerAdapter :
        RecyclerView.Adapter<PagerAdapter.Holder>() {
        override fun getItemViewType(position: Int): Int =
            if (entries[position].mimeType.isVideo) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = parent.context.layoutInflater.inflate(
                R.layout.media_viewer_page, parent, false
            )
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            if (entry.mimeType.isVideo) {
                holder.imageView.isVisible = false
                holder.posterImage.isVisible = true
                holder.playContainer.isVisible = true
                val attributes = try {
                    Files.readAttributes(entry.path, BasicFileAttributes::class.java)
                } catch (e: Exception) {
                    null
                }
                if (attributes != null) {
                    holder.posterImage.load(entry.path to attributes)
                } else {
                    holder.posterImage.load(entry.path.fileProviderUri)
                }
                holder.playContainer.setOnClickListener { openPlayer(entry) }
                holder.posterImage.setOnClickListener { toggleChrome() }
            } else {
                holder.posterImage.isVisible = false
                holder.playContainer.isVisible = false
                holder.imageView.isVisible = true
                holder.imageView.setOrientation(
                    rotations[entry.path.toString()] ?: 0
                )
                holder.imageView.setImage(
                    ImageSource.uri(entry.path.fileProviderUri)
                )
                holder.imageView.setOnClickListener { toggleChrome() }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            try {
                holder.imageView.recycle()
            } catch (e: Exception) {
                // The image view may not have loaded an image yet.
            }
        }

        override fun getItemCount(): Int = entries.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: SubsamplingScaleImageView =
                view.findViewById(R.id.image_view)
            val posterImage: ImageView = view.findViewById(R.id.poster_image)
            val playContainer: View = view.findViewById(R.id.play_container)
        }
    }

    companion object {
        private const val PATH_LIST_SIZE_MAX = 1000
        private const val MENU_OPEN_WITH = 1
        private const val MENU_PLAYER = 2
        private val EXTRA_PATHS =
            "${MediaViewerActivity::class.java.name}.extra.PATHS"
        private val EXTRA_MIMES =
            "${MediaViewerActivity::class.java.name}.extra.MIMES"
        private val EXTRA_POSITION =
            "${MediaViewerActivity::class.java.name}.extra.POSITION"

        private fun <T> windowed(
            list: List<T>, position: Int, max: Int
        ): Pair<List<T>, Int> {
            if (list.size <= max) {
                return list to position
            }
            val start = (position - max / 2).coerceIn(0, list.size - max)
            return list.subList(start, start + max) to (position - start)
        }

        fun createIntent(
            context: Context,
            entries: List<Pair<Path, MimeType>>,
            position: Int
        ): Intent {
            val (window, windowPosition) = windowed(
                entries, position, PATH_LIST_SIZE_MAX
            )
            return Intent(context, MediaViewerActivity::class.java).apply {
                putStringArrayListExtra(
                    EXTRA_PATHS,
                    ArrayList(window.map { it.first.toString() })
                )
                putStringArrayListExtra(
                    EXTRA_MIMES,
                    ArrayList(window.map { it.second.value })
                )
                putExtra(EXTRA_POSITION, windowPosition)
            }
        }
    }
}
