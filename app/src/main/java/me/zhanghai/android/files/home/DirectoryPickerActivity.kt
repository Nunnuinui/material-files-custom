/*
 * Custom fork addition: a minimal in-app directory picker used by the
 * media category screen to choose the destination for move/copy, like
 * the picker in File Manager Plus. Browses local storage only and
 * returns the chosen directory's path as the activity result.
 */

package me.zhanghai.android.files.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.layoutInflater

class DirectoryPickerActivity : AppActivity() {
    private var currentDirectory: File =
        @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install()
        setContentView(R.layout.directory_picker_activity)
        findViewById<View>(R.id.root_layout).applySystemBarInsetsPadding()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MOVE
        intent.getStringExtra(EXTRA_START_PATH)?.let { startPath ->
            val start = File(startPath)
            if (start.isDirectory) {
                currentDirectory = start
            }
        }
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(
            when (mode) {
                MODE_COPY -> R.string.directory_picker_copy_title
                MODE_EXTRACT -> R.string.directory_picker_extract_title
                MODE_BOOKMARK -> R.string.directory_picker_bookmark_title
                else -> R.string.directory_picker_move_title
            }
        )
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = Adapter { directory ->
            currentDirectory = directory
            refresh()
        }
        recyclerView.adapter = adapter
        findViewById<MaterialButton>(R.id.confirm_button).apply {
            text = getString(
                when (mode) {
                    MODE_COPY -> R.string.copy
                    MODE_EXTRACT -> R.string.media_extract
                    MODE_BOOKMARK -> R.string.home_add_folder
                    else -> R.string.media_category_move
                }
            )
            setOnClickListener {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_RESULT_PATH, currentDirectory.absolutePath)
                )
                finish()
            }
        }
        refresh()
    }

    private fun refresh() {
        toolbar.subtitle = currentDirectory.absolutePath
        val directories = try {
            currentDirectory.listFiles { file -> file.isDirectory }?.toList()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        adapter.directories = directories.sortedBy { it.name.lowercase() }
        adapter.notifyDataSetChanged()
    }

    private fun navigateUp(): Boolean {
        val parent = currentDirectory.parentFile ?: return false
        currentDirectory = parent
        refresh()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (!navigateUp()) {
                finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!navigateUp()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private class Adapter(
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        var directories: List<File> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = parent.context.layoutInflater.inflate(
                R.layout.directory_picker_item, parent, false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val directory = directories[position]
            val context = holder.itemView.context
            holder.iconImage.setColorFilter(
                context.getColorByAttr(android.R.attr.colorPrimary)
            )
            holder.nameText.text = directory.name
            holder.itemView.setOnClickListener { onClick(directory) }
        }

        override fun getItemCount(): Int = directories.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconImage: ImageView = itemView.findViewById(R.id.icon_image)
            val nameText: TextView = itemView.findViewById(R.id.name_text)
        }
    }

    companion object {
        const val MODE_MOVE = "move"
        const val MODE_COPY = "copy"
        const val MODE_EXTRACT = "extract"
        const val MODE_BOOKMARK = "bookmark"

        private val EXTRA_MODE = "${DirectoryPickerActivity::class.java.name}.extra.MODE"
        private val EXTRA_START_PATH =
            "${DirectoryPickerActivity::class.java.name}.extra.START_PATH"
        private val EXTRA_RESULT_PATH =
            "${DirectoryPickerActivity::class.java.name}.extra.RESULT_PATH"

        fun createIntent(context: Context, mode: String, startPath: String?): Intent =
            Intent(context, DirectoryPickerActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_START_PATH, startPath)
            }

        fun getResultPath(data: Intent?): String? =
            data?.getStringExtra(EXTRA_RESULT_PATH)
    }
}
