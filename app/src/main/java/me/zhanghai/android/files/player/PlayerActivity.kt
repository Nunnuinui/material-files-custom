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
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Rational
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
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
import androidx.media3.session.SessionCommand
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
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.valueCompat

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

    // Pinch zoom state for the video surface (TextureView).
    private var videoSurface: TextureView? = null
    private var videoZoom = 1f
    private var videoPanX = 0f
    private var videoPanY = 0f

    // Picture-in-picture state: true while this activity is (or was,
    // until handled in onStop) showing the video in a PiP window.
    private var wasInPip = false

    private val pipToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val mediaController = controller ?: return
            if (mediaController.isPlaying) {
                mediaController.pause()
            } else {
                mediaController.play()
            }
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItems: List<MediaItem>? = null
    private var pendingPosition: Int = 0
    private var isVideo: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resetVideoZoom()
            updatePipParams()
            updateInfo(mediaItem)
            updatePlayButton()
            updateProgress()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateInfo(controller?.currentMediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.registerReceiver(
                this, pipToggleReceiver,
                IntentFilter(ACTION_PIP_TOGGLE_PLAY_PAUSE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        maybeRequestNotificationPermission()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                unregisterReceiver(pipToggleReceiver)
            } catch (e: Exception) {
                // Not registered (should not happen).
            }
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Returning to full screen from PiP (expanding the window):
        // clear the PiP flag without pausing.
        if (wasInPip && !isInPipNow()) {
            wasInPip = false
        }
        // (Re)arm the PiP params, including auto-enter, so a setting
        // change made in the settings screen takes effect without
        // restarting playback.
        updatePipParams()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // With background video playback enabled, leaving the app
        // during a video enters picture-in-picture so the user keeps
        // watching in a small window.
        pipDebug(
            "onUserLeaveHint isVideo=$isVideo" +
                " bg=${Settings.VIDEO_BACKGROUND_PLAYBACK.valueCompat}" +
                " controller=${controller != null} finishing=$isFinishing" +
                " pipFeature=${packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
                )} sdk=${Build.VERSION.SDK_INT}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isVideo && Settings.VIDEO_BACKGROUND_PLAYBACK.valueCompat &&
            controller != null && !isFinishing
        ) {
            // Device findings (user's Galaxy, Android 16): while
            // auto-enter params are registered, app-initiated PiP
            // entry is always refused; with manual params registered,
            // the same call succeeds in the Recents flow. The home
            // gesture still refuses manual entry (mid-transition) and
            // needs the system's auto-enter, which reads the
            // registered params about a second later. So: swap the
            // registered params to manual ones, try to enter now,
            // and if that fails, swap the auto-enter params back in
            // so the system can still take over for the gesture.
            val manualParams = buildPipParams(forManualEnter = true)
            try {
                setPictureInPictureParams(manualParams)
            } catch (e: Exception) {
                // Ignore: still try to enter below.
            }
            var entered = false
            try {
                entered = enterPictureInPictureMode(manualParams)
                pipDebug("hint disarm+enter -> $entered")
            } catch (e: Exception) {
                // PiP unavailable (e.g. turned off in system settings
                // for this app): playback simply continues in the
                // background.
                pipDebug("hint disarm+enter threw: $e")
            }
            if (!entered) {
                try {
                    setPictureInPictureParams(buildPipParams())
                } catch (e: Exception) {
                    // Ignore: params are re-registered on resume.
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipDebug("onPictureInPictureModeChanged in=$isInPictureInPictureMode")
        if (isInPictureInPictureMode) {
            wasInPip = true
        }
        // On exit, wasInPip is deliberately NOT cleared here: this
        // callback fires before onStop when the PiP window is closed
        // with the X button, and onStop needs the flag to tell a
        // dismissal (pause) apart from an expansion back to full
        // screen (handled in onResume).
        playerView?.useController = !isInPictureInPictureMode
    }

    // Diagnostic trail in Downloads (mf_pip_debug.txt), used to find
    // out why PiP did not engage on the user's device.
    private fun pipDebug(message: String) {
        try {
            val stamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date())
            @Suppress("DEPRECATION")
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
            java.io.File(dir, "mf_pip_debug.txt").appendText("$stamp $message\n")
        } catch (e: Exception) {
            // Diagnostics must never break the app.
        }
    }

    private fun isInPipNow(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isInPictureInPictureMode

    private fun buildPipParams(
        forManualEnter: Boolean = false
    ): PictureInPictureParams {
        val mediaController = controller
        val size = mediaController?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0 &&
            size.width.toFloat() / size.height in 0.42f..2.39f
        ) {
            Rational(size.width, size.height)
        } else {
            Rational(16, 9)
        }
        val playing = mediaController?.isPlaying == true
        val iconRes = if (playing) R.drawable.player_pause else R.drawable.player_play
        val title = getString(
            if (playing) R.string.pip_action_pause else R.string.pip_action_play
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this, PIP_TOGGLE_REQUEST_CODE,
            Intent(ACTION_PIP_TOGGLE_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = RemoteAction(
            Icon.createWithResource(this, iconRes), title, title, toggleIntent
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(toggleAction))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !forManualEnter
        ) {
            // Let the system enter PiP automatically on the leave
            // gesture (Android 12+). The manual call in
            // onUserLeaveHint was refused (returned false) on the
            // user's Galaxy even with every condition in place; the
            // system-initiated path does not depend on the activity's
            // focus state at that instant. Manual enter requests use
            // params WITHOUT the auto-enter flag: on that device the
            // manual call kept being refused while the flag was part
            // of the request params, so keep the two separate.
            builder.setAutoEnterEnabled(
                isVideo && Settings.VIDEO_BACKGROUND_PLAYBACK.valueCompat
            )
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !isVideo || isFinishing
        ) {
            return
        }
        // Register the PiP params (with auto-enter) with the system.
        // The home gesture can only be covered by this system-driven
        // auto-enter: at that instant the activity is mid-transition
        // and app-initiated entry is refused. The Recents path is
        // covered separately by the disarm-and-enter fallback in
        // onPause, and onResume re-registers these params afterwards.
        try {
            setPictureInPictureParams(buildPipParams())
        } catch (e: Exception) {
            // Not in a state where PiP params can be set; the params
            // are rebuilt when entering PiP anyway.
        }
    }

    private fun setupVideoViews() {
        val view = findViewById<PlayerView>(R.id.player_view)
        playerView = view
        // Pinch zoom on the video surface (a TextureView, so it can be
        // scaled and panned with regular view transforms).
        (view.videoSurfaceView as? TextureView)?.let { surface ->
            videoSurface = surface
            setupVideoZoom(surface)
        }
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
        findViewById<View>(R.id.rotate_button)?.setOnClickListener {
            rotatedLandscape = !rotatedLandscape
            requestedOrientation = if (rotatedLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    private fun setupVideoZoom(surface: TextureView) {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    videoZoom = (videoZoom * detector.scaleFactor)
                        .coerceIn(1f, MAX_VIDEO_ZOOM)
                    if (videoZoom <= 1f) {
                        videoPanX = 0f
                        videoPanY = 0f
                    } else {
                        clampVideoPan()
                    }
                    applyVideoTransform()
                    return true
                }
            }
        )
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // The surface claims every touch (see below), so
                    // the tap-to-toggle-controller behavior of the
                    // PlayerView is re-implemented here.
                    val view = playerView ?: return true
                    if (view.isControllerFullyVisible) {
                        view.hideController()
                    } else {
                        view.showController()
                    }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    distanceX: Float, distanceY: Float
                ): Boolean {
                    if (videoZoom <= 1f) {
                        return false
                    }
                    videoPanX -= distanceX
                    videoPanY -= distanceY
                    clampVideoPan()
                    applyVideoTransform()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    resetVideoZoom()
                    return true
                }
            }
        )
        // The surface must claim the whole touch stream from the
        // first ACTION_DOWN on; returning false for the down event
        // (as the first version did unless a scale was already in
        // progress) makes the framework deliver the rest of the
        // gesture to the PlayerView instead, so pinch never started.
        surface.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun clampVideoPan() {
        val surface = videoSurface ?: return
        val maxX = surface.width * (videoZoom - 1f) / 2f
        val maxY = surface.height * (videoZoom - 1f) / 2f
        videoPanX = videoPanX.coerceIn(-maxX, maxX)
        videoPanY = videoPanY.coerceIn(-maxY, maxY)
    }

    private fun applyVideoTransform() {
        val surface = videoSurface ?: return
        surface.scaleX = videoZoom
        surface.scaleY = videoZoom
        surface.translationX = videoPanX
        surface.translationY = videoPanY
    }

    private fun resetVideoZoom() {
        videoZoom = 1f
        videoPanX = 0f
        videoPanY = 0f
        applyVideoTransform()
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
        // Videos pause when the user leaves the app, unless the
        // background video playback setting is on; music always keeps
        // playing in the background. A rotation recreates the activity
        // and must not pause playback. Closing the PiP window counts
        // as leaving for good, so it pauses even with the setting on.
        // On the user's Galaxy (Android 16), closing PiP with the X
        // button stops the activity while isInPictureInPictureMode
        // still reports true, and the mode-changed(false) callback
        // only arrives afterwards — so "not in PiP anymore" can never
        // be observed here. Expanding PiP back to full screen never
        // stops the activity at all (and clears the latch in
        // onResume), so a latched wasInPip at onStop time means the
        // PiP window was dismissed.
        val pipDismissed = wasInPip
        pipDebug(
            "onStop isVideo=$isVideo wasInPip=$wasInPip" +
                " inPip=${isInPipNow()} pipDismissed=$pipDismissed"
        )
        if (pipDismissed) {
            wasInPip = false
        }
        if (isVideo && !isChangingConfigurations && pipDismissed) {
            // Dismissed for good (PiP closed with X): pause right away
            // and tell the service to stop entirely, which also
            // removes the media notification.
            controller?.pause()
            controller?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.ACTION_STOP_AND_DISMISS, Bundle.EMPTY
                ),
                Bundle.EMPTY
            )
        } else if (isVideo && !isChangingConfigurations &&
            !Settings.VIDEO_BACKGROUND_PLAYBACK.valueCompat
        ) {
            controller?.pause()
        }
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
        private const val MAX_VIDEO_ZOOM = 5f
        private const val ACTION_PIP_TOGGLE_PLAY_PAUSE =
            "me.zhanghai.android.files.player.PIP_TOGGLE_PLAY_PAUSE"
        private const val PIP_TOGGLE_REQUEST_CODE = 1001
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
