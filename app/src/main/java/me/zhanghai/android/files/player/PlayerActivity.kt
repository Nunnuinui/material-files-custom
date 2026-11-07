/*
 * Custom fork addition: built-in media player UI for audio and video,
 * backed by the PlaybackService through a MediaController (Media3).
 * Mirrors how the image viewer is wired: FileListFragment passes the
 * sibling media files of the tapped file as a playlist, so repeat-all
 * loops the folder and prev/next move within it.
 *
 * Video UI follows the File Manager Plus player (custom controller
 * layout); music UI follows YouTube Music (big artwork, title/artist,
 * seek bar, shuffle / prev / big play / next / repeat).
 */

package me.zhanghai.android.files.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isVideo
import androidx.appcompat.widget.PopupMenu
import me.zhanghai.android.files.home.applySystemBarInsetsPadding
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList

class PlayerActivity : AppActivity() {
    private var playerView: PlayerView? = null
    private var titleText: TextView? = null
    private var artistText: TextView? = null
    private var artworkImage: ImageView? = null
    private var repeatButton: ImageButton? = null
    private var repeatBadge: TextView? = null
    private var shuffleButton: ImageButton? = null

    // Audio-mode custom controls
    private var seekBar: SeekBar? = null
    private var positionText: TextView? = null
    private var durationText: TextView? = null
    private var playButton: ImageButton? = null
    private var fullscreenButton: ImageButton? = null
    private var isFullscreen = false
    private var isSeeking = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    // Video-mode custom controller buttons
    private var resizeIndex = 0
    private var rotatedLandscape = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItems: List<MediaItem>? = null
    private var pendingPosition: Int = 0
    private var isVideo: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateInfo(mediaItem)
            updatePlayButton()
            updateProgress()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateInfo(controller?.currentMediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayButton()
            updateProgress()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            controller?.let { updateModeButtons(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            controller?.let { updateModeButtons(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity, R.string.player_error, Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        me.zhanghai.android.files.home.CrashReporter.install()

        val mimeType = intent.type?.asMimeTypeOrNull()
        isVideo = mimeType?.isVideo == true
        val paths = intent.extraPathList
        val data = intent.data
        when {
            !paths.isNullOrEmpty() -> {
                pendingItems = paths.map { path -> path.toMediaItem() }
                pendingPosition = intent.getIntExtra(EXTRA_POSITION, 0)
                    .coerceIn(0, paths.size - 1)
            }
            data != null -> {
                val title = intent.extraPath?.fileName?.toString()
                    ?: data.lastPathSegment ?: ""
                pendingItems = listOf(
                    MediaItem.Builder()
                        .setUri(data)
                        .setMediaMetadata(
                            MediaMetadata.Builder().setTitle(title).build()
                        )
                        .build()
                )
                pendingPosition = 0
            }
            else -> {
                // Launched from the playback notification: show the current
                // playback (audio UI) without changing the playlist.
                isVideo = false
            }
        }

        setContentView(
            if (isVideo) R.layout.player_activity else R.layout.player_audio_activity
        )
        findViewById<View>(R.id.root_layout).applySystemBarInsetsPadding()
        titleText = findViewById(R.id.title_text)
        artistText = findViewById(R.id.artist_text)
        artworkImage = findViewById(R.id.artwork_image)
        repeatButton = findViewById(R.id.repeat_button)
        repeatBadge = findViewById(R.id.repeat_badge)
        shuffleButton = findViewById(R.id.shuffle_button)
        repeatButton?.setOnClickListener { cycleRepeatMode() }
        shuffleButton?.setOnClickListener { toggleShuffle() }
        if (isVideo) {
            setupVideoViews()
        } else {
            setupAudioViews()
        }
        updateModeButtons(null)
        maybeRequestNotificationPermission()
    }

    private fun setupVideoViews() {
        val view = findViewById<PlayerView>(R.id.player_view)
        playerView = view
        // The fullscreen button is wired manually as well, so its
        // icon is the app's own (enter/exit) instead of Media3's.
        fullscreenButton = findViewById(R.id.fullscreen_button)
        fullscreenButton?.setOnClickListener { applyFullscreen(!isFullscreen) }
        // Transport buttons are wired manually: Media3 1.8's styled
        // controller only binds its single exo_play_pause button and the
        // *_with_amount seek buttons, so the legacy exo_play/exo_pause and
        // exo_rew/exo_ffwd ids in a custom layout receive no clicks.
        playButton = findViewById(R.id.play_pause_button)
        playButton?.setOnClickListener { togglePlayPause() }
        findViewById<View>(R.id.prev_button)?.setOnClickListener {
            controller?.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.next_button)?.setOnClickListener {
            controller?.seekToNextMediaItem()
        }
        findViewById<View>(R.id.rew_button)?.setOnClickListener {
            controller?.seekBack()
        }
        findViewById<View>(R.id.ff_button)?.setOnClickListener {
            controller?.seekForward()
        }
        findViewById<View>(R.id.back_button)?.setOnClickListener { finish() }
        findViewById<TextView>(R.id.speed_button)?.let { speedButton ->
            speedButton.setOnClickListener { showSpeedMenu(speedButton) }
        }
        findViewById<View>(R.id.aspect_button)?.setOnClickListener {
            resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
            view.resizeMode = RESIZE_MODES[resizeIndex]
        }
        findViewById<View>(R.id.rotate_button)?.setOnClickListener {
            rotatedLandscape = !rotatedLandscape
            requestedOrientation = if (rotatedLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    private fun setupAudioViews() {
        findViewById<View>(R.id.close_button)?.setOnClickListener { finish() }
        playButton = findViewById(R.id.play_button)
        playButton?.setOnClickListener { togglePlayPause() }
        findViewById<View>(R.id.prev_button)?.setOnClickListener {
            controller?.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.next_button)?.setOnClickListener {
            controller?.seekToNextMediaItem()
        }
        seekBar = findViewById(R.id.seek_bar)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar, progress: Int, fromUser: Boolean
            ) {
                if (fromUser) {
                    positionText?.text = formatTime(progress * 1000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                controller?.seekTo(seekBar.progress * 1000L)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(
            this, ComponentName(this, PlaybackService::class.java)
        )
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            val mediaController = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            controller = mediaController
            playerView?.player = mediaController
            mediaController.addListener(playerListener)
            pendingItems?.let { items ->
                mediaController.setMediaItems(items, pendingPosition, 0L)
                mediaController.prepare()
                mediaController.play()
                pendingItems = null
            }
            syncSpeedButton(mediaController)
            updateInfo(mediaController.currentMediaItem)
            updateModeButtons(mediaController)
            updatePlayButton()
            updateProgress()
        }, ContextCompat.getMainExecutor(this))
        if (!isVideo) {
            progressHandler.post(progressUpdater)
        }
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressUpdater)
        playerView?.player = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { future -> MediaController.releaseFuture(future) }
        controllerFuture = null
        super.onStop()
    }

    private fun showSpeedMenu(anchor: TextView) {
        val mediaController = controller ?: return
        val popup = PopupMenu(this, anchor)
        SPEED_OPTIONS.forEachIndexed { index, option ->
            popup.menu.add(0, index, index, option.second)
        }
        popup.menu.add(0, SPEED_OPTIONS.size, SPEED_OPTIONS.size,
            R.string.player_speed_custom)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == SPEED_OPTIONS.size) {
                showCustomSpeedDialog(anchor)
            } else {
                val option = SPEED_OPTIONS[item.itemId]
                mediaController.setPlaybackSpeed(option.first)
                anchor.text = option.second
            }
            true
        }
        popup.show()
    }

    private fun showCustomSpeedDialog(anchor: TextView) {
        val mediaController = controller ?: return
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(
                speedLabelText(mediaController.playbackParameters.speed)
                    .removeSuffix("X")
            )
            selectAll()
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
            .setTitle(R.string.player_speed_custom)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val parsed = editText.text.toString().toFloatOrNull()
                val speed = parsed?.let { kotlin.math.round(it * 100) / 100f }
                if (speed == null || speed < 0.1f || speed > 5f) {
                    Toast.makeText(
                        this, R.string.player_speed_invalid, Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                mediaController.setPlaybackSpeed(speed)
                anchor.text = speedLabelText(speed)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun speedLabelText(speed: Float): String {
        val option = SPEED_OPTIONS.firstOrNull { it.first == speed }
        if (option != null) {
            return option.second
        }
        val text = java.math.BigDecimal(speed.toString())
            .stripTrailingZeros()
            .toPlainString()
        return text + "X"
    }

    private fun syncSpeedButton(mediaController: MediaController) {
        val speedButton = findViewById<TextView>(R.id.speed_button) ?: return
        speedButton.text = speedLabelText(mediaController.playbackParameters.speed)
    }

    private fun updateInfo(mediaItem: MediaItem?) {
        val metadata = mediaItem?.mediaMetadata
        val title = metadata?.title?.toString()?.takeIf { it.isNotEmpty() }
            ?: mediaItem?.mediaId?.substringAfterLast('/') ?: ""
        titleText?.text = title
        if (isVideo) {
            return
        }
        val artist = metadata?.artist?.toString()?.takeIf { it.isNotEmpty() }
        artistText?.text = artist ?: ""
        val artworkData = metadata?.artworkData
        if (artworkData != null) {
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            if (bitmap != null) {
                artworkImage?.setImageBitmap(bitmap)
                return
            }
        }
        artworkImage?.setImageResource(R.drawable.player_audio_artwork)
        loadArtworkFallback(mediaItem, artist == null)
    }

    // For local files, read the embedded artwork (and artist) straight from
    // the file when the session did not provide them.
    private fun loadArtworkFallback(mediaItem: MediaItem?, needArtist: Boolean) {
        val mediaId = mediaItem?.mediaId ?: return
        val path = try {
            Paths.get(mediaId)
        } catch (e: Exception) {
            return
        }
        if (!path.isLinuxPath) {
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path.toFile().absolutePath)
                        val picture = retriever.embeddedPicture?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        val tagArtist = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_ARTIST
                        )
                        picture to tagArtist
                    } finally {
                        retriever.release()
                    }
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            if (controller?.currentMediaItem?.mediaId != mediaId) {
                return@launch
            }
            result.first?.let { bitmap -> artworkImage?.setImageBitmap(bitmap) }
            if (needArtist) {
                result.second?.takeIf { it.isNotEmpty() }?.let { tagArtist ->
                    artistText?.text = tagArtist
                }
            }
        }
    }

    private fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) {
            mediaController.pause()
        } else {
            mediaController.play()
        }
    }

    private fun updatePlayButton() {
        val button = playButton ?: return
        val playing = controller?.isPlaying == true
        button.setImageResource(
            if (playing) R.drawable.player_pause_dark else R.drawable.player_play_dark
        )
        button.contentDescription = getString(
            if (playing) R.string.player_pause else R.string.player_play
        )
    }

    private fun updateProgress() {
        val bar = seekBar ?: return
        val mediaController = controller ?: return
        val durationMs = mediaController.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0) {
            bar.max = 0
            bar.progress = 0
            durationText?.text = ""
            positionText?.text = ""
            return
        }
        bar.max = (durationMs / 1000).toInt()
        if (!isSeeking) {
            bar.progress = (mediaController.currentPosition / 1000).toInt()
            positionText?.text = formatTime(mediaController.currentPosition)
        }
        durationText?.text = formatTime(durationMs)
    }

    private fun cycleRepeatMode() {
        val mediaController = controller ?: return
        mediaController.repeatMode = when (mediaController.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        updateModeButtons(mediaController)
    }

    private fun toggleShuffle() {
        val mediaController = controller ?: return
        mediaController.shuffleModeEnabled = !mediaController.shuffleModeEnabled
        updateModeButtons(mediaController)
    }

    private fun updateModeButtons(player: Player?) {
        val repeatMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val shuffleEnabled = player?.shuffleModeEnabled ?: false
        repeatButton?.setColorFilter(
            if (repeatMode == Player.REPEAT_MODE_OFF) COLOR_INACTIVE else Color.WHITE
        )
        repeatBadge?.isVisible = repeatMode == Player.REPEAT_MODE_ONE
        shuffleButton?.setColorFilter(
            if (shuffleEnabled) Color.WHITE else COLOR_INACTIVE
        )
    }

    private fun applyFullscreen(isFullscreen: Boolean) {
        this.isFullscreen = isFullscreen
        fullscreenButton?.setImageResource(
            if (isFullscreen) R.drawable.player_fullscreen_exit
            else R.drawable.player_fullscreen_enter
        )
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun Path.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(toString())
            .setUri(fileProviderUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fileName?.toString() ?: "")
                    .build()
            )
            .build()

    companion object {
        private val COLOR_INACTIVE = Color.argb(140, 255, 255, 255)
        private const val PROGRESS_INTERVAL_MS = 500L
        private val SPEED_OPTIONS = listOf(
            0.25f to "0.25X",
            0.5f to "0.5X",
            0.75f to "0.75X",
            1f to "1X",
            1.25f to "1.25X",
            1.5f to "1.5X",
            1.75f to "1.75X",
            2f to "2X"
        )
        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        private val EXTRA_POSITION = "${PlayerActivity::class.java.name}.extra.POSITION"

        private fun speedLabel(speed: Float): String =
            if (speed == speed.toInt().toFloat()) {
                "${speed.toInt()}X"
            } else {
                "${speed}X"
            }

        private fun formatTime(timeMs: Long): String {
            val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = totalSeconds % 3600 / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }

        fun createIntent(paths: List<Path>, position: Int, mimeType: MimeType): Intent =
            Intent(application, PlayerActivity::class.java).apply {
                type = mimeType.value
                extraPathList = paths
                putExtra(EXTRA_POSITION, position)
            }
    }
}
