package com.lucas.musique

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews

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

    companion object {
        const val ACT_PLAY = "com.lucas.musique.WIDGET_PLAY"
        const val ACT_NEXT = "com.lucas.musique.WIDGET_NEXT"
        const val ACT_PREV = "com.lucas.musique.WIDGET_PREV"

        // Les boutons parlent directement au service de lecture (pas de connexion à créer).
        private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                ctx, code,
                Intent(ctx, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun build(ctx: Context, title: String, artist: String, playing: Boolean, cover: Bitmap?): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_title, title)
            v.setTextViewText(R.id.w_artist, artist)
            v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            if (cover != null) v.setImageViewBitmap(R.id.w_cover, cover)
            else v.setImageViewResource(R.id.w_cover, R.drawable.widget_cover_placeholder)
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
    }
}
