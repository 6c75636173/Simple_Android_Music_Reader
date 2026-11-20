package com.lucas.musique

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** Widget d'écran d'accueil : pochette, titre, précédent / lecture / suivant. */
class MusicWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val sp = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val v = build(
            context,
            sp.getString("title", "Lecteur") ?: "Lecteur",
            sp.getString("artist", "") ?: "",
            sp.getBoolean("playing", false),
            null
        )
        appWidgetManager.updateAppWidget(appWidgetIds, v)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val a = intent.action ?: return
        if (a == ACT_PLAY || a == ACT_NEXT || a == ACT_PREV) control(context.applicationContext, a)
    }

    companion object {
        const val ACT_PLAY = "com.lucas.musique.WIDGET_PLAY"
        const val ACT_NEXT = "com.lucas.musique.WIDGET_NEXT"
        const val ACT_PREV = "com.lucas.musique.WIDGET_PREV"

        private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, MusicWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun build(ctx: Context, title: String, artist: String, playing: Boolean, cover: Bitmap?): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_title, title)
            v.setTextViewText(R.id.w_artist, artist)
            v.setImageViewResource(
                R.id.w_play,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            if (cover != null) v.setImageViewBitmap(R.id.w_cover, cover)
            else v.setImageViewResource(R.id.w_cover, R.drawable.ic_launcher_foreground)
            v.setOnClickPendingIntent(R.id.w_play, pi(ctx, ACT_PLAY, 1))
            v.setOnClickPendingIntent(R.id.w_prev, pi(ctx, ACT_PREV, 2))
            v.setOnClickPendingIntent(R.id.w_next, pi(ctx, ACT_NEXT, 3))
            v.setOnClickPendingIntent(
                R.id.w_info,
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            return v
        }

        fun hasWidgets(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            return mgr.getAppWidgetIds(ComponentName(ctx, MusicWidget::class.java)).isNotEmpty()
        }

        fun update(ctx: Context, title: String, artist: String, playing: Boolean, cover: Bitmap?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MusicWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx, title, artist, playing, cover))
        }

        private fun control(ctx: Context, action: String) {
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            val f = MediaController.Builder(ctx, token).buildAsync()
            val main = Handler(Looper.getMainLooper())
            f.addListener({
                try {
                    val c = f.get()
                    when (action) {
                        ACT_PLAY -> if (c.isPlaying) c.pause() else c.play()
                        ACT_NEXT -> c.seekToNext()
                        ACT_PREV -> c.seekToPrevious()
                    }
                } catch (_: Exception) {
                }
                main.postDelayed({ MediaController.releaseFuture(f) }, 2000)
            }, ContextCompat.getMainExecutor(ctx))
        }
    }
}
