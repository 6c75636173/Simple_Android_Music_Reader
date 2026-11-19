@file:OptIn(UnstableApi::class)

package com.lucas.musique

import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Service de lecture : musique en arrière-plan, notification, égaliseur, normalisation. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var eq: Equalizer? = null
    private var dp: DynamicsProcessing? = null
    private lateinit var audioPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        audioPrefs = getSharedPreferences("audio", MODE_PRIVATE)

        // La lecture sans blanc (gapless) est gérée automatiquement par ExoPlayer
        // quand les fichiers contiennent les infos nécessaires (MP3 LAME, AAC/M4A, FLAC...).
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveResume(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveResume(player)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupEffects(audioSessionId)
            }
        })
        setupEffects(player.audioSessionId)

        session = MediaSession.Builder(this, player)
            .setBitmapLoader(CoverBitmapLoader(this))
            .setCallback(object : MediaSession.Callback {

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_AUDIO, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(cmds)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_AUDIO) {
                        audioPrefs.edit()
                            .putFloat("bass", args.getFloat("bass", 0f))
                            .putFloat("treble", args.getFloat("treble", 0f))
                            .putBoolean("norm", args.getBoolean("norm", false))
                            .apply()
                        applyEffects()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val items = mediaItems.map {
                        it.buildUpon()
                            .setUri(it.requestMetadata.mediaUri ?: it.localConfiguration?.uri)
                            .build()
                    }.toMutableList()
                    return Futures.immediateFuture(items)
                }
            })
            .build()
    }

    private fun saveResume(p: Player) {
        val id = p.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        getSharedPreferences("resume", MODE_PRIVATE).edit()
            .putLong("lastId", id)
            .putLong("lastPos", p.currentPosition)
            .apply()
    }

    private fun setupEffects(id: Int) {
        try { eq?.release() } catch (_: Exception) {}
        eq = null
        try { dp?.release() } catch (_: Exception) {}
        dp = null
        try {
            eq = Equalizer(0, id).also { it.enabled = true }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                dp = buildLeveler(id)
            } catch (_: Exception) {
            }
        }
        applyEffects()
    }

    /** Limiteur + gain : lisse les écarts de volume entre un titre fort et un titre faible. */
    @RequiresApi(28)
    private fun buildLeveler(id: Int): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            2, false, 0, false, 0, false, 0, true
        ).build()
        val p = DynamicsProcessing(0, id, cfg)
        p.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -8f, 4f)
        )
        p.enabled = false
        return p
    }

    private fun applyEffects() {
        val bass = audioPrefs.getFloat("bass", 0f)
        val treble = audioPrefs.getFloat("treble", 0f)
        eq?.let { e ->
            try {
                val n = e.numberOfBands.toInt()
                val range = e.bandLevelRange
                val mn = range[0].toInt()
                val mx = range[1].toInt()
                for (i in 0 until n) {
                    val t = if (n > 1) i.toFloat() / (n - 1) else 0f
                    val w = bass * (1f - t) + treble * t
                    val lvl = (w * (if (w >= 0f) mx else -mn)).toInt().coerceIn(mn, mx)
                    e.setBandLevel(i.toShort(), lvl.toShort())
                }
            } catch (_: Exception) {
            }
        }
        try {
            dp?.enabled = audioPrefs.getBoolean("norm", false)
        } catch (_: Exception) {
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let { saveResume(it) }
        try { eq?.release() } catch (_: Exception) {}
        try { dp?.release() } catch (_: Exception) {}
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
