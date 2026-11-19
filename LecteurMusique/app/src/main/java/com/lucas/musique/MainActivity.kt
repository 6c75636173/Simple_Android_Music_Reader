package com.lucas.musique

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

data class Track(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {

    private val controller = mutableStateOf<MediaController?>(null)
    private val tracks = mutableStateListOf<Track>()
    private var future: ListenableFuture<MediaController>? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadTracks()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { PlayerScreen(controller.value, tracks) }
            }
        }
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permLauncher.launch(perms.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({ controller.value = f.get() }, ContextCompat.getMainExecutor(this))
        future = f
    }

    override fun onStop() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller.value = null
        super.onStop()
    }

    private fun loadTracks() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return
        thread {
            val list = mutableListOf<Track>()
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?",
                arrayOf("/storage/emulated/0/Music/%", "/storage/%-%/Music/%"),
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: ""
                    list += Track(
                        id, c.getString(iTitle) ?: "Sans titre", artist,
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    )
                }
            }
            runOnUiThread { tracks.clear(); tracks.addAll(list) }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Charge la pochette : miniature Android (API 29+), sinon image intégrée au fichier. */
private suspend fun loadCover(ctx: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            return@withContext ctx.contentResolver.loadThumbnail(uri, Size(600, 600), null)
        } catch (_: Exception) {
        }
    }
    try {
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

@Composable
private fun Cover(bmp: Bitmap?, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("♪", fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PlayerScreen(c: MediaController?, tracks: List<Track>) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(-1) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }

    val current = tracks.getOrNull(index)

    fun sync(p: Player) {
        playing = p.isPlaying
        index = p.currentMediaItemIndex
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        dur = if (p.duration > 0) p.duration else 0L
    }

    fun toggle() {
        c?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    DisposableEffect(c) {
        if (c == null) {
            onDispose { }
        } else {
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync(player)
            }
            sync(c)
            c.addListener(l)
            onDispose { c.removeListener(l) }
        }
    }

    // Charge la bibliothèque dans la file de lecture (une seule fois)
    LaunchedEffect(c, tracks.size) {
        if (c != null && tracks.isNotEmpty() && c.mediaItemCount == 0) {
            c.setMediaItems(tracks.map {
                MediaItem.Builder()
                    .setMediaId(it.id.toString())
                    .setUri(it.uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(it.uri).build())
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()
                    )
                    .build()
            })
            c.prepare()
        }
    }

    // Position de lecture
    LaunchedEffect(c) {
        while (true) {
            if (c != null && !drag) pos = c.currentPosition
            delay(500)
        }
    }

    // Pochette du titre en cours
    LaunchedEffect(current?.id) {
        cover = null
        if (current != null) cover = loadCover(ctx, current.uri)
    }

    BackHandler(enabled = expanded) { expanded = false }

    if (expanded && current != null) {
        // ---------- Lecteur plein écran avec pochette ----------
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { expanded = false }) { Text("⌄ Réduire") }
            }
            Spacer(Modifier.weight(1f))
            Cover(cover, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(24.dp))
            Text(
                current.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            if (current.artist.isNotEmpty())
                Text(
                    current.artist, fontSize = 14.sp, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = pos.toFloat().coerceIn(0f, maxOf(dur, 1L).toFloat()),
                onValueChange = { drag = true; pos = it.toLong() },
                onValueChangeFinished = { c?.seekTo(pos); drag = false },
                valueRange = 0f..maxOf(dur, 1L).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), fontSize = 12.sp)
                Text(fmt(dur), fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { c?.shuffleModeEnabled = !shuffle }) {
                    Text("🔀", fontSize = 24.sp, modifier = Modifier.alpha(if (shuffle) 1f else 0.35f))
                }
                TextButton(onClick = { c?.seekToPrevious() }) { Text("⏮", fontSize = 28.sp) }
                Button(onClick = { toggle() }) {
                    Text(if (playing) "⏸" else "▶", fontSize = 28.sp)
                }
                TextButton(onClick = { c?.seekToNext() }) { Text("⏭", fontSize = 28.sp) }
                TextButton(onClick = {
                    c?.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Text(
                        if (repeat == Player.REPEAT_MODE_ONE) "🔂" else "🔁", fontSize = 24.sp,
                        modifier = Modifier.alpha(if (repeat == Player.REPEAT_MODE_OFF) 0.35f else 1f)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    } else {
        // ---------- Liste + barre de lecture en bas ----------
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Text(
                "Ma musique", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(16.dp)
            )
            if (tracks.isEmpty()) {
                Text(
                    "Aucune musique trouvée dans le dossier Music",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                itemsIndexed(tracks) { i, t ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { c?.let { it.seekToDefaultPosition(i); it.play() } }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (i == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal
                        )
                        if (t.artist.isNotEmpty())
                            Text(
                                t.artist, fontSize = 12.sp, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    }
                }
            }
            if (current != null) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                ) {
                    Column {
                        LinearProgressIndicator(
                            progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Cover(cover, Modifier.size(52.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    current.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (current.artist.isNotEmpty())
                                    Text(
                                        current.artist, fontSize = 12.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                            }
                            TextButton(onClick = { toggle() }) {
                                Text(if (playing) "⏸" else "▶", fontSize = 24.sp)
                            }
                            TextButton(onClick = { c?.seekToNext() }) { Text("⏭", fontSize = 24.sp) }
                        }
                    }
                }
            }
        }
    }
}