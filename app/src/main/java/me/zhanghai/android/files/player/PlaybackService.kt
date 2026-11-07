/*
 * Custom fork addition: background playback service based on Media3
 * MediaSessionService. Owns the single ExoPlayer for both audio and video;
 * PlayerActivity is only a UI remote connected through a MediaController,
 * so playback (especially music) continues with the screen off or while
 * the user browses other folders, with system media controls.
 */

package me.zhanghai.android.files.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            .add(
                                SessionCommand(
                                    ACTION_STOP_AND_DISMISS, Bundle.EMPTY
                                )
                            )
                            .build()
                    return MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_STOP_AND_DISMISS) {
                        // The user dismissed playback for good (e.g.
                        // closed the PiP window): stop, clear the
                        // playlist and end the service so the media
                        // notification disappears as well.
                        session.player.stop()
                        session.player.clearMediaItems()
                        stopSelf()
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                }
            })
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Swiping the app away from Recents must end video playback:
        // the picture is gone, so the sound has to stop too (closing
        // the PiP window already behaves this way). Music may keep
        // playing in the background, as before.
        val isVideo = player != null &&
            player.videoSize.width > 0 && player.videoSize.height > 0
        serviceDebug(
            "onTaskRemoved isVideo=$isVideo" +
                " playWhenReady=${player?.playWhenReady}" +
                " state=${player?.playbackState}" +
                " items=${player?.mediaItemCount}" +
                " videoSize=${player?.videoSize?.width}x" +
                "${player?.videoSize?.height}"
        )
        if (player == null || isVideo || !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            serviceDebug("onTaskRemoved -> stopSelf")
            stopSelf()
        }
    }

    private fun serviceDebug(message: String) {
        try {
            val downloads = android.os.Environment
                .getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
            val file = java.io.File(downloads, "mf_pip_debug.txt")
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date())
            java.io.FileWriter(file, true).use {
                it.append("$timestamp SVC $message\n")
            }
        } catch (e: Exception) {
            // Diagnostics only; never let it affect playback.
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_AND_DISMISS =
            "me.zhanghai.android.files.player.STOP_AND_DISMISS"
    }
}
