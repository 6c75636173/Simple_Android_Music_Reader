@file:OptIn(UnstableApi::class)

package com.lucas.musique

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val uri: Uri
)

sealed interface Entry {
    data class Header(val text: String) : Entry
    data class Item(val track: Track) : Entry
}

private const val TAB_PLAYLISTS = 4
private val TABS = listOf("Titres", "Artistes", "Albums", "Dossiers", "Playlists")

class MainActivity : ComponentActivity() {

    private val controller = mutableStateOf<MediaController?>(null)
    private val tracks = mutableStateListOf<Track>()
    private var future: ListenableFuture<MediaController>? = null
    private val ui by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadTracks()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var mode by remember { mutableIntStateOf(ui.getInt("theme", 0)) } // 0 auto, 1 clair, 2 sombre
            var dyn by remember { mutableStateOf(ui.getBoolean("dyn", true)) }
            val dark = when (mode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val ctx = LocalContext.current
            val view = LocalView.current
            SideEffect {
                val ic = WindowCompat.getInsetsController(window, view)
                ic.isAppearanceLightStatusBars = !dark
                ic.isAppearanceLightNavigationBars = !dark
            }
            val scheme = when {
                dyn && Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    PlayerScreen(
                        c = controller.value,
                        tracks = tracks,
                        themeMode = mode,
                        dynamic = dyn,
                        onTheme = { mode = it; ui.edit().putInt("theme", it).apply() },
                        onDynamic = { dyn = it; ui.edit().putBoolean("dyn", it).apply() }
                    )
                }
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
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATA
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
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: ""
                    val album = c.getString(iAlbum) ?: ""
                    val folder = c.getString(iData)?.let { File(it).parentFile?.name } ?: ""
                    list += Track(
                        id, c.getString(iTitle) ?: "Sans titre", artist, album, folder,
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    )
                }
            }
            runOnUiThread { tracks.clear(); tracks.addAll(list) }
        }
    }
}

private fun toItem(t: Track): MediaItem =
    MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(t.uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(t.uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(t.uri) // lu par CoverBitmapLoader pour la notification
                .build()
        )
        .build()

private fun buildEntries(list: List<Track>, tab: Int): List<Entry> {
    if (tab == 0) return list.map { Entry.Item(it) }
    val key: (Track) -> String = when (tab) {
        1 -> { t -> t.artist.ifEmpty { "Artiste inconnu" } }
        2 -> { t -> t.album.ifEmpty { "Album inconnu" } }
        else -> { t -> t.folder.ifEmpty { "Dossier inconnu" } }
    }
    return list.groupBy(key)
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .flatMap { (k, v) -> listOf<Entry>(Entry.Header(k)) + v.map { Entry.Item(it) } }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
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
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    if (selected) Button(onClick = onClick, contentPadding = pad) { Text(text) }
    else OutlinedButton(onClick = onClick, contentPadding = pad) { Text(text) }
}

@Composable
private fun TrackRow(
    t: Track,
    current: Boolean,
    playlists: Map<String, List<Long>>,
    inPlaylist: String?,
    onPlay: () -> Unit,
    onAdd: (String) -> Unit,
    onNew: () -> Unit,
    onRemove: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { onPlay() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
            )
            if (t.artist.isNotEmpty())
                Text(
                    t.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }
        Box {
            TextButton(onClick = { open = true }) { Text("⋮", fontSize = 20.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (inPlaylist != null) {
                    DropdownMenuItem(
                        text = { Text("Retirer de la playlist") },
                        onClick = { open = false; onRemove() }
                    )
                }
                playlists.keys.sorted().forEach { name ->
                    DropdownMenuItem(
                        text = { Text("Ajouter à « $name »") },
                        onClick = { open = false; onAdd(name) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Nouvelle playlist…") },
                    onClick = { open = false; onNew() }
                )
            }
        }
    }
}

@Composable
fun PlayerScreen(
    c: MediaController?,
    tracks: List<Track>,
    themeMode: Int,
    dynamic: Boolean,
    onTheme: (Int) -> Unit,
    onDynamic: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val rp = remember { ctx.getSharedPreferences("resume", Context.MODE_PRIVATE) }
    val ap = remember { ctx.getSharedPreferences("audio", Context.MODE_PRIVATE) }
    val store = remember { PlaylistStore(ctx) }
    val playlists = remember { mutableStateMapOf<String, List<Long>>().apply { putAll(store.load()) } }

    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableLongStateOf(-1L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var openPl by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Track?>(null) }

    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var lyricsFor by remember { mutableLongStateOf(-1L) }

    var bass by remember { mutableFloatStateOf(ap.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(ap.getFloat("treble", 0f)) }
    var norm by remember { mutableStateOf(ap.getBoolean("norm", false)) }

    val current = remember(curId, tracks.size) { tracks.firstOrNull { it.id == curId } }
    val byId = remember(tracks.size) { tracks.associateBy { it.id } }

    fun sync(p: Player) {
        playing = p.isPlaying
        curId = p.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        dur = if (p.duration > 0) p.duration else 0L
    }

    fun toggle() {
        c?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun playList(list: List<Track>, id: Long) {
        val start = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        c?.let {
            it.setMediaItems(list.map(::toItem), start, 0L)
            it.prepare()
            it.play()
        }
    }

    fun persist() = store.save(playlists.toMap())

    fun addToPlaylist(name: String, t: Track) {
        val l = playlists[name] ?: emptyList()
        if (t.id !in l) {
            playlists[name] = l + t.id
            persist()
        }
    }

    fun sendAudio() {
        val args = Bundle().apply {
            putFloat("bass", bass)
            putFloat("treble", treble)
            putBoolean("norm", norm)
        }
        c?.sendCustomCommand(SessionCommand(CMD_AUDIO, Bundle.EMPTY), args)
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

    // File de lecture initiale + reprise au dernier titre
    LaunchedEffect(c, tracks.size) {
        if (c != null && tracks.isNotEmpty() && c.mediaItemCount == 0) {
            val lastId = rp.getLong("lastId", -1L)
            val idx = tracks.indexOfFirst { it.id == lastId }
            val start = idx.coerceAtLeast(0)
            val startPos = if (idx >= 0) rp.getLong("lastPos", 0L) else 0L
            c.setMediaItems(tracks.map(::toItem), start, startPos)
            c.prepare()
        }
    }

    // Position de lecture + sauvegarde régulière
    LaunchedEffect(c) {
        var n = 0
        while (true) {
            if (c != null && !drag) pos = c.currentPosition
            n++
            if (c != null && n % 4 == 0 && curId >= 0) {
                rp.edit().putLong("lastId", curId).putLong("lastPos", c.currentPosition).apply()
            }
            delay(500)
        }
    }

    // Pochette
    LaunchedEffect(current?.id) {
        cover = null
        val t = current
        if (t != null) cover = withContext(Dispatchers.IO) { loadCoverBlocking(ctx, t.uri) }
    }

    // Paroles
    LaunchedEffect(current?.id, showLyrics, expanded) {
        val t = current
        if (expanded && showLyrics && t != null && lyricsFor != t.id) {
            lyrics = loadLyrics(ctx, t.uri)
            lyricsFor = t.id
        }
    }

    BackHandler(enabled = expanded) { expanded = false }
    BackHandler(enabled = !expanded && tab == TAB_PLAYLISTS && openPl != null) { openPl = null }

    // ---------- Dialogue : nouvelle playlist ----------
    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false; pending = null },
            title = { Text("Nouvelle playlist") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Nom") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty() && !playlists.containsKey(n)) {
                        playlists[n] = listOfNotNull(pending?.id)
                        persist()
                    }
                    showNew = false; pending = null
                }) { Text("Créer") }
            },
            dismissButton = {
                TextButton(onClick = { showNew = false; pending = null }) { Text("Annuler") }
            }
        )
    }

    // ---------- Dialogue : réglages ----------
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Réglages") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Thème", fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Chip("Auto", themeMode == 0) { onTheme(0) }
                        Chip("Clair", themeMode == 1) { onTheme(1) }
                        Chip("Sombre", themeMode == 2) { onTheme(2) }
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Couleurs du fond d’écran", Modifier.weight(1f))
                            Switch(checked = dynamic, onCheckedChange = onDynamic)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Égaliseur", fontWeight = FontWeight.SemiBold)
                    Text("Graves", fontSize = 12.sp)
                    Slider(
                        value = bass, onValueChange = { bass = it; sendAudio() },
                        valueRange = -1f..1f
                    )
                    Text("Aigus", fontSize = 12.sp)
                    Slider(
                        value = treble, onValueChange = { treble = it; sendAudio() },
                        valueRange = -1f..1f
                    )
                    TextButton(onClick = { bass = 0f; treble = 0f; sendAudio() }) {
                        Text("Réinitialiser")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Normaliser le volume", Modifier.weight(1f))
                        Switch(checked = norm, onCheckedChange = { norm = it; sendAudio() })
                    }
                    Text(
                        "Lecture sans blanc : automatique quand les fichiers le permettent.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Fermer") } }
        )
    }

    if (expanded && current != null) {
        // ---------- Lecteur plein écran ----------
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = false }) { Text("⌄ Réduire") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLyrics = !showLyrics }) {
                    Text(if (showLyrics) "🖼 Pochette" else "📝 Paroles")
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showLyrics) {
                    Text(
                        if (lyricsFor != current.id) "Chargement…"
                        else lyrics ?: "Aucune parole intégrée dans ce fichier.",
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                } else {
                    Cover(cover, Modifier.fillMaxWidth().aspectRatio(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
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
        }
    } else {
        // ---------- Bibliothèque + barre de lecture ----------
        val filtered = remember(tracks.size, query) {
            val q = query.trim()
            if (q.isEmpty()) tracks.toList()
            else tracks.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
            }
        }
        val entries = remember(filtered, tab) { buildEntries(filtered, tab) }
        val shown = remember(entries) { entries.filterIsInstance<Entry.Item>().map { it.track } }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Ma musique", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 22.sp) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Rechercher un titre, un artiste…") },
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TABS.forEachIndexed { i, name ->
                    Chip(name, tab == i) { tab = i; openPl = null }
                }
            }

            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (tab == TAB_PLAYLISTS && openPl == null) {
                    // Liste des playlists
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Button(
                                onClick = { pending = null; showNew = true },
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) { Text("＋ Nouvelle playlist") }
                        }
                        items(playlists.keys.sorted()) { name ->
                            Column(
                                Modifier.fillMaxWidth().clickable { openPl = name }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${playlists[name]?.size ?: 0} titre(s)", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (tab == TAB_PLAYLISTS) {
                    // Contenu d'une playlist
                    val name = openPl ?: ""
                    val plTracks = (playlists[name] ?: emptyList()).mapNotNull { byId[it] }
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { openPl = null }) { Text("‹ Retour") }
                            Text(
                                name, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = {
                                if (plTracks.isNotEmpty()) playList(plTracks, plTracks.first().id)
                            }) { Text("▶ Tout lire") }
                            TextButton(onClick = {
                                playlists.remove(name); persist(); openPl = null
                            }) { Text("🗑") }
                        }
                        if (plTracks.isEmpty()) {
                            Text(
                                "Playlist vide. Ajoute des titres avec le menu ⋮ de la liste.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(plTracks) { t ->
                                TrackRow(
                                    t, t.id == curId, playlists, name,
                                    onPlay = { playList(plTracks, t.id) },
                                    onAdd = { addToPlaylist(it, t) },
                                    onNew = { pending = t; showNew = true },
                                    onRemove = {
                                        playlists[name] = (playlists[name] ?: emptyList()) - t.id
                                        persist()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Titres / Artistes / Albums / Dossiers
                    if (shown.isEmpty()) {
                        Text(
                            if (tracks.isEmpty()) "Aucune musique trouvée dans le dossier Music"
                            else "Aucun résultat",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries) { e ->
                            when (e) {
                                is Entry.Header -> Text(
                                    e.text, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                                )
                                is Entry.Item -> TrackRow(
                                    e.track, e.track.id == curId, playlists, null,
                                    onPlay = { playList(shown, e.track.id) },
                                    onAdd = { addToPlaylist(it, e.track) },
                                    onNew = { pending = e.track; showNew = true },
                                    onRemove = {}
                                )
                            }
                        }
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
