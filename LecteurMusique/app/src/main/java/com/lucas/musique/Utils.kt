package com.lucas.musique

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Commandes personnalisées envoyées au service de lecture. */
const val CMD_AUDIO = "com.lucas.musique.AUDIO"   // égaliseur, normalisation, fondu
const val CMD_SLEEP = "com.lucas.musique.SLEEP"   // minuteur d'arrêt (minutes ; 0 = annuler)
const val CMD_FAV = "com.lucas.musique.FAV"       // bouton favori (notification / écran verrouillé)

/** Dossiers scannés par défaut : Music du stockage interne et de la carte SD. */
val DEFAULT_FOLDERS = listOf("/storage/emulated/0/Music", "/storage/%-%/Music")

fun folderLabel(p: String): String =
    if (p.contains("%-%")) p.replace("/storage/%-%", "Carte SD") else p

/** Convertit le dossier choisi dans le sélecteur Android en chemin de fichier. */
fun treeUriToPath(uri: Uri): String? {
    val id = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        return null
    }
    val parts = id.split(":", limit = 2)
    val vol = parts[0]
    val rel = parts.getOrNull(1) ?: ""
    val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** Pochette : miniature Android (API 29+), sinon image intégrée au fichier. */
fun loadCoverBlocking(ctx: Context, uri: Uri, px: Int = 600): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            return ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } catch (_: Exception) {
        }
    }
    return try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } finally {
            r.release()
        }
    } catch (_: Exception) {
        null
    }
}

/** Permet à la notification et à l'écran verrouillé d'afficher la pochette du fichier audio. */
class CoverBitmapLoader(private val ctx: Context) : BitmapLoader {
    private val exec = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        exec.submit(Callable<Bitmap> {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalStateException("Image illisible")
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        exec.submit(Callable<Bitmap> {
            loadCoverBlocking(ctx, uri) ?: throw FileNotFoundException("Pas de pochette")
        })
}

/** Paroles intégrées au fichier (ID3 USLT pour le MP3, tag LYRICS pour FLAC/OGG). */
suspend fun loadLyrics(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val groups = MetadataRetriever.retrieveMetadata(ctx, MediaItem.fromUri(uri)).get()
        for (i in 0 until groups.length) {
            val g = groups.get(i)
            for (j in 0 until g.length) {
                val md = g.getFormat(j).metadata ?: continue
                for (k in 0 until md.length()) {
                    when (val e = md.get(k)) {
                        is BinaryFrame ->
                            if (e.id == "USLT" || e.id == "ULT")
                                parseUslt(e.data)?.let { return@withContext it }
                        is CommentFrame ->
                            if (e.id == "USLT" || e.id == "ULT")
                                e.text.trim().takeIf { it.isNotEmpty() }?.let { return@withContext it }
                        is VorbisComment ->
                            if (e.key.equals("LYRICS", true) || e.key.equals("UNSYNCEDLYRICS", true))
                                e.value.trim().takeIf { it.isNotEmpty() }?.let { return@withContext it }
                        else -> {}
                    }
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

private fun parseUslt(d: ByteArray): String? {
    if (d.size < 5) return null
    val enc = d[0].toInt()
    val cs = when (enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    var i = 4 // 1 octet d'encodage + 3 octets de langue
    if (enc == 1 || enc == 2) {
        while (i + 1 < d.size && !(d[i].toInt() == 0 && d[i + 1].toInt() == 0)) i += 2
        i += 2
    } else {
        while (i < d.size && d[i].toInt() != 0) i++
        i++
    }
    if (i >= d.size) return null
    return String(d, i, d.size - i, cs).trim().ifEmpty { null }
}

/**
 * Playlists : nom -> liste de chemins de fichiers.
 * Les anciennes playlists (identifiants numériques) sont converties au premier scan.
 */
class PlaylistStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    fun load(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        try {
            val o = JSONObject(sp.getString("data", "{}") ?: "{}")
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                out[k] = List(a.length()) { a.get(it).toString() }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(m: Map<String, List<String>>) {
        val o = JSONObject()
        for ((k, v) in m) {
            val a = JSONArray()
            v.forEach { a.put(it) }
            o.put(k, a)
        }
        sp.edit().putString("data", o.toString()).apply()
    }
}

/** Favoris : chemins de fichiers (les anciens identifiants sont convertis au premier scan). */
class FavStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("favs", Context.MODE_PRIVATE)

    fun load(): MutableSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val s = sp.getString("keys", null)
            if (s != null) {
                val a = JSONArray(s)
                for (i in 0 until a.length()) out.add(a.getString(i))
            } else {
                (sp.getString("ids", "") ?: "").split(",").filter { it.isNotBlank() }.forEach { out.add(it) }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(keys: Collection<String>) {
        val a = JSONArray()
        keys.forEach { a.put(it) }
        sp.edit().putString("keys", a.toString()).remove("ids").apply()
    }

    /** Ajoute ou retire un favori ; renvoie true s'il est maintenant favori. */
    fun toggle(key: String): Boolean {
        val s = load()
        val now = if (s.contains(key)) { s.remove(key); false } else { s.add(key); true }
        save(s)
        return now
    }
}

/** Compteur d'écoutes (un titre compte à partir de 30 s d'écoute). */
class PlayStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun load(): Map<String, Int> {
        val out = HashMap<String, Int>()
        try {
            val o = JSONObject(sp.getString("counts", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getInt(k)
        } catch (_: Exception) {
        }
        return out
    }

    fun inc(key: String) {
        try {
            val o = JSONObject(sp.getString("counts", "{}") ?: "{}")
            o.put(key, o.optInt(key, 0) + 1)
            sp.edit().putString("counts", o.toString()).apply()
        } catch (_: Exception) {
        }
    }
}

/** Titres / artistes modifiés dans l'appli (le fichier audio n'est pas touché), par chemin. */
class MetaStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("meta2", Context.MODE_PRIVATE)

    fun load(): Map<String, Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        try {
            val o = JSONObject(sp.getString("data", "{}") ?: "{}")
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                out[k] = Pair(e.optString("t"), e.optString("a"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(m: Map<String, Pair<String, String>>) {
        val o = JSONObject()
        for ((path, v) in m) {
            o.put(path, JSONObject().put("t", v.first).put("a", v.second))
        }
        sp.edit().putString("data", o.toString()).apply()
    }
}
