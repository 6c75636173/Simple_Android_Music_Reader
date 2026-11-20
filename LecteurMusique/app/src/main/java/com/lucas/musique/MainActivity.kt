package com.lucas.musique

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val uri: Uri,
    val path: String,
    val durationMs: Long,
    val dateAdded: Long,
    val origTitle: String,
    val origArtist: String
)

sealed interface Entry {
    data class Header(val text: String) : Entry
    data class Item(val track: Track) : Entry
}

private const val TAB_FAV = 4
private const val TAB_PLAYLISTS = 5
private const val TAB_AUTO = 6
private val TABS = listOf("Titres", "Artistes", "Albums", "Dossiers", "Favoris", "Playlists", "Auto")
private val SORTS = listOf("Titre (A→Z)", "Artiste", "Ajout récent", "Durée ↓", "Durée ↑")
private val AUTOS = listOf("Récemment ajoutés", "Les plus écoutés", "Jamais écoutés")

class MainActivity : ComponentActivity() {

    private val controller = mutableStateOf<MediaController?>(null)
    private val tracks = mutableStateListOf<Track>()
    private val folders = mutableStateListOf<String>()
    private val rev = mutableIntStateOf(0)
    private var future: ListenableFuture<MediaController>? = null
    private val ui by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val metaStore by lazy { MetaStore(this) }
    private val meta = LinkedHashMap<String, Pair<String, String>>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val reload = Runnable { loadTracks() }
    private val observer = object : ContentObserver(uiHandler) {
        override fun onChange(selfChange: Boolean) {
            uiHandler.removeCallbacks(reload)
            uiHandler.postDelayed(reload, 1000)
        }
    }

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
                        onEdit = { t, title, artist -> editTrack(t, title, artist) },
                        onReset = { resetTrack(it) },
                        onRescan = { rescan() }
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
        // Actualisation automatique de la bibliothèque
        contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        loadTracks()
    }

    override fun onStop() {
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        uiHandler.removeCallbacks(reload)
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
        rescan()
    }

    private fun editTrack(t: Track, title: String, artist: String) {
        val nt = title.trim().ifEmpty { t.title }
        val na = artist.trim()
        meta[t.path] = Pair(nt, na)
        metaStore.save(meta)
        val i = tracks.indexOfFirst { it.id == t.id }
        if (i >= 0) tracks[i] = t.copy(title = nt, artist = na)
        rev.intValue = rev.intValue + 1
    }

    /** Rétablit le titre et l'artiste d'origine du fichier. */
    private fun resetTrack(t: Track) {
        meta.remove(t.path)
        metaStore.save(meta)
        val i = tracks.indexOfFirst { it.id == t.id }
        if (i >= 0) tracks[i] = t.copy(title = t.origTitle, artist = t.origArtist)
        rev.intValue = rev.intValue + 1
    }

    /** Demande à Android de re-scanner les dossiers choisis, puis recharge la liste. */
    private fun rescan() {
        val dirs = folders.filter { !it.contains('%') }.toTypedArray()
        loadTracks()
        if (dirs.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, dirs, null) { _, _ ->
                uiHandler.removeCallbacks(reload)
                uiHandler.postDelayed(reload, 800)
            }
        }
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
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED
            )
            val selection = paths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            val args = paths.map { it.trimEnd('/') + "/%" }.toTypedArray()
            try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, selection, args,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val iAdd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val path = c.getString(iData) ?: id.toString()
                        val origTitle = c.getString(iTitle) ?: "Sans titre"
                        val origArtist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: ""
                        var title = origTitle
                        var artist = origArtist
                        meta[path]?.let { title = it.first; artist = it.second }
                        val album = c.getString(iAlbum) ?: ""
                        val folder = File(path).parentFile?.name ?: ""
                        list += Track(
                            id, title, artist, album, folder,
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            path, c.getLong(iDur), c.getLong(iAdd), origTitle, origArtist
                        )
                    }
                }
            } catch (_: Exception) {
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
                .setExtras(Bundle().apply { putString("path", t.path) })
                .build()
        )
        .build()

private fun sortTracks(l: List<Track>, s: Int): List<Track> = when (s) {
    1 -> l.sortedBy { it.artist.lowercase() }
    2 -> l.sortedByDescending { it.dateAdded }
    3 -> l.sortedByDescending { it.durationMs }
    4 -> l.sortedBy { it.durationMs }
    else -> l.sortedBy { it.title.lowercase() }
}

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
private fun Ico(res: Int, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Icon(painterResource(res), contentDescription = null, modifier = modifier.size(size), tint = tint)
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
    badge: String,
    playlists: Map<String, List<String>>,
    inPlaylist: String?,
    onPlay: () -> Unit,
    onFav: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
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
            val sub = listOf(t.artist, badge).filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (sub.isNotEmpty())
                Text(
                    sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }
        IconButton(onClick = onFav) {
            Ico(
                if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                tint = if (fav) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }
        Box {
            IconButton(onClick = { open = true }) { Ico(R.drawable.ic_more_vert) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = { open = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Ajouter à la file") }, onClick = { open = false; onQueue() })
                DropdownMenuItem(text = { Text("Modifier les infos") }, onClick = { open = false; onEdit() })
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
                DropdownMenuItem(text = { Text("Nouvelle playlist…") }, onClick = { open = false; onNew() })
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
    onEdit: (Track, String, String) -> Unit,
    onReset: (Track) -> Unit,
    onRescan: () -> Unit
) {
    val ctx = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rp = remember { ctx.getSharedPreferences("resume", Context.MODE_PRIVATE) }
    val ap = remember { ctx.getSharedPreferences("audio", Context.MODE_PRIVATE) }
    val store = remember { PlaylistStore(ctx) }
    val favStore = remember { FavStore(ctx) }
    val statStore = remember { PlayStore(ctx) }
    val playlists = remember { mutableStateMapOf<String, List<String>>().apply { putAll(store.load()) } }
    val favs = remember { mutableStateListOf<String>().apply { addAll(favStore.load()) } }
    var statsRev by remember { mutableIntStateOf(0) }
    val stats = remember(statsRev) { statStore.load() }

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
    var queueRev by remember { mutableIntStateOf(0) }

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var autoMode by remember { mutableIntStateOf(0) }
    var openPl by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Track?>(null) }
    var editing by remember { mutableStateOf<Track?>(null) }

    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var lyricsFor by remember { mutableLongStateOf(-1L) }

    var bass by remember { mutableFloatStateOf(ap.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(ap.getFloat("treble", 0f)) }
    var norm by remember { mutableStateOf(ap.getBoolean("norm", false)) }
    var xfade by remember { mutableFloatStateOf(ap.getInt("xfade", 0).toFloat()) }
    var gapless by remember { mutableStateOf(ap.getBoolean("gapless", false)) }
    var dedup by remember { mutableStateOf(ap.getBoolean("dedup", false)) }
    var sleepEnd by remember { mutableLongStateOf(0L) }

    val listState = rememberLazyListState()

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

    fun playNext(t: Track) {
        c?.let { it.addMediaItem(if (it.mediaItemCount == 0) 0 else it.currentMediaItemIndex + 1, toItem(t)) }
    }

    fun enqueue(t: Track) {
        c?.addMediaItem(toItem(t))
    }

    fun persist() = store.save(playlists.toMap())

    fun addToPlaylist(name: String, t: Track) {
        val l = playlists[name] ?: emptyList()
        if (t.path !in l) {
            playlists[name] = l + t.path
            persist()
        }
    }

    fun toggleFav(key: String) {
        if (key in favs) favs.remove(key) else favs.add(key)
        favStore.save(favs.toList())
    }

    fun sendAudio() {
        val args = Bundle().apply {
            putFloat("bass", bass)
            putFloat("treble", treble)
            putBoolean("norm", norm)
            putInt("xfade", xfade.roundToInt())
            putBoolean("gapless", gapless)
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

    // Favoris modifiés depuis la notification + compteurs d'écoute
    DisposableEffect(Unit) {
        val spFav = ctx.getSharedPreferences("favs", Context.MODE_PRIVATE)
        val spStats = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
            if (sp === spFav) {
                val n = favStore.load()
                if (n != favs.toSet()) {
                    favs.clear()
                    favs.addAll(n)
                }
            } else {
                statsRev++
            }
        }
        spFav.registerOnSharedPreferenceChangeListener(l)
        spStats.registerOnSharedPreferenceChangeListener(l)
        onDispose {
            spFav.unregisterOnSharedPreferenceChangeListener(l)
            spStats.unregisterOnSharedPreferenceChangeListener(l)
        }
    }

    // Conversion des anciens favoris / playlists (identifiants) en chemins de fichiers
    LaunchedEffect(tracks.size) {
        if (tracks.isNotEmpty()) {
            val byIdStr = tracks.associateBy { it.id.toString() }
            fun fix(k: String): String = if (k.isNotEmpty() && k.all { it.isDigit() }) byIdStr[k]?.path ?: k else k
            val newFavs = favs.map { fix(it) }
            if (newFavs != favs.toList()) {
                favs.clear()
                favs.addAll(newFavs)
                favStore.save(newFavs)
            }
            var changed = false
            for (name in playlists.keys.toList()) {
                val old = playlists[name] ?: continue
                val nw = old.map { fix(it) }
                if (nw != old) {
                    playlists[name] = nw
                    changed = true
                }
            }
            if (changed) persist()
        }
    }

    DisposableEffect(c) {
        if (c == null) {
            onDispose { }
        } else {
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    sync(player)
                    if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                        events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                    ) queueRev++
                }
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

    // Retour en haut de liste quand on change d'onglet
    LaunchedEffect(tab, openPl, autoMode) { listState.scrollToItem(0) }

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
                        playlists[n] = listOfNotNull(pending?.path)
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
        val modified = et.title != et.origTitle || et.artist != et.origArtist
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
                    if (modified) {
                        TextButton(onClick = { onReset(et); editing = null }) {
                            Text("Rétablir l’original")
                        }
                    }
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

    // ---------- Dialogue : file de lecture ----------
    if (showQueue) {
        val rq = queueRev // relit la file quand elle change
        val n = c?.mediaItemCount ?: 0
        val ci = c?.currentMediaItemIndex ?: -1
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("File de lecture ($n)") },
            text = {
                if (n == 0) {
                    Text("La file est vide.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(n) { i ->
                            val mi = c?.getMediaItemAt(i)
                            val title = mi?.mediaMetadata?.title?.toString() ?: "…"
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    c?.seekToDefaultPosition(i)
                                    c?.play()
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    title, Modifier.weight(1f).padding(vertical = 8.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (i == ci) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (i == ci) FontWeight.Bold else FontWeight.Normal
                                )
                                TextButton(onClick = { if (i > 0) c?.moveMediaItem(i, i - 1) }) { Text("↑") }
                                TextButton(onClick = { if (i < n - 1) c?.moveMediaItem(i, i + 1) }) { Text("↓") }
                                IconButton(onClick = { c?.removeMediaItem(i) }) { Ico(R.drawable.ic_close, size = 20.dp) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Fermer") } }
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
                            IconButton(onClick = { onFolders(folders.filter { it != p }) }) {
                                Ico(R.drawable.ic_close, size = 20.dp)
                            }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Forcer le sans-blanc (léger chevauchement)", Modifier.weight(1f))
                        Switch(checked = gapless, onCheckedChange = {
                            gapless = it
                            ap.edit().putBoolean("gapless", it).apply()
                            sendAudio()
                        })
                    }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Masquer les doublons", Modifier.weight(1f))
                        Switch(checked = dedup, onCheckedChange = {
                            dedup = it
                            ap.edit().putBoolean("dedup", it).apply()
                        })
                    }
                    Text(
                        "Doublon = même titre, même artiste et même durée.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { showSettings = false; showFolders = true }) {
                            Text("Dossiers…")
                        }
                        OutlinedButton(onClick = { onRescan() }) { Text("Rescanner") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Fermer") } }
        )
    }

    // ---------- Blocs partagés du lecteur plein écran ----------
    val media: @Composable (Boolean) -> Unit = { land ->
        if (showLyrics && current != null) {
            Text(
                if (lyricsFor != current.id) "Chargement…"
                else lyrics ?: "Aucune parole intégrée dans ce fichier.",
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        } else if (land) {
            Cover(cover, Modifier.fillMaxHeight().aspectRatio(1f))
        } else {
            Cover(cover, Modifier.fillMaxWidth().widthIn(max = 480.dp).aspectRatio(1f))
        }
    }

    val controls: @Composable () -> Unit = {
        if (current != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    IconButton(onClick = { c?.shuffleModeEnabled = !shuffle }) {
                        Ico(R.drawable.ic_shuffle, Modifier.alpha(if (shuffle) 1f else 0.35f))
                    }
                    IconButton(onClick = { c?.seekToPrevious() }) { Ico(R.drawable.ic_prev, size = 32.dp) }
                    Button(onClick = { toggle() }, contentPadding = PaddingValues(16.dp)) {
                        Ico(if (playing) R.drawable.ic_pause else R.drawable.ic_play, size = 32.dp)
                    }
                    IconButton(onClick = { c?.seekToNext() }) { Ico(R.drawable.ic_next, size = 32.dp) }
                    IconButton(onClick = {
                        c?.repeatMode = when (repeat) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }) {
                        Ico(
                            if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                            Modifier.alpha(if (repeat == Player.REPEAT_MODE_OFF) 0.35f else 1f)
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
                    IconButton(onClick = { toggleFav(current.path) }) {
                        val f = current.path in favs
                        Ico(
                            if (f) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                            tint = if (f) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showTimer = true }) { Ico(R.drawable.ic_timer) }
                    IconButton(onClick = { showQueue = true }) { Ico(R.drawable.ic_queue) }
                }
            }
        }
    }

    val topRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { expanded = false }) { Ico(R.drawable.ic_expand_more, size = 28.dp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showLyrics = !showLyrics }) {
                Ico(R.drawable.ic_subject, Modifier.padding(end = 6.dp))
                Text(if (showLyrics) "Pochette" else "Paroles")
            }
        }
    }

    Crossfade(targetState = expanded && current != null, label = "ecran") { full ->
        if (full && current != null) {
            // ---------- Lecteur plein écran (tirer vers le bas pour fermer) ----------
            val dragClose = Modifier.pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = { if (dy > 220f) expanded = false },
                    onVerticalDrag = { _, d -> dy += d }
                )
            }
            if (landscape) {
                Row(
                    Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).then(dragClose),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) { media(true) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        topRow()
                        Spacer(Modifier.weight(1f))
                        controls()
                        Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).then(dragClose),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    topRow()
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) { media(false) }
                    Spacer(Modifier.height(16.dp))
                    controls()
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
            val base = remember(filtered, sort, dedup) {
                val s = sortTracks(filtered, sort)
                if (dedup) s.distinctBy {
                    "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}|${it.durationMs / 2000}"
                } else s
            }
            val entries = remember(base, tab, favs.size) {
                when (tab) {
                    TAB_FAV -> buildEntries(base.filter { it.path in favs }, 0)
                    in 0..3 -> buildEntries(base, tab)
                    else -> emptyList()
                }
            }
            val autoList = remember(base, autoMode, stats) {
                when (autoMode) {
                    0 -> base.sortedByDescending { it.dateAdded }.take(100)
                    1 -> base.filter { (stats[it.path] ?: 0) > 0 }
                        .sortedByDescending { stats[it.path] ?: 0 }.take(100)
                    else -> base.filter { (stats[it.path] ?: 0) == 0 }
                }
            }
            val shown = remember(entries) { entries.filterIsInstance<Entry.Item>().map { it.track } }

            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 0.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Ma musique", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onRescan() }) { Ico(R.drawable.ic_refresh) }
                    IconButton(onClick = { showQueue = true }) { Ico(R.drawable.ic_queue) }
                    TextButton(onClick = { showTimer = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Ico(R.drawable.ic_timer)
                        if (remainMin > 0) Text(" $remainMin min", fontSize = 12.sp)
                    }
                    IconButton(onClick = { showSettings = true }) { Ico(R.drawable.ic_settings) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Rechercher un titre, un artiste…") },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Ico(R.drawable.ic_close, size = 20.dp) }
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
                if (tab in 0..4) {
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { showSort = true }) {
                            Ico(R.drawable.ic_sort, Modifier.padding(end = 6.dp), size = 20.dp)
                            Text("Trier : ${SORTS[sort]}", fontSize = 13.sp)
                        }
                        DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                            SORTS.forEachIndexed { i, s ->
                                DropdownMenuItem(text = { Text(s) }, onClick = { sort = i; showSort = false })
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    if (tab == TAB_PLAYLISTS && openPl == null) {
                        // Liste des playlists
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                        val byKey = tracks.associateBy { it.path }
                        val plTracks = (playlists[name] ?: emptyList()).mapNotNull { byKey[it] }
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
                                }) { Text("Tout lire") }
                                IconButton(onClick = {
                                    playlists.remove(name); persist(); openPl = null
                                }) { Ico(R.drawable.ic_close) }
                            }
                            if (plTracks.isEmpty()) {
                                Text(
                                    "Playlist vide. Ajoute des titres avec le menu ⋮ de la liste.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            LazyColumn(Modifier.weight(1f), state = listState) {
                                items(plTracks) { t ->
                                    TrackRow(
                                        t, t.id == curId, t.path in favs, "", playlists, name,
                                        onPlay = { playList(plTracks, t.id) },
                                        onFav = { toggleFav(t.path) },
                                        onPlayNext = { playNext(t) },
                                        onQueue = { enqueue(t) },
                                        onAdd = { addToPlaylist(it, t) },
                                        onNew = { pending = t; showNew = true },
                                        onEdit = { editing = t },
                                        onRemove = {
                                            playlists[name] = (playlists[name] ?: emptyList()) - t.path
                                            persist()
                                        }
                                    )
                                }
                            }
                        }
                    } else if (tab == TAB_AUTO) {
                        // Listes automatiques : récents, plus écoutés, jamais écoutés
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AUTOS.forEachIndexed { i, n -> Chip(n, autoMode == i) { autoMode = i } }
                            }
                            if (autoList.isEmpty()) {
                                Text(
                                    if (autoMode == 1) "Aucun titre écouté pour l’instant."
                                    else "Aucun titre.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LazyColumn(Modifier.weight(1f), state = listState) {
                                items(autoList) { t ->
                                    TrackRow(
                                        t, t.id == curId, t.path in favs,
                                        if (autoMode == 1) "${stats[t.path] ?: 0}×" else "",
                                        playlists, null,
                                        onPlay = { playList(autoList, t.id) },
                                        onFav = { toggleFav(t.path) },
                                        onPlayNext = { playNext(t) },
                                        onQueue = { enqueue(t) },
                                        onAdd = { addToPlaylist(it, t) },
                                        onNew = { pending = t; showNew = true },
                                        onEdit = { editing = t },
                                        onRemove = {}
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
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            items(entries) { e ->
                                when (e) {
                                    is Entry.Header -> Text(
                                        e.text, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                                    )
                                    is Entry.Item -> TrackRow(
                                        e.track, e.track.id == curId, e.track.path in favs, "",
                                        playlists, null,
                                        onPlay = { playList(shown, e.track.id) },
                                        onFav = { toggleFav(e.track.path) },
                                        onPlayNext = { playNext(e.track) },
                                        onQueue = { enqueue(e.track) },
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
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically { it } + fadeIn()
                    ) {
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
                                    IconButton(onClick = { toggle() }) {
                                        Ico(if (playing) R.drawable.ic_pause else R.drawable.ic_play, size = 28.dp)
                                    }
                                    IconButton(onClick = { c?.seekToNext() }) { Ico(R.drawable.ic_next, size = 28.dp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
