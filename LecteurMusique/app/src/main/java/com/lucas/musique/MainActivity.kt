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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

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

private const val TAB_FAV = 4
private const val TAB_PLAYLISTS = 5
private val TABS = listOf("Titres", "Artistes", "Albums", "Dossiers", "Favoris", "Playlists")

class MainActivity : ComponentActivity() {

    private val controller = mutableStateOf<MediaController?>(null)
    private val tracks = mutableStateListOf<Track>()
    private val folders = mutableStateListOf<String>()
    private val rev = mutableIntStateOf(0)
    private var future: ListenableFuture<MediaController>? = null
    private val ui by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val metaStore by lazy { MetaStore(this) }
    private val meta = LinkedHashMap<Long, Pair<String, String>>()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadTracks()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folders.addAll(readFolders())
        meta.putAll(metaStore.load())
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
                        rev = rev.intValue,
                        folders = folders,
                        themeMode = mode,
                        dynamic = dyn,
                        onTheme = { mode = it; ui.edit().putInt("theme", it).apply() },
                        onDynamic = { dyn = it; ui.edit().putBoolean("dyn", it).apply() },
                        onFolders = { setFolders(it) },
                        onEdit = { t, title, artist -> editTrack(t, title, artist) }
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

    private fun readFolders(): List<String> {
        val s = ui.getString("folders", null) ?: return DEFAULT_FOLDERS
        return try {
            val a = JSONArray(s)
            List(a.length()) { a.getString(it) }.ifEmpty { DEFAULT_FOLDERS }
        } catch (_: Exception) {
            DEFAULT_FOLDERS
        }
    }

    private fun setFolders(list: List<String>) {
        val l = list.ifEmpty { DEFAULT_FOLDERS }
        folders.clear()
        folders.addAll(l)
        ui.edit().putString("folders", JSONArray(l).toString()).apply()
        loadTracks()
    }

    private fun editTrack(t: Track, title: String, artist: String) {
        val nt = title.trim().ifEmpty { t.title }
        val na = artist.trim()
        meta[t.id] = Pair(nt, na)
        metaStore.save(meta)
        val i = tracks.indexOfFirst { it.id == t.id }
        if (i >= 0) tracks[i] = t.copy(title = nt, artist = na)
        rev.intValue = rev.intValue + 1
    }

    private fun loadTracks() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return
        val paths = folders.toList().ifEmpty { DEFAULT_FOLDERS }
        thread {
            val list = mutableListOf<Track>()
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATA
            )
            val selection = paths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            val args = paths.map { it.trimEnd('/') + "/%" }.toTypedArray()
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, selection, args,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    var title = c.getString(iTitle) ?: "Sans titre"
                    var artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: ""
                    meta[id]?.let { title = it.first; artist = it.second }
                    val album = c.getString(iAlbum) ?: ""
                    val folder = c.getString(iData)?.let { File(it).parentFile?.name } ?: ""
                    list += Track(
                        id, title, artist, album, folder,
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
    fav: Boolean,
    playlists: Map<String, List<Long>>,
    inPlaylist: String?,
    onPlay: () -> Unit,
    onFav: () -> Unit,
    onAdd: (String) -> Unit,
    onNew: () -> Unit,
    onEdit: () -> Unit,
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
        TextButton(onClick = onFav) { Text(if (fav) "❤" else "♡", fontSize = 18.sp) }
        Box {
            TextButton(onClick = { open = true }) { Text("⋮", fontSize = 20.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Modifier les infos") },
                    onClick = { open = false; onEdit() }
                )
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
    rev: Int,
    folders: List<String>,
    themeMode: Int,
    dynamic: Boolean,
    onTheme: (Int) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onFolders: (List<String>) -> Unit,
    onEdit: (Track, String, String) -> Unit
) {
    val ctx = LocalContext.current
    val rp = remember { ctx.getSharedPreferences("resume", Context.MODE_PRIVATE) }
    val ap = remember { ctx.getSharedPreferences("audio", Context.MODE_PRIVATE) }
    val store = remember { PlaylistStore(ctx) }
    val favStore = remember { FavStore(ctx) }
    val playlists = remember { mutableStateMapOf<String, List<Long>>().apply { putAll(store.load()) } }
    val favs = remember { mutableStateListOf<Long>().apply { addAll(favStore.load()) } }

    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableLongStateOf(-1L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var speed by remember { mutableFloatStateOf(1f) }
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
    var showTimer by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Track?>(null) }
    var editing by remember { mutableStateOf<Track?>(null) }

    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var lyricsFor by remember { mutableLongStateOf(-1L) }

    var bass by remember { mutableFloatStateOf(ap.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(ap.getFloat("treble", 0f)) }
    var norm by remember { mutableStateOf(ap.getBoolean("norm", false)) }
    var xfade by remember { mutableFloatStateOf(ap.getInt("xfade", 0).toFloat()) }
    var sleepEnd by remember { mutableLongStateOf(0L) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val p = treeUriToPath(uri)
            if (p != null && p !in folders) onFolders(folders + p)
        }
    }

    val current = tracks.firstOrNull { it.id == curId }

    fun sync(p: Player) {
        playing = p.isPlaying
        curId = p.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        speed = p.playbackParameters.speed
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

    fun toggleFav(id: Long) {
        if (id in favs) favs.remove(id) else favs.add(id)
        favStore.save(favs.toSet())
    }

    fun sendAudio() {
        val args = Bundle().apply {
            putFloat("bass", bass)
            putFloat("treble", treble)
            putBoolean("norm", norm)
            putInt("xfade", xfade.roundToInt())
        }
        c?.sendCustomCommand(SessionCommand(CMD_AUDIO, Bundle.EMPTY), args)
    }

    fun setSleep(min: Int) {
        sleepEnd = if (min > 0) System.currentTimeMillis() + min * 60_000L else 0L
        val args = Bundle().apply { putInt("min", min) }
        c?.sendCustomCommand(SessionCommand(CMD_SLEEP, Bundle.EMPTY), args)
    }

    fun cycleSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val i = speeds.indexOfFirst { abs(it - speed) < 0.01f }
        c?.setPlaybackSpeed(speeds[(i + 1) % speeds.size])
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

    // Envoie les réglages au service dès la connexion (fondu enchaîné, égaliseur...)
    LaunchedEffect(c) {
        if (c != null) sendAudio()
    }

    // Position de lecture + sauvegarde régulière + minuteur
    LaunchedEffect(c) {
        var n = 0
        while (true) {
            if (c != null && !drag) pos = c.currentPosition
            sleepEnd = ap.getLong("sleepEnd", 0L)
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

    val now = System.currentTimeMillis()
    val remainMin = if (sleepEnd > now) ((sleepEnd - now) / 60_000L + 1).toInt() else 0

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

    // ---------- Dialogue : modifier les infos ----------
    editing?.let { et ->
        var nt by remember(et.id) { mutableStateOf(et.title) }
        var na by remember(et.id) { mutableStateOf(et.artist) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifier les infos") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nt, onValueChange = { nt = it },
                        singleLine = true, label = { Text("Titre") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = na, onValueChange = { na = it },
                        singleLine = true, label = { Text("Artiste") }
                    )
                    Text(
                        "Change l’affichage dans l’appli, pas le fichier audio.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onEdit(et, nt, na); editing = null }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } }
        )
    }

    // ---------- Dialogue : minuteur d'arrêt ----------
    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Minuteur d’arrêt") },
            text = {
                Column {
                    Text(if (remainMin > 0) "La musique s’arrête dans $remainMin min" else "Désactivé")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Chip("Off", remainMin == 0) { setSleep(0) }
                        Chip("15 min", false) { setSleep(15); showTimer = false }
                        Chip("30 min", false) { setSleep(30); showTimer = false }
                        Chip("60 min", false) { setSleep(60); showTimer = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTimer = false }) { Text("Fermer") } }
        )
    }

    // ---------- Dialogue : dossiers scannés ----------
    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("Dossiers de musique") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Toute la musique de ces dossiers (et sous-dossiers) apparaît dans l’appli.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    folders.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(folderLabel(p), Modifier.weight(1f), fontSize = 14.sp)
                            TextButton(onClick = { onFolders(folders.filter { it != p }) }) { Text("✕") }
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Chip("＋ Ajouter un dossier", true) { picker.launch(null) }
                        Chip("Par défaut", false) { onFolders(DEFAULT_FOLDERS) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFolders = false }) { Text("Fermer") } }
        )
    }

    // ---------- Dialogue : réglages ----------
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Réglages") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Fondu enchaîné : " +
                            if (xfade.roundToInt() == 0) "désactivé" else "${xfade.roundToInt()} s",
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = xfade,
                        onValueChange = { xfade = it },
                        onValueChangeFinished = { sendAudio() },
                        valueRange = 0f..12f,
                        steps = 11
                    )
                    Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showSettings = false; showFolders = true }) {
                        Text("Dossiers de musique…")
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
        // ---------- Lecteur plein écran (tirer vers le bas pour fermer) ----------
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = { if (dy > 220f) expanded = false },
                        onVerticalDrag = { _, d -> dy += d }
                    )
                },
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { cycleSpeed() }) {
                    Text(if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×", fontSize = 18.sp)
                }
                TextButton(onClick = { toggleFav(current.id) }) {
                    Text(if (current.id in favs) "❤" else "♡", fontSize = 22.sp)
                }
                TextButton(onClick = { showTimer = true }) {
                    Text(if (remainMin > 0) "⏲ $remainMin min" else "⏲", fontSize = 18.sp)
                }
            }
        }
    } else {
        // ---------- Bibliothèque + barre de lecture ----------
        val filtered = remember(tracks.size, query, rev) {
            val q = query.trim()
            if (q.isEmpty()) tracks.toList()
            else tracks.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
            }
        }
        val entries = remember(filtered, tab, favs.size) {
            if (tab == TAB_FAV) buildEntries(filtered.filter { it.id in favs }, 0)
            else buildEntries(filtered, tab)
        }
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
                TextButton(onClick = { showTimer = true }) {
                    Text(if (remainMin > 0) "⏲ $remainMin min" else "⏲", fontSize = 20.sp)
                }
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
                    val byId = tracks.associateBy { it.id }
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
                                    t, t.id == curId, t.id in favs, playlists, name,
                                    onPlay = { playList(plTracks, t.id) },
                                    onFav = { toggleFav(t.id) },
                                    onAdd = { addToPlaylist(it, t) },
                                    onNew = { pending = t; showNew = true },
                                    onEdit = { editing = t },
                                    onRemove = {
                                        playlists[name] = (playlists[name] ?: emptyList()) - t.id
                                        persist()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Titres / Artistes / Albums / Dossiers / Favoris
                    if (shown.isEmpty()) {
                        Text(
                            when {
                                tab == TAB_FAV -> "Aucun favori : touche ♡ sur un titre."
                                tracks.isEmpty() -> "Aucune musique trouvée dans les dossiers choisis"
                                else -> "Aucun résultat"
                            },
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
                                    e.track, e.track.id == curId, e.track.id in favs, playlists, null,
                                    onPlay = { playList(shown, e.track.id) },
                                    onFav = { toggleFav(e.track.id) },
                                    onAdd = { addToPlaylist(it, e.track) },
                                    onNew = { pending = e.track; showNew = true },
                                    onEdit = { editing = e.track },
                                    onRemove = {}
                                )
                            }
                        }
                    }
                }
            }

            if (current != null) {
                // Barre de lecture : toucher = plein écran, glisser = titre suivant / précédent
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                        .pointerInput(c) {
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dx = 0f },
                                onDragEnd = {
                                    if (dx < -120f) c?.seekToNext()
                                    else if (dx > 120f) c?.seekToPrevious()
                                },
                                onHorizontalDrag = { _, d -> dx += d }
                            )
                        }
                        .clickable { expanded = true }
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
