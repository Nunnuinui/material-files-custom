/*
 * Custom fork addition: browses one MediaStore-backed media category
 * from the home screen (images / videos / audio / documents / recent
 * files). For images, videos, audio and documents the first level is
 * a folder (album) grid like File Manager Plus shows: one tile per
 * containing folder with a cover thumbnail and the item count;
 * tapping a folder drills into its files. Recent files are one flat
 * list.
 * The toolbar offers filename search (with saved history) and a view /
 * sort settings sheet (list or grid, grid columns, file name visibility,
 * sort field and order, optionally per folder), mirroring File Manager
 * Plus. Files open exactly like in the file list: images in the built-in
 * viewer, audio and video in the built-in player with the visible files
 * as the playlist, and everything else through a view intent.
 * Long-pressing files starts a selection mode with copy / move /
 * rename / delete, carried out by the app's file job service just like
 * in the folder view.
 */

package me.zhanghai.android.files.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.file.lastModifiedInstant
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.isArchiveFile
import me.zhanghai.android.files.player.PlayerActivity
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.image.ImageViewerActivity

class MediaCategoryActivity : AppActivity() {
    private lateinit var category: MediaCategory
    private lateinit var fileAdapter: Adapter
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var historyList: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var selectionBar: View
    private lateinit var renameAction: View
    private lateinit var toolbarView: MaterialToolbar
    private lateinit var searchBar: View
    private lateinit var searchEdit: EditText
    private lateinit var searchClear: View

    private var grouped = false
    private var allEntries: List<MediaEntry> = emptyList()
    private var currentFolder: String? = null
    private var searching = false
    private var applyAllFolders = true

    private val viewPrefs by lazy {
        getSharedPreferences(PREFS_VIEW, Context.MODE_PRIVATE)
    }
    private val searchPrefs by lazy {
        getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
    }

    private val selectedPaths = linkedSetOf<String>()
    private val selectedFolders = linkedSetOf<String>()
    private var pendingSources: List<Path> = emptyList()
    private var needsRefresh = false

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            reloadEntries()
        }
    }

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val targetPath = DirectoryPickerActivity.getResultPath(result.data)
        if (result.resultCode != Activity.RESULT_OK || targetPath == null) {
            return@registerForActivityResult
        }
        val sources = pendingSources
        if (sources.isEmpty()) {
            return@registerForActivityResult
        }
        when (pendingPickerMode) {
            DirectoryPickerActivity.MODE_COPY ->
                FileJobService.copy(sources, Paths.get(targetPath), this)
            DirectoryPickerActivity.MODE_EXTRACT ->
                extractArchives(sources, Paths.get(targetPath))
            else ->
                FileJobService.move(sources, Paths.get(targetPath), this)
        }
        clearSelection()
        clearFolderSelection()
        needsRefresh = true
        scheduleReload()
    }
    private var pendingPickerMode: String = DirectoryPickerActivity.MODE_MOVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install()
        category = MediaCategory.valueOf(
            intent.getStringExtra(EXTRA_CATEGORY) ?: MediaCategory.IMAGES.name
        )
        grouped = category != MediaCategory.RECENT
        setContentView(R.layout.media_category_activity)
        findViewById<View>(R.id.root_layout).applySystemBarInsetsPadding()
        toolbarView = findViewById(R.id.toolbar)
        setSupportActionBar(toolbarView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(titleRes(category))
        // Step back one level at a time (selection, search, then folder
        // contents back to the folder list) instead of leaving at once.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!navigateUp()) {
                        finish()
                    }
                }
            }
        )

        recyclerView = findViewById(R.id.recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        emptyText = findViewById(R.id.empty_text)
        selectionBar = findViewById(R.id.selection_bar)
        layoutManager = GridLayoutManager(this, fileSpanCount())
        recyclerView.layoutManager = layoutManager
        fileAdapter = Adapter(
            category,
            thumbnailsOnly = category == MediaCategory.IMAGES,
            useListLayout = fileSpanCount() == 1,
            selectedPaths = selectedPaths,
            onClick = { entry -> onEntryClicked(entry) },
            onLongClick = { entry -> onEntryLongClicked(entry) }
        )
        folderAdapter = FolderAdapter(
            category,
            selectedFolders = selectedFolders,
            onClick = { folder -> onFolderClicked(folder) },
            onLongClick = { folder -> toggleFolderSelection(folder) }
        )

        historyList = findViewById(R.id.history_list)
        historyList.layoutManager = LinearLayoutManager(this)
        historyAdapter = HistoryAdapter(
            onClick = { item -> onHistoryClicked(item) },
            onRemove = { item -> removeHistory(item) }
        )
        historyList.adapter = historyAdapter

        searchBar = findViewById(R.id.search_bar)
        searchEdit = findViewById(R.id.search_edit)
        searchClear = findViewById(R.id.search_clear)
        findViewById<View>(R.id.search_back).setOnClickListener { exitSearchMode() }
        searchClear.setOnClickListener { searchEdit.text?.clear() }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchClear.isVisible = !s.isNullOrEmpty()
                if (searching) {
                    render()
                }
            }
        })
        searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                saveHistory(searchQueryText())
                hideKeyboard()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.action_copy).setOnClickListener {
            launchPicker(DirectoryPickerActivity.MODE_COPY)
        }
        findViewById<View>(R.id.action_move).setOnClickListener {
            launchPicker(DirectoryPickerActivity.MODE_MOVE)
        }
        renameAction = findViewById(R.id.action_rename)
        renameAction.setOnClickListener {
            showRenameDialog()
        }
        findViewById<View>(R.id.action_delete).setOnClickListener {
            confirmDelete()
        }
        findViewById<View>(R.id.action_more).setOnClickListener { anchor ->
            showMoreMenu(anchor)
        }
        val iconColor = getColorByAttr(android.R.attr.colorControlNormal)
        listOf(
            R.id.action_copy_icon, R.id.action_move_icon, R.id.action_rename_icon,
            R.id.action_delete_icon, R.id.action_more_icon
        ).forEach { iconId ->
            findViewById<ImageView>(iconId).setColorFilter(iconColor)
        }

        reloadEntries()
    }

    override fun onStart() {
        super.onStart()
        val resolver = contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
        resolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), true, mediaObserver
        )
    }

    override fun onResume() {
        super.onResume()
        if (needsRefresh) {
            needsRefresh = false
            reloadEntries()
        }
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(mediaObserver)
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.media_category_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (!navigateUp()) {
                    finish()
                }
                return true
            }
            R.id.action_search -> {
                enterSearchMode()
                return true
            }
            R.id.action_view_sort -> {
                showViewSettingsSheet()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // Returns true when the press was consumed inside this screen.
    private fun navigateUp(): Boolean {
        if (selectedPaths.isNotEmpty()) {
            clearSelection()
            return true
        }
        if (selectedFolders.isNotEmpty()) {
            clearFolderSelection()
            return true
        }
        if (searching) {
            exitSearchMode()
            return true
        }
        if (grouped && currentFolder != null) {
            currentFolder = null
            render()
            return true
        }
        return false
    }

    private fun fileSpanCount(): Int =
        when (category) {
            MediaCategory.IMAGES -> 4
            MediaCategory.VIDEOS -> 3
            else -> 1
        }

    // -- View settings -------------------------------------------------

    private data class ViewSettings(
        var sortField: Int = SORT_DATE,
        var sortAscending: Boolean = false,
        var listMode: Boolean = false,
        var gridColumns: Int = 3,
        var showNames: Boolean = true,
        var showHidden: Boolean = false
    )

    private fun defaultSettings(): ViewSettings =
        ViewSettings(
            sortField = SORT_DATE,
            sortAscending = false,
            listMode = fileSpanCount() == 1,
            gridColumns = when (category) {
                MediaCategory.IMAGES -> 4
                MediaCategory.VIDEOS -> 3
                else -> 3
            },
            showNames = category != MediaCategory.IMAGES
        )

    private fun categoryScope(): String = "cat_" + category.name

    private fun currentScope(): String =
        if (grouped && currentFolder != null && !applyAllFolders) {
            categoryScope() + "|" + currentFolder
        } else {
            categoryScope()
        }

    private fun loadSettings(): ViewSettings {
        applyAllFolders = viewPrefs.getBoolean(categoryScope() + ":applyAll", true)
        var settings = readScope(categoryScope()) ?: defaultSettings()
        if (grouped && currentFolder != null && !applyAllFolders) {
            readScope(categoryScope() + "|" + currentFolder)?.let { settings = it }
        }
        return settings
    }

    private fun readScope(scope: String): ViewSettings? {
        if (!viewPrefs.contains(scope + ":sort")) {
            return null
        }
        val defaults = defaultSettings()
        return ViewSettings(
            sortField = viewPrefs.getInt(scope + ":sort", defaults.sortField),
            sortAscending = viewPrefs.getBoolean(scope + ":asc", defaults.sortAscending),
            listMode = viewPrefs.getBoolean(scope + ":list", defaults.listMode),
            gridColumns = viewPrefs.getInt(scope + ":cols", defaults.gridColumns),
            showNames = viewPrefs.getBoolean(scope + ":names", defaults.showNames),
            showHidden = viewPrefs.getBoolean(scope + ":hidden", defaults.showHidden)
        )
    }

    private fun saveSettings(settings: ViewSettings) {
        val editor = viewPrefs.edit()
        editor.putBoolean(categoryScope() + ":applyAll", applyAllFolders)
        val scope = currentScope()
        editor.putInt(scope + ":sort", settings.sortField)
            .putBoolean(scope + ":asc", settings.sortAscending)
            .putBoolean(scope + ":list", settings.listMode)
            .putInt(scope + ":cols", settings.gridColumns)
            .putBoolean(scope + ":names", settings.showNames)
            .putBoolean(scope + ":hidden", settings.showHidden)
        if (applyAllFolders) {
            // Shared settings win: drop the per-folder overrides.
            for (key in viewPrefs.all.keys) {
                if (key.startsWith(categoryScope() + "|")) {
                    editor.remove(key)
                }
            }
        }
        editor.apply()
    }

    private fun sortEntries(
        entries: List<MediaEntry>, settings: ViewSettings
    ): List<MediaEntry> {
        val comparator: Comparator<MediaEntry> = when (settings.sortField) {
            SORT_NAME -> compareBy { it.name.lowercase() }
            SORT_SIZE -> compareBy { it.attributes.size() }
            SORT_TYPE -> compareBy<MediaEntry> { it.mimeType.value.lowercase() }
                .thenBy { it.name.lowercase() }
            else -> compareBy { it.attributes.lastModifiedInstant.toEpochMilli() }
        }
        return entries.sortedWith(
            if (settings.sortAscending) comparator else comparator.reversed()
        )
    }

    private fun sortFolders(
        folders: List<FolderInfo>, settings: ViewSettings
    ): List<FolderInfo> {
        val comparator: Comparator<FolderInfo> = when (settings.sortField) {
            SORT_NAME, SORT_TYPE -> compareBy { it.name.lowercase() }
            SORT_SIZE -> compareBy { it.totalSize }
            else -> compareBy { it.latestModified }
        }
        return folders.sortedWith(
            if (settings.sortAscending) comparator else comparator.reversed()
        )
    }

    private fun sortFieldRes(field: Int): Int =
        when (field) {
            SORT_NAME -> R.string.media_sort_name
            SORT_SIZE -> R.string.media_sort_size
            SORT_TYPE -> R.string.media_sort_type
            else -> R.string.media_sort_date
        }

    private fun sortLabel(settings: ViewSettings): String =
        getString(sortFieldRes(settings.sortField)) +
            if (settings.sortAscending) " ▲" else " ▼"

    private fun showViewSettingsSheet() {
        val settings = loadSettings()
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.media_view_settings_sheet, null)
        val applyAllBox = sheet.findViewById<CheckBox>(R.id.apply_all_checkbox)
        val radioList = sheet.findViewById<RadioButton>(R.id.radio_list)
        val radioGrid = sheet.findViewById<RadioButton>(R.id.radio_grid)
        val columnsRow = sheet.findViewById<View>(R.id.columns_row)
        val columnsValue = sheet.findViewById<TextView>(R.id.columns_value)
        val namesBox = sheet.findViewById<CheckBox>(R.id.show_names_checkbox)
        val sortValue = sheet.findViewById<TextView>(R.id.sort_value)

        applyAllBox.isChecked = applyAllFolders
        if (settings.listMode) {
            radioList.isChecked = true
        } else {
            radioGrid.isChecked = true
        }
        columnsValue.text = settings.gridColumns.toString()
        namesBox.isChecked = settings.showNames
        val hiddenBox = sheet.findViewById<CheckBox>(R.id.show_hidden_checkbox)
        hiddenBox.isChecked = settings.showHidden
        sortValue.text = sortLabel(settings)
        fun refreshColumnsRow() {
            columnsRow.isVisible = !settings.listMode
        }
        refreshColumnsRow()

        applyAllBox.setOnCheckedChangeListener { _, checked ->
            applyAllFolders = checked
            saveSettings(settings)
            render()
        }
        radioList.setOnCheckedChangeListener { _, checked ->
            if (checked && !settings.listMode) {
                settings.listMode = true
                saveSettings(settings)
                render()
                refreshColumnsRow()
            }
        }
        radioGrid.setOnCheckedChangeListener { _, checked ->
            if (checked && settings.listMode) {
                settings.listMode = false
                saveSettings(settings)
                render()
                refreshColumnsRow()
            }
        }
        sheet.findViewById<View>(R.id.columns_minus).setOnClickListener {
            if (settings.gridColumns > 2) {
                settings.gridColumns--
                columnsValue.text = settings.gridColumns.toString()
                saveSettings(settings)
                render()
            }
        }
        sheet.findViewById<View>(R.id.columns_plus).setOnClickListener {
            if (settings.gridColumns < 5) {
                settings.gridColumns++
                columnsValue.text = settings.gridColumns.toString()
                saveSettings(settings)
                render()
            }
        }
        namesBox.setOnCheckedChangeListener { _, checked ->
            settings.showNames = checked
            saveSettings(settings)
            render()
        }
        hiddenBox.setOnCheckedChangeListener { _, checked ->
            settings.showHidden = checked
            saveSettings(settings)
            render()
        }
        sortValue.setOnClickListener {
            showSortDialog(settings) { sortValue.text = sortLabel(settings) }
        }
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun showSortDialog(settings: ViewSettings, onPicked: () -> Unit) {
        val fields = intArrayOf(SORT_NAME, SORT_SIZE, SORT_DATE, SORT_TYPE)
        val labels = ArrayList<CharSequence>()
        for (field in fields) {
            labels.add(getString(sortFieldRes(field)) + " ▲")
            labels.add(getString(sortFieldRes(field)) + " ▼")
        }
        val checked = fields.indexOf(settings.sortField) * 2 +
            if (settings.sortAscending) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.media_sort_dialog_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                settings.sortField = fields[which / 2]
                settings.sortAscending = which % 2 == 0
                saveSettings(settings)
                render()
                onPicked()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -- Search ----------------------------------------------------------

    private fun searchQueryText(): String =
        if (searching) searchEdit.text?.toString()?.trim().orEmpty() else ""

    private fun enterSearchMode() {
        if (searching) {
            return
        }
        searching = true
        clearSelection()
        refreshHistory()
        toolbarView.isVisible = false
        searchBar.isVisible = true
        render()
        searchEdit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        searchEdit.post {
            imm.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun exitSearchMode() {
        if (!searching) {
            return
        }
        searching = false
        hideKeyboard()
        searchEdit.text?.clear()
        searchBar.isVisible = false
        toolbarView.isVisible = true
        render()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEdit.windowToken, 0)
    }

    private fun loadHistory(): List<String> =
        searchPrefs.getString(KEY_HISTORY, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun refreshHistory() {
        historyAdapter.items = loadHistory()
        historyAdapter.notifyDataSetChanged()
    }

    private fun saveHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val items = loadHistory().toMutableList()
        items.removeAll { it.equals(trimmed, ignoreCase = true) }
        items.add(0, trimmed)
        searchPrefs.edit()
            .putString(KEY_HISTORY, items.take(HISTORY_MAX).joinToString("\n"))
            .apply()
        refreshHistory()
    }

    private fun removeHistory(item: String) {
        val items = loadHistory().toMutableList()
        items.remove(item)
        searchPrefs.edit().putString(KEY_HISTORY, items.joinToString("\n")).apply()
        refreshHistory()
        render()
    }

    private fun onHistoryClicked(item: String) {
        searchEdit.setText(item)
        searchEdit.setSelection(item.length)
        saveHistory(item)
    }

    // -- Rendering -------------------------------------------------------

    private fun reloadEntries() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                MediaStoreQueries.queryEntries(this@MediaCategoryActivity, category)
            }
            allEntries = entries
            // Drop selections for files that no longer exist here.
            val present = entries.mapTo(HashSet()) { it.path.toString() }
            if (selectedPaths.retainAll(present) && selectedPaths.isEmpty()) {
                updateSelectionUi()
            }
            render()
            progressBar.isVisible = false
        }
    }

    private fun render() {
        val settings = loadSettings()
        val visible = displayEntries(settings)
        if (searching) {
            val query = searchQueryText()
            val results = if (query.isEmpty()) {
                visible
            } else {
                visible.filter { it.name.contains(query, ignoreCase = true) }
            }
            applyFileView(settings)
            fileAdapter.entries = sortEntries(results, settings)
            fileAdapter.notifyDataSetChanged()
            if (recyclerView.adapter !== fileAdapter) {
                recyclerView.adapter = fileAdapter
            }
            layoutManager.spanCount =
                if (settings.listMode) 1 else settings.gridColumns
            val showHistory = query.isEmpty() && historyAdapter.items.isNotEmpty()
            historyList.isVisible = showHistory
            recyclerView.isVisible = !showHistory
            emptyText.isVisible = !showHistory && results.isEmpty()
            if (selectedPaths.isNotEmpty()) {
                updateSelectionUi()
            }
            return
        }
        historyList.isVisible = false
        recyclerView.isVisible = true
        if (grouped && currentFolder == null) {
            folderAdapter.useListLayout = settings.listMode
            folderAdapter.hiddenKeys = hiddenFolderKeys()
            folderAdapter.showHidden = settings.showHidden
            folderAdapter.folders = sortFolders(buildFolders(visible), settings)
            val folderKeys = folderAdapter.folders.mapTo(HashSet()) { it.key }
            if (!folderKeys.containsAll(selectedFolders)) {
                selectedFolders.retainAll(folderKeys)
                updateSelectionUi()
            }
            folderAdapter.notifyDataSetChanged()
            if (recyclerView.adapter !== folderAdapter) {
                recyclerView.adapter = folderAdapter
            }
            layoutManager.spanCount = if (settings.listMode) 1 else 3
            title = getString(titleRes(category))
            emptyText.isVisible = visible.isEmpty()
            recyclerView.scrollToPosition(0)
            if (selectedFolders.isNotEmpty()) {
                updateSelectionUi()
            }
            return
        }
        var entries = if (grouped) {
            visible.filter { folderKey(it) == currentFolder }
        } else {
            visible
        }
        if (grouped && entries.isEmpty() && visible.isNotEmpty()) {
            // The open folder lost all its files (moved or deleted).
            currentFolder = null
            render()
            return
        }
        applyFileView(settings)
        fileAdapter.entries = sortEntries(entries, settings)
        fileAdapter.notifyDataSetChanged()
        if (recyclerView.adapter !== fileAdapter) {
            recyclerView.adapter = fileAdapter
        }
        layoutManager.spanCount =
            if (settings.listMode) 1 else settings.gridColumns
        title = currentBaseTitle()
        emptyText.isVisible = entries.isEmpty()
        recyclerView.scrollToPosition(0)
        if (selectedPaths.isNotEmpty()) {
            updateSelectionUi()
        }
    }

    private fun applyFileView(settings: ViewSettings) {
        fileAdapter.useListLayout = settings.listMode
        fileAdapter.thumbnailsOnly = !settings.showNames
    }

    private fun currentBaseTitle(): String =
        if (grouped && currentFolder != null) {
            folderName(currentFolder!!)
        } else {
            getString(titleRes(category))
        }

    private fun folderKey(entry: MediaEntry): String =
        entry.path.parent?.toString() ?: entry.path.toString()

    private fun folderName(key: String): String {
        if (key == PRIMARY_STORAGE_ROOT) {
            return getString(R.string.home_internal_storage)
        }
        return Paths.get(key).fileName?.toString() ?: key
    }

    private fun buildFolders(entries: List<MediaEntry>): List<FolderInfo> {
        // Entries arrive newest first, so first-appearance order is the
        // order of each folder's newest file, and the first entry of a
        // folder is its cover.
        val byFolder = linkedMapOf<String, MutableList<MediaEntry>>()
        for (entry in entries) {
            byFolder.getOrPut(folderKey(entry)) { mutableListOf() }.add(entry)
        }
        return byFolder.map { (key, files) ->
            FolderInfo(
                key = key,
                name = folderName(key),
                count = files.size,
                cover = files.first(),
                totalSize = files.sumOf { it.attributes.size() },
                latestModified = files.maxOf {
                    it.attributes.lastModifiedInstant.toEpochMilli()
                }
            )
        }
    }

    private fun hiddenFolderKeys(): Set<String> =
        viewPrefs.getStringSet(HIDDEN_KEY_PREFIX + category.name, emptySet())
            ?.toSet() ?: emptySet()

    private fun isHiddenEntry(entry: MediaEntry, hiddenKeys: Set<String>): Boolean {
        if (folderKey(entry) in hiddenKeys) {
            return true
        }
        // Dot-files and anything inside a dot-folder count as hidden.
        // Note: this app's Path implementation (ByteStringListPath)
        // throws on iteration, so inspect the path string instead.
        return entry.path.toString().split('/').any { it.startsWith(".") }
    }

    private fun displayEntries(settings: ViewSettings): List<MediaEntry> {
        if (settings.showHidden) {
            return allEntries
        }
        val hiddenKeys = hiddenFolderKeys()
        return allEntries.filterNot { isHiddenEntry(it, hiddenKeys) }
    }

    private fun hideFolders(keys: Collection<String>) {
        val updated = hiddenFolderKeys() + keys
        viewPrefs.edit()
            .putStringSet(HIDDEN_KEY_PREFIX + category.name, updated)
            .apply()
    }

    // File jobs finish asynchronously and the media store is rescanned
    // afterwards, so reload once more a little later to catch the result.
    private fun scheduleReload() {
        Handler(Looper.getMainLooper()).postDelayed({ reloadEntries() }, 2000)
    }

    // -- Selection and file actions --------------------------------------

    private fun onFolderClicked(folder: FolderInfo) {
        if (selectedFolders.isNotEmpty()) {
            toggleFolderSelection(folder)
        } else {
            currentFolder = folder.key
            render()
        }
    }

    private fun toggleFolderSelection(folder: FolderInfo) {
        if (!selectedFolders.add(folder.key)) {
            selectedFolders.remove(folder.key)
        }
        folderAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun clearFolderSelection() {
        if (selectedFolders.isEmpty()) {
            return
        }
        selectedFolders.clear()
        folderAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun onEntryClicked(entry: MediaEntry) {
        if (selectedPaths.isNotEmpty()) {
            toggleSelection(entry)
        } else {
            openEntry(entry)
        }
    }

    private fun onEntryLongClicked(entry: MediaEntry) {
        toggleSelection(entry)
    }

    private fun toggleSelection(entry: MediaEntry) {
        val key = entry.path.toString()
        if (!selectedPaths.add(key)) {
            selectedPaths.remove(key)
        }
        fileAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun clearSelection() {
        if (selectedPaths.isEmpty()) {
            return
        }
        selectedPaths.clear()
        fileAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val selecting = selectedPaths.isNotEmpty() || selectedFolders.isNotEmpty()
        selectionBar.isVisible = selecting
        title = when {
            selectedPaths.isNotEmpty() -> getString(
                R.string.media_category_selected_format,
                selectedPaths.size, fileAdapter.entries.size
            )
            selectedFolders.isNotEmpty() -> getString(
                R.string.media_category_selected_format,
                selectedFolders.size, folderAdapter.folders.size
            )
            else -> currentBaseTitle()
        }
        // Rename only makes sense for a single item, like in File Manager Plus.
        val single = selectedPaths.size + selectedFolders.size == 1
        if (::renameAction.isInitialized) {
            renameAction.isEnabled = single
            renameAction.alpha = if (single) 1f else 0.4f
        }
    }

    private fun selectedEntries(): List<MediaEntry> =
        fileAdapter.entries.filter { it.path.toString() in selectedPaths }

    private fun showRenameDialog() {
        val folderMode = selectedFolders.isNotEmpty()
        val targetPath: Path
        val currentName: String
        if (folderMode) {
            val key = selectedFolders.singleOrNull() ?: return
            targetPath = Paths.get(key)
            currentName = folderName(key)
        } else {
            val entry = selectedEntries().singleOrNull() ?: return
            targetPath = entry.path
            currentName = entry.name
        }
        val editText = EditText(this).apply {
            setText(currentName)
            setSingleLine(true)
            val dotIndex = currentName.lastIndexOf('.')
            if (!folderMode && dotIndex > 0) {
                setSelection(0, dotIndex)
            } else {
                selectAll()
            }
        }
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            setPadding(
                (20 * density).toInt(), (8 * density).toInt(),
                (20 * density).toInt(), 0
            )
            addView(editText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(R.string.rename) { _, _ ->
                val fileName = editText.text.toString().asFileNameOrNull()
                if (fileName == null) {
                    Toast.makeText(
                        this, R.string.file_list_create_file_name_error_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                FileJobService.rename(targetPath, fileName.value, this)
                clearSelection()
                clearFolderSelection()
                needsRefresh = true
                scheduleReload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        if (selectedFolders.isNotEmpty()) {
            showFolderMoreMenu(anchor)
            return
        }
        val single = selectedPaths.size == 1
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SHARE, 0, R.string.share)
        popup.menu.add(0, MENU_OPEN_WITH, 1, R.string.media_category_open_with)
            .isEnabled = single
        popup.menu.add(0, MENU_PROPERTIES, 2, R.string.media_category_properties)
            .isEnabled = single
        val selected = selectedEntries()
        val allArchives = selected.isNotEmpty() && selected.all {
            it.path.isArchiveFile(it.mimeType)
        }
        popup.menu.add(0, MENU_EXTRACT, 3, R.string.media_extract)
            .isEnabled = allArchives
        popup.menu.add(0, MENU_ARCHIVE, 4, R.string.media_archive_title)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> {
                    shareSelected()
                    true
                }
                MENU_OPEN_WITH -> {
                    openWithSelected()
                    true
                }
                MENU_PROPERTIES -> {
                    showProperties()
                    true
                }
                MENU_EXTRACT -> {
                    launchPicker(DirectoryPickerActivity.MODE_EXTRACT)
                    true
                }
                MENU_ARCHIVE -> {
                    showCreateArchiveDialog(selectedPaths.map { Paths.get(it) })
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showFolderMoreMenu(anchor: View) {
        val single = selectedFolders.size == 1
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_HIDE, 0, R.string.media_folder_hide)
        popup.menu.add(0, MENU_PROPERTIES, 1, R.string.media_category_properties)
            .isEnabled = single
        popup.menu.add(0, MENU_ARCHIVE, 2, R.string.media_archive_title)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HIDE -> {
                    hideFolders(selectedFolders.toList())
                    clearFolderSelection()
                    render()
                    true
                }
                MENU_PROPERTIES -> {
                    showFolderProperties()
                    true
                }
                MENU_ARCHIVE -> {
                    showCreateArchiveDialog(selectedFolders.map { Paths.get(it) })
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showFolderProperties() {
        val key = selectedFolders.singleOrNull() ?: return
        val folder = folderAdapter.folders.firstOrNull { it.key == key } ?: return
        val message = buildString {
            append(folder.name).append('\n')
            append(key).append('\n')
            append(
                getString(R.string.media_folder_count_format, folder.count)
            ).append('\n')
            append(
                Formatter.formatFileSize(this@MediaCategoryActivity, folder.totalSize)
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.media_category_properties)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // Extraction reuses the copy job with the archive's root path as
    // the source, exactly like the file list does: the job then writes
    // the archive's contents into the chosen directory.
    private fun extractArchives(sources: List<Path>, targetDirectory: Path) {
        try {
            val roots = sources.map { it.createArchiveRootPath() }
            FileJobService.copy(roots, targetDirectory, this)
        } catch (e: Exception) {
            Toast.makeText(
                this, R.string.media_extract_failed, Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showCreateArchiveDialog(sources: List<Path>) {
        if (sources.isEmpty()) {
            return
        }
        val targetDirectory = sources.first().parent ?: return
        val firstName = sources.first().fileName?.toString() ?: "archive"
        val baseName = if (sources.size == 1) {
            firstName.substringBeforeLast('.', firstName)
        } else {
            "archive"
        }
        val initialName = "$baseName.zip"
        val editText = EditText(this).apply {
            setText(initialName)
            setSingleLine(true)
            val dotIndex = initialName.lastIndexOf('.')
            if (dotIndex > 0) {
                setSelection(0, dotIndex)
            } else {
                selectAll()
            }
        }
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            setPadding(
                (20 * density).toInt(), (8 * density).toInt(),
                (20 * density).toInt(), 0
            )
            addView(editText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.media_archive_title)
            .setView(container)
            .setPositiveButton(R.string.media_archive_title) { _, _ ->
                var name = editText.text.toString().trim()
                if (!name.endsWith(".zip", ignoreCase = true)) {
                    name += ".zip"
                }
                val fileName = name.asFileNameOrNull()
                if (fileName == null) {
                    Toast.makeText(
                        this, R.string.file_list_create_file_name_error_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                FileJobService.archive(
                    sources, targetDirectory.resolve(fileName.value),
                    Archive.FORMAT_ZIP, Archive.FILTER_NONE, null, this
                )
                clearSelection()
                clearFolderSelection()
                needsRefresh = true
                scheduleReload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareSelected() {
        val entries = selectedEntries()
        if (entries.isEmpty()) {
            return
        }
        val uris = entries.map { it.path.fileProviderUri }
        val intent = uris.createSendStreamIntent(entries.map { it.mimeType })
            .withChooser()
        startActivitySafe(intent)
        clearSelection()
    }

    private fun openWithSelected() {
        val entry = selectedEntries().singleOrNull() ?: return
        val intent = entry.path.fileProviderUri.createViewIntent(entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .withChooser()
        startActivitySafe(intent)
        clearSelection()
    }

    private fun showProperties() {
        val entry = selectedEntries().singleOrNull() ?: return
        val dateTime = DateFormat.format(
            "yyyy/MM/dd HH:mm",
            entry.attributes.lastModifiedInstant.toEpochMilli()
        )
        val message = buildString {
            append(entry.name).append('\n')
            append(entry.path.toString()).append('\n')
            append(entry.mimeType.value).append('\n')
            append(
                Formatter.formatFileSize(
                    this@MediaCategoryActivity, entry.attributes.size()
                )
            ).append('\n')
            append(dateTime)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.media_category_properties)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete() {
        if (selectedFolders.isNotEmpty()) {
            val folderPaths = selectedFolders.map { Paths.get(it) }
            MaterialAlertDialogBuilder(this)
                .setMessage(
                    getString(R.string.media_folder_delete_message, folderPaths.size)
                )
                .setPositiveButton(R.string.delete) { _, _ ->
                    FileJobService.delete(folderPaths, this)
                    clearFolderSelection()
                    needsRefresh = true
                    scheduleReload()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val paths = selectedPaths.map { Paths.get(it) }
        if (paths.isEmpty()) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(
                getString(R.string.media_category_delete_message, paths.size)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                FileJobService.delete(paths, this)
                clearSelection()
                needsRefresh = true
                scheduleReload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPicker(mode: String) {
        val sources = if (selectedFolders.isNotEmpty()) {
            selectedFolders.map { Paths.get(it) }
        } else {
            selectedPaths.map { Paths.get(it) }
        }
        if (sources.isEmpty()) {
            return
        }
        pendingPickerMode = mode
        pendingSources = sources
        val startPath = sources.firstOrNull()?.parent?.toString()
        pickerLauncher.launch(
            DirectoryPickerActivity.createIntent(this, mode, startPath)
        )
    }

    private fun openEntry(entry: MediaEntry) {
        val entries = fileAdapter.entries
        val mimeType = entry.mimeType
        when {
            mimeType.isImage || mimeType.isVideo -> {
                // Images and videos open in the media viewer first,
                // like File Manager Plus; the viewer hands videos to
                // the player when its play button is tapped.
                val media = entries.filter {
                    it.mimeType.isImage || it.mimeType.isVideo
                }
                val mediaPosition = media.indexOf(entry)
                if (mediaPosition == -1) {
                    return
                }
                startActivitySafe(
                    MediaViewerActivity.createIntent(
                        this, media.map { it.path to it.mimeType },
                        mediaPosition
                    )
                )
            }
            mimeType.isAudio -> {
                val sameKind = entries.filter { it.mimeType.isAudio }
                val kindPosition = sameKind.indexOf(entry)
                if (kindPosition == -1) {
                    return
                }
                val (window, windowPosition) = windowed(
                    sameKind.map { it.path }, kindPosition, PATH_LIST_SIZE_MAX
                )
                startActivitySafe(
                    PlayerActivity.createIntent(window, windowPosition, mimeType)
                )
            }
            else -> {
                val intent = entry.path.fileProviderUri.createViewIntent(mimeType)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .apply { extraPath = entry.path }
                startActivitySafe(intent)
            }
        }
    }

    // -- Adapters ----------------------------------------------------------

    private data class FolderInfo(
        val key: String,
        val name: String,
        val count: Int,
        val cover: MediaEntry,
        val totalSize: Long,
        val latestModified: Long
    )

    private class FolderAdapter(
        private val category: MediaCategory,
        private val selectedFolders: Set<String>,
        private val onClick: (FolderInfo) -> Unit,
        private val onLongClick: (FolderInfo) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {
        var folders: List<FolderInfo> = emptyList()
        var useListLayout: Boolean = false
        var hiddenKeys: Set<String> = emptySet()
        var showHidden: Boolean = false

        override fun getItemViewType(position: Int): Int =
            if (useListLayout) VIEW_TYPE_LIST else VIEW_TYPE_GRID

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layoutRes = if (viewType == VIEW_TYPE_LIST) {
                R.layout.media_category_list_item
            } else {
                R.layout.media_folder_item
            }
            val view = parent.context.layoutInflater.inflate(layoutRes, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            val context = holder.itemView.context
            val selected = folder.key in selectedFolders
            if (category == MediaCategory.AUDIO) {
                holder.thumbnailImage.load(folder.cover.path to folder.cover.attributes) {
                    placeholder(R.drawable.file_audio_icon)
                    error(R.drawable.file_audio_icon)
                }
            } else {
                holder.thumbnailImage.load(folder.cover.path to folder.cover.attributes)
            }
            holder.thumbnailImage.alpha = if (selected) 0.55f else 1f
            holder.checkImage?.let { check ->
                check.setColorFilter(
                    context.getColorByAttr(android.R.attr.colorPrimary)
                )
                check.isVisible = selected
            }
            // Folders the user hid are dimmed while hidden items are shown.
            holder.itemView.alpha =
                if (showHidden && folder.key in hiddenKeys) 0.55f else 1f
            if (useListLayout) {
                holder.nameText.text = folder.name
                holder.subtitleText?.text =
                    context.getString(R.string.media_folder_count_format, folder.count) +
                        " · " + Formatter.formatFileSize(context, folder.totalSize)
            } else {
                holder.nameText.text = "${folder.name} (${folder.count})"
            }
            holder.itemView.setOnClickListener { onClick(folder) }
            holder.itemView.setOnLongClickListener {
                onLongClick(folder)
                true
            }
        }

        override fun getItemCount(): Int = folders.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnail_image)
            val nameText: TextView = itemView.findViewById(R.id.name_text)
            val subtitleText: TextView? = itemView.findViewById(R.id.subtitle_text)
            val checkImage: ImageView? = itemView.findViewById(R.id.check_image)
        }

        companion object {
            private const val VIEW_TYPE_LIST = 0
            private const val VIEW_TYPE_GRID = 1
        }
    }

    private class HistoryAdapter(
        private val onClick: (String) -> Unit,
        private val onRemove: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        var items: List<String> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = parent.context.layoutInflater.inflate(
                R.layout.media_search_history_item, parent, false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            holder.icon.setColorFilter(
                context.getColorByAttr(android.R.attr.colorControlNormal)
            )
            holder.text.text = item
            holder.itemView.setOnClickListener { onClick(item) }
            holder.remove.setOnClickListener { onRemove(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.history_icon)
            val text: TextView = itemView.findViewById(R.id.history_text)
            val remove: View = itemView.findViewById(R.id.history_remove)
        }
    }

    private class Adapter(
        private val category: MediaCategory,
        thumbnailsOnly: Boolean,
        useListLayout: Boolean,
        private val selectedPaths: Set<String>,
        private val onClick: (MediaEntry) -> Unit,
        private val onLongClick: (MediaEntry) -> Unit
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        var entries: List<MediaEntry> = emptyList()
        var thumbnailsOnly: Boolean = thumbnailsOnly
        var useListLayout: Boolean = useListLayout

        override fun getItemViewType(position: Int): Int =
            if (useListLayout) VIEW_TYPE_LIST else VIEW_TYPE_GRID

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layoutRes = if (viewType == VIEW_TYPE_LIST) {
                R.layout.media_category_list_item
            } else {
                R.layout.media_category_grid_item
            }
            val view = parent.context.layoutInflater.inflate(layoutRes, parent, false)
            return ViewHolder(view, viewType == VIEW_TYPE_LIST)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            val context = holder.itemView.context
            val selected = entry.path.toString() in selectedPaths
            holder.thumbnailImage.load(entry.path to entry.attributes)
            holder.thumbnailImage.alpha = if (selected) 0.55f else 1f
            holder.checkImage?.let { check ->
                check.setColorFilter(
                    context.getColorByAttr(android.R.attr.colorPrimary)
                )
                check.isVisible = selected
            }
            if (holder.isList) {
                holder.nameText?.text = entry.name
                holder.subtitleText?.let { subtitle ->
                    subtitle.setTextColor(
                        context.getColorByAttr(android.R.attr.textColorSecondary)
                    )
                    val size = Formatter.formatFileSize(
                        context, entry.attributes.size()
                    )
                    subtitle.text = if (category == MediaCategory.RECENT ||
                        category == MediaCategory.DOCUMENTS
                    ) {
                        val date = DateFormat.format(
                            "yyyy/MM/dd",
                            entry.attributes.lastModifiedInstant.toEpochMilli()
                        )
                        "$date  $size"
                    } else {
                        size
                    }
                }
            } else {
                holder.nameText?.isVisible = !thumbnailsOnly
                holder.nameText?.text = entry.name
            }
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }

        override fun getItemCount(): Int = entries.size

        class ViewHolder(itemView: View, val isList: Boolean) :
            RecyclerView.ViewHolder(itemView) {
            val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnail_image)
            val nameText: TextView? = itemView.findViewById(R.id.name_text)
            val subtitleText: TextView? = itemView.findViewById(R.id.subtitle_text)
            val checkImage: ImageView? = itemView.findViewById(R.id.check_image)
        }

        companion object {
            private const val VIEW_TYPE_LIST = 0
            private const val VIEW_TYPE_GRID = 1
        }
    }

    companion object {
        private const val PATH_LIST_SIZE_MAX = 1000
        private const val MENU_SHARE = 1
        private const val MENU_OPEN_WITH = 2
        private const val MENU_PROPERTIES = 3
        private const val MENU_HIDE = 4
        private const val MENU_EXTRACT = 5
        private const val MENU_ARCHIVE = 6
        private const val HIDDEN_KEY_PREFIX = "hidden_"
        private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
        private const val PREFS_VIEW = "media_category_view"
        private const val PREFS_SEARCH = "media_category_search"
        private const val KEY_HISTORY = "history"
        private const val HISTORY_MAX = 10
        private const val SORT_NAME = 0
        private const val SORT_SIZE = 1
        private const val SORT_DATE = 2
        private const val SORT_TYPE = 3
        private val EXTRA_CATEGORY = "${MediaCategoryActivity::class.java.name}.extra.CATEGORY"

        private fun <T> windowed(
            list: List<T>, position: Int, max: Int
        ): Pair<List<T>, Int> {
            if (list.size <= max) {
                return list to position
            }
            val start = (position - max / 2).coerceIn(0, list.size - max)
            return list.subList(start, start + max) to (position - start)
        }

        private fun titleRes(category: MediaCategory): Int =
            when (category) {
                MediaCategory.IMAGES -> R.string.navigation_standard_directory_pictures
                MediaCategory.VIDEOS -> R.string.navigation_standard_directory_movies
                MediaCategory.AUDIO -> R.string.navigation_standard_directory_music
                MediaCategory.DOCUMENTS -> R.string.navigation_standard_directory_documents
                MediaCategory.RECENT -> R.string.home_recent_files
            }

        fun createIntent(context: android.content.Context, category: MediaCategory): Intent =
            Intent(context, MediaCategoryActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY, category.name)
            }
    }
}
