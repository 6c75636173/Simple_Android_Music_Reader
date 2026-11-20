package com.lucas.musique

import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.cos
import kotlin.math.sin

/**
 * Service de lecture : arrière-plan, notification, égaliseur, normalisation,
 * fondu enchaîné (2 lecteurs), minuteur d'arrêt, widget, favori et compteur d'écoutes.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var fader: ExoPlayer? = null // joue la fin du titre sortant pendant le fondu
    private var eq: Equalizer? = null
    private var dp: DynamicsProcessing? = null
    private lateinit var audioPrefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // --- état du fondu enchaîné ---
    private var xfadeMs = 0L
    private var forceGapless = false
    private var fadeLen = 0L
    private var fadeStart = 0L // 0 = pas de fondu en cours
    private var fadePrepared = false
    private var preparedId: String? = null
    private var giveUpId: String? = null
    private var countedKey: String? = null

    private val favListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateFavButton() }

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (_: Exception) {
            }
            if (player.isPlaying || fadeStart != 0L) handler.postDelayed(this, 50)
        }
    }

    private val sleepStop = Runnable {
        player.pause()
        audioPrefs.edit().putLong("sleepEnd", 0L).apply()
    }

    override fun onCreate() {
        super.onCreate()
        audioPrefs = getSharedPreferences("audio", MODE_PRIVATE)
        xfadeMs = audioPrefs.getInt("xfade", 0) * 1000L
        forceGapless = audioPrefs.getBoolean("gapless", false)
        audioPrefs.edit().putLong("sleepEnd", 0L).apply()

        player = ExoPlayer.Builder(this)
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
                if (isPlaying) {
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                } else {
                    saveResume(player)
                }
                updateWidget()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) abortFade() // pause : on coupe le fondu
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveResume(player)
                countedKey = null
                giveUpId = null
                if (fadeStart != 0L) {
                    // la transition déclenchée par le fondu lui-même arrive dans les 800 ms
                    if (SystemClock.uptimeMillis() - fadeStart > 800) abortFade()
                } else {
                    fadePrepared = false
                }
                updateWidget()
                updateFavButton()
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
                        .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                        .add(SessionCommand(CMD_FAV, Bundle.EMPTY))
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
                    when (customCommand.customAction) {
                        CMD_AUDIO -> {
                            val xf = args.getInt("xfade", 0).coerceIn(0, 12)
                            val gl = args.getBoolean("gapless", false)
                            audioPrefs.edit()
                                .putFloat("bass", args.getFloat("bass", 0f))
                                .putFloat("treble", args.getFloat("treble", 0f))
                                .putBoolean("norm", args.getBoolean("norm", false))
                                .putInt("xfade", xf)
                                .putBoolean("gapless", gl)
                                .apply()
                            xfadeMs = xf * 1000L
                            forceGapless = gl
                            if (fadeMs() == 0L && fadeStart == 0L) fadePrepared = false
                            applyEffects()
                        }
                        CMD_SLEEP -> {
                            val min = args.getInt("min", 0)
                            handler.removeCallbacks(sleepStop)
                            if (min > 0) {
                                handler.postDelayed(sleepStop, min * 60_000L)
                                audioPrefs.edit()
                                    .putLong("sleepEnd", System.currentTimeMillis() + min * 60_000L)
                                    .apply()
                            } else {
                                audioPrefs.edit().putLong("sleepEnd", 0L).apply()
                            }
                        }
                        CMD_FAV -> {
                            currentPath()?.let { FavStore(this@PlaybackService).toggle(it) }
                            updateFavButton()
                        }
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

        getSharedPreferences("favs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(favListener)
        updateFavButton()
    }

    /** Boutons du widget : on reçoit l'action directement ici. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MusicWidget.ACT_PLAY -> if (player.isPlaying) player.pause() else player.play()
            MusicWidget.ACT_NEXT -> player.seekToNext()
            MusicWidget.ACT_PREV -> player.seekToPrevious()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun currentPath(): String? =
        player.currentMediaItem?.mediaMetadata?.extras?.getString("path")

    // ------------------------------------------------------------------
    // Fondu enchaîné : un 2e lecteur joue la fin du titre qui sort pendant
    // que le lecteur principal démarre le titre suivant (volumes en
    // puissance constante). Le 2e lecteur est préparé À L'AVANCE à la bonne
    // position pour éviter tout à-coup (cartes SD lentes).
    // ------------------------------------------------------------------

    /** Durée effective du chevauchement : fondu choisi, sinon 0,3 s si « sans blanc forcé ». */
    private fun fadeMs(): Long = if (xfadeMs > 0L) xfadeMs else if (forceGapless) 300L else 0L

    private fun countPlay() {
        val key = currentPath() ?: return
        if (key != countedKey && player.currentPosition >= 30_000L) {
            countedKey = key
            PlayStore(this).inc(key)
        }
    }

    private fun onTick() {
        countPlay()
        if (fadeStart != 0L) {
            stepFade()
            return
        }
        val fm = fadeMs()
        if (fm <= 0L || !player.isPlaying) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) {
            fadePrepared = false
            return
        }
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur < fm * 2 + 8000) return // titre trop court
        val id = player.currentMediaItem?.mediaId
        if (id == giveUpId) return
        val remaining = dur - player.currentPosition
        if (remaining <= fm + 6000 && !fadePrepared) prepareFader(dur - fm - 1000)
        if (remaining <= fm && fadePrepared) {
            if (fader?.playbackState == Player.STATE_READY) {
                startFade(fm)
            } else if (remaining < fm - minOf(1500L, fm / 2)) {
                // le 2e lecteur n'est pas prêt à temps : on laisse la transition normale
                fadePrepared = false
                giveUpId = id
            }
        }
    }

    private fun buildFader(): ExoPlayer {
        val f = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false // le focus audio reste géré par le lecteur principal
            )
            .build()
        f.audioSessionId = player.audioSessionId // mêmes effets (égaliseur, normalisation)
        return f
    }

    private fun prepareFader(startMs: Long) {
        val item = player.currentMediaItem ?: return
        val f = fader ?: buildFader().also { fader = it }
        f.setMediaItem(item, startMs.coerceAtLeast(0L))
        f.volume = 0f
        f.playWhenReady = false
        f.prepare()
        preparedId = item.mediaId
        fadePrepared = true
    }

    private fun startFade(len: Long) {
        val f = fader ?: return
        if (preparedId != player.currentMediaItem?.mediaId) {
            fadePrepared = false
            return
        }
        fadePrepared = false
        fadeLen = len
        f.playbackParameters = player.playbackParameters
        f.seekTo(player.currentPosition + 150)
        f.volume = 1f
        f.play()
        fadeStart = SystemClock.uptimeMillis()
        player.volume = 0f
        player.seekToNextMediaItem()
    }

    private fun stepFade() {
        if (fadeLen <= 0L) {
            endFade()
            return
        }
        val t = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeLen).coerceIn(0f, 1f)
        val a = t * Math.PI / 2
        player.volume = sin(a).toFloat()
        fader?.volume = cos(a).toFloat()
        if (t >= 1f) endFade()
    }

    private fun endFade() {
        fadeStart = 0L
        fadePrepared = false
        player.volume = 1f
        fader?.let {
            try {
                it.stop()
                it.clearMediaItems()
            } catch (_: Exception) {
            }
        }
    }

    private fun abortFade() {
        val active = fadeStart != 0L || fadePrepared
        fadeStart = 0L
        fadePrepared = false
        if (!active) return
        player.volume = 1f
        fader?.let {
            try {
                it.stop()
                it.clearMediaItems()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------

    /** Bouton favori (♥) dans la notification et sur l'écran verrouillé. */
    private fun updateFavButton() {
        val s = session ?: return
        val path = currentPath()
        val fav = path != null && FavStore(this).load().contains(path)
        val b = CommandButton.Builder()
            .setDisplayName(if (fav) "Retirer des favoris" else "Ajouter aux favoris")
            .setIconResId(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            .setSessionCommand(SessionCommand(CMD_FAV, Bundle.EMPTY))
            .build()
        s.setCustomLayout(ImmutableList.of(b))
    }

    private fun updateWidget() {
        if (!MusicWidget.hasWidgets(applicationContext)) return
        val md = player.mediaMetadata
        val title = md.title?.toString() ?: "Lecteur"
        val artist = md.artist?.toString() ?: ""
        val playing = player.isPlaying
        val uri = player.currentMediaItem?.localConfiguration?.uri
        getSharedPreferences("widget", MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("artist", artist)
            .putBoolean("playing", playing)
            .apply()
        Thread {
            val bmp = uri?.let { loadCoverBlocking(applicationContext, it, 200) }
            handler.post { MusicWidget.update(applicationContext, title, artist, playing, bmp) }
        }.start()
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
        handler.removeCallbacksAndMessages(null)
        getSharedPreferences("favs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(favListener)
        session?.player?.let { saveResume(it) }
        try { eq?.release() } catch (_: Exception) {}
        try { dp?.release() } catch (_: Exception) {}
        try { fader?.release() } catch (_: Exception) {}
        fader = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
