package app.nova.tv

import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Lecteur plein écran (Media3 / ExoPlayer).
 * Télé : OK = pause, ◀ ▶ = -10 s / +10 s, ▲ ▼ = chaîne suivante / précédente, chiffres = numéro de chaîne.
 * Téléphone : commandes tactiles classiques.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_RESULT = "result"
        private const val CORAL = 0xFFFF4F7B.toInt()
        private const val SUN = 0xFFFFB547.toInt()
        private const val INK = 0xFF06070C.toInt()
        private const val MUTED = 0xFFA3A2B8.toInt()
    }

    private data class Item(val url: String, val title: String, val sub: String, val num: Int)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var items: List<Item> = emptyList()
    private var live = false
    private var tv = false
    private var finished = false
    private val retried = HashSet<Int>()

    private lateinit var topBar: LinearLayout
    private lateinit var closeBtn: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var numView: TextView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var timeView: TextView
    private lateinit var hintView: TextView
    private lateinit var pauseView: TextView
    private lateinit var errorView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideOverlay() }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 500)
        }
    }
    private var digits = ""
    private val digitRunnable = Runnable { jumpToNumber() }

    // Abonnement MAG : adresse de l'API du portail et en-têtes du boîtier
    private var magApi = ""
    private var magHeaders: Map<String, String> = emptyMap()

    private val prefs by lazy { getSharedPreferences("nova_player", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        tv = ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
        if (!tv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val json = try {
            JSONObject(intent.getStringExtra(EXTRA_PLAYLIST) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        live = json.optString("mode") == "live"
        val arr = json.optJSONArray("items")
        val list = ArrayList<Item>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Item(o.optString("url"), o.optString("title"), o.optString("sub"), o.optInt("num", 0)))
            }
        }
        items = list
        if (items.isEmpty()) {
            finish()
            return
        }
        val startIndex = json.optInt("index", 0).coerceIn(0, items.size - 1)
        val startMs = json.optLong("start", 0L)
        json.optJSONObject("mag")?.let { m ->
            magApi = m.optString("api")
            val h = m.optJSONObject("headers")
            if (h != null) {
                val map = LinkedHashMap<String, String>()
                for (k in h.keys()) map[k] = h.optString(k)
                magHeaders = map
            }
        }

        buildUi()

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(magHeaders["User-Agent"] ?: "Nova/1.0 (Linux; Android)")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        if (magHeaders.isNotEmpty()) http.setDefaultRequestProperties(magHeaders.filterKeys { it != "User-Agent" })
        // Les liens « novamag:// » sont demandés au portail juste avant la lecture (lien à usage unique)
        val sources = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme == "novamag") spec.withUri(resolveMag(spec.uri)) else spec
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(sources))
            .build()
        applySavedLanguages()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        playerView.player = player
        player.addListener(listener)
        val mediaItems = items.mapIndexed { i, it -> mediaItem(it, i, null) }
        player.setMediaItems(mediaItems, startIndex, if (live || startMs <= 0) C.TIME_UNSET else startMs)
        player.playWhenReady = true
        player.prepare()
        showInfo(startIndex)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult()
            }
        })
    }

    // ---------- Construction de l'interface ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        return t
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.useController = !tv
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        playerView.setShowSubtitleButton(true)
        playerView.keepScreenOn = true
        if (!tv) {
            playerView.controllerShowTimeoutMs = 3500
            playerView.setShowNextButton(true)
            playerView.setShowPreviousButton(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val pad = dp(if (tv) 40 else 20)

        // Bandeau du haut : bouton quitter, puis numéro, titre, sous-titre
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.TOP
        topBar.setPadding(pad, pad, pad, dp(48))
        topBar.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xD9000000.toInt(), 0x00000000)
        )
        numView = label(if (tv) 22f else 16f, INK, true)
        numView.setShadowLayer(0f, 0f, 0f, 0)
        val pill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CORAL, SUN))
        pill.cornerRadius = dp(40).toFloat()
        numView.background = pill
        numView.setPadding(dp(14), dp(4), dp(14), dp(4))
        val numLp = LinearLayout.LayoutParams(-2, -2)
        numLp.bottomMargin = dp(10)
        titleView = label(if (tv) 28f else 18f, Color.WHITE, true)
        subView = label(if (tv) 16f else 13f, 0xFFC9C8DA.toInt())

        // Téléphone : bouton ✕ cliquable. Télé : rappel de la touche Retour.
        closeBtn = label(if (tv) 16f else 22f, Color.WHITE, true)
        closeBtn.text = if (tv) "←  Retour" else "✕"
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setShadowLayer(0f, 0f, 0f, 0)
        val closeBg = GradientDrawable()
        closeBg.cornerRadius = dp(40).toFloat()
        closeBg.setColor(0x40FFFFFF)
        closeBg.setStroke(dp(1), 0x55FFFFFF)
        closeBtn.background = closeBg
        val closeLp = if (tv) LinearLayout.LayoutParams(-2, -2) else LinearLayout.LayoutParams(dp(48), dp(48))
        closeLp.marginEnd = dp(16)
        if (tv) {
            closeBtn.setPadding(dp(18), dp(8), dp(18), dp(8))
        } else {
            closeBtn.isClickable = true
            closeBtn.isFocusable = false
            closeBtn.contentDescription = "Quitter la lecture"
            closeBtn.setOnClickListener { finishWithResult() }
        }
        topBar.addView(closeBtn, closeLp)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.addView(numView, numLp)
        info.addView(titleView)
        info.addView(subView)
        topBar.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Bandeau du bas (télé) : progression, temps, aide télécommande
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setPadding(pad, dp(60), pad, pad)
        bottomBar.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE6000000.toInt(), 0x00000000)
        )
        bottomBar.visibility = View.GONE
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 1000
        progress.progressTintList = ColorStateList.valueOf(CORAL)
        progress.progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
        bottomBar.addView(progress, LinearLayout.LayoutParams(-1, dp(6)))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, 0)
        timeView = label(16f, Color.WHITE, true)
        hintView = label(14f, MUTED)
        hintView.gravity = Gravity.END
        row.addView(timeView, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(hintView, LinearLayout.LayoutParams(-2, -2))
        bottomBar.addView(row)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Icône pause au centre (télé)
        pauseView = label(if (tv) 72f else 54f, Color.WHITE, true)
        pauseView.text = "❚❚"
        pauseView.gravity = Gravity.CENTER
        pauseView.visibility = View.GONE
        root.addView(pauseView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Message d'erreur
        errorView = label(if (tv) 20f else 16f, Color.WHITE, true)
        errorView.gravity = Gravity.CENTER
        errorView.visibility = View.GONE
        errorView.setPadding(dp(28), dp(20), dp(28), dp(20))
        val box = GradientDrawable()
        box.cornerRadius = dp(22).toFloat()
        box.setColor(0xE016182A.toInt())
        errorView.background = box
        root.addView(errorView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        setContentView(root)

        if (!tv) {
            playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                topBar.visibility = v
            })
        }
    }

    // ---------- Lecture ----------

    private fun urlIn(s: String?): String = Regex("https?://\\S+").find(s ?: "")?.value ?: ""

    /** Demande au portail MAG le vrai lien du flux (appelé hors du fil principal par le lecteur). */
    private fun resolveMag(uri: Uri): Uri {
        val type = uri.getQueryParameter("t") ?: "itv"
        val cmd = uri.getQueryParameter("c") ?: ""
        val series = uri.getQueryParameter("s") ?: ""
        if (magApi.isNotEmpty()) {
            try {
                val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
                val q = "$magApi?type=${enc(type)}&action=create_link&cmd=${enc(cmd)}&series=${enc(series)}" +
                    "&forced_storage=0&disable_ad=0&download=0&JsHttpRequest=1-xml"
                val c = URL(q).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                for ((k, v) in magHeaders) c.setRequestProperty(k, v)
                val body = try {
                    c.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    c.disconnect()
                }
                val real = urlIn(JSONObject(body).optJSONObject("js")?.optString("cmd"))
                if (real.isNotEmpty()) return Uri.parse(real)
            } catch (_: Exception) {
            }
        }
        val direct = urlIn(cmd)
        if (direct.isNotEmpty() && !direct.contains("://localhost")) return Uri.parse(direct)
        throw IOException("Le portail MAG n'a pas donné de lien pour ce titre")
    }

    private fun mediaItem(it: Item, index: Int, forcedMime: String?): MediaItem {
        val b = MediaItem.Builder().setUri(it.url).setMediaId(index.toString())
        val path = it.url.substringBefore('?').lowercase(Locale.ROOT)
        val mime = forcedMime ?: if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
        if (mime != null) b.setMimeType(mime)
        return b.build()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            errorView.visibility = View.GONE
            showInfo(player.currentMediaItemIndex)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!tv) return
            val pausedByUser = !player.playWhenReady
            pauseView.visibility = if (pausedByUser) View.VISIBLE else View.GONE
            if (pausedByUser) showOverlay(true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) errorView.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            val i = player.currentMediaItemIndex
            val item = items.getOrNull(i) ?: return
            val path = item.url.substringBefore('?').lowercase(Locale.ROOT)
            // Lien sans extension : on retente une fois en HLS
            if (!retried.contains(i) && !path.endsWith(".m3u8")) {
                retried.add(i)
                player.replaceMediaItem(i, mediaItem(item, i, MimeTypes.APPLICATION_M3U8))
                player.prepare()
                player.play()
                return
            }
            errorView.text = if (live) {
                "Cette chaîne ne répond pas.\n" +
                    (if (tv) "▲ ▼ pour changer de chaîne  ·  OK pour réessayer" else "Utilise ⏮ ⏭ pour changer de chaîne")
            } else {
                "Ce titre ne se lance pas.\n" +
                    (if (tv) "OK pour réessayer  ·  Retour pour quitter" else "Réessaie dans un moment")
            }
            errorView.visibility = View.VISIBLE
            showOverlay(true)
        }
    }

    private fun retry() {
        val i = player.currentMediaItemIndex
        retried.remove(i)
        items.getOrNull(i)?.let { player.replaceMediaItem(i, mediaItem(it, i, null)) }
        errorView.visibility = View.GONE
        player.prepare()
        player.play()
        showOverlay(false)
    }

    private fun togglePause() {
        if (errorView.visibility == View.VISIBLE) {
            retry()
            return
        }
        if (player.playWhenReady) {
            player.pause()
            showOverlay(true)
        } else {
            player.play()
            showOverlay(false)
        }
    }

    private fun zap(d: Int) {
        if (items.size < 2) {
            showOverlay(false)
            return
        }
        val n = (player.currentMediaItemIndex + d + items.size) % items.size
        player.seekToDefaultPosition(n)
        player.play()
    }

    private fun seekBy(ms: Long) {
        val dur = player.duration
        var p = player.currentPosition + ms
        if (p < 0) p = 0
        if (dur != C.TIME_UNSET && dur > 0 && p > dur - 1000) p = dur - 1000
        player.seekTo(p)
        showOverlay(!player.playWhenReady)
    }

    private fun typeDigit(d: Int) {
        digits = (digits + d).takeLast(4)
        numView.text = digits.padEnd(3, '_')
        numView.visibility = View.VISIBLE
        showOverlay(false)
        handler.removeCallbacks(digitRunnable)
        handler.postDelayed(digitRunnable, 1300)
    }

    private fun jumpToNumber() {
        val n = digits.toIntOrNull()
        digits = ""
        val idx = if (n == null) -1 else items.indexOfFirst { it.num == n }
        if (idx >= 0) {
            player.seekToDefaultPosition(idx)
            player.play()
        } else {
            Toast.makeText(this, "Chaîne ${n ?: ""} introuvable dans cette liste", Toast.LENGTH_SHORT).show()
            showInfo(player.currentMediaItemIndex)
        }
    }

    // ---------- Télécommande ----------

    private val tvKeys = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) return super.dispatchKeyEvent(event)
        if (!::player.isInitialized) return super.dispatchKeyEvent(event)

        if (!tv) {
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) togglePause()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val isDigit = k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9
        if (!tvKeys.contains(k) && !(isDigit && live)) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val rep = event.repeatCount
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (rep == 0) togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); showOverlay(false) }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showOverlay(true) }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (live) { if (rep == 0) showTracksMenu() } else seekBy(-10_000L * (1 + rep / 4))
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (live) { if (rep == 0) showTracksMenu() } else seekBy(10_000L * (1 + rep / 4))
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (!live) seekBy(-30_000L)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!live) seekBy(30_000L)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (live) { if (rep == 0) zap(1) } else showOverlay(false)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (live) { if (rep == 0) zap(-1) } else if (rep == 0) showTracksMenu()
            KeyEvent.KEYCODE_CHANNEL_DOWN -> if (live) { if (rep == 0) zap(-1) } else showOverlay(false)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> if (rep == 0) showTracksMenu()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (rep == 0) {
                if (live) zap(1) else if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (rep == 0) {
                if (live) zap(-1) else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            }
            KeyEvent.KEYCODE_INFO -> showOverlay(false)
            else -> if (isDigit && live && rep == 0) typeDigit(k - KeyEvent.KEYCODE_0)
        }
        return true
    }

    // ---------- Langue audio et sous-titres ----------

    private class Choice(val label: String, val group: Tracks.Group, val index: Int)

    /** Remet les langues choisies la dernière fois (français par défaut pour l'audio). */
    private fun applySavedLanguages() {
        val b = player.trackSelectionParameters.buildUpon()
        b.setPreferredAudioLanguage(prefs.getString("audio", "fr"))
        when (val t = prefs.getString("text", null)) {
            null -> {}
            "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(t)
        }
        player.trackSelectionParameters = b.build()
    }

    private fun trackName(f: Format, n: Int, type: Int): String {
        val parts = ArrayList<String>()
        val lang = f.language
        if (!lang.isNullOrEmpty() && lang != "und") {
            val name = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.FRENCH)
            parts.add(
                if (name.isNotEmpty() && !name.equals(lang, true)) name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
                else lang.uppercase(Locale.ROOT)
            )
        }
        val label = f.label
        if (!label.isNullOrBlank() && parts.none { it.equals(label, true) }) parts.add(label)
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> parts.add("mono")
                2 -> parts.add("stéréo")
                6 -> parts.add("5.1")
                8 -> parts.add("7.1")
            }
        }
        if (type == C.TRACK_TYPE_TEXT && (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts.add("forcés")
        if (parts.isEmpty()) return if (type == C.TRACK_TYPE_AUDIO) "Piste $n" else "Sous-titres $n"
        return parts.joinToString("  ·  ")
    }

    private fun choices(type: Int): List<Choice> {
        val out = ArrayList<Choice>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out.add(Choice(trackName(g.getTrackFormat(i), out.size + 1, type), g, i))
            }
        }
        return out
    }

    private fun dialog(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    /** Menu de la télécommande : choisir la langue et les sous-titres du titre en cours. */
    private fun showTracksMenu() {
        val audio = choices(C.TRACK_TYPE_AUDIO)
        val text = choices(C.TRACK_TYPE_TEXT)
        if (audio.size < 2 && text.isEmpty()) {
            Toast.makeText(
                this,
                if (player.currentTracks.isEmpty) "Patiente, la lecture démarre…" else "Une seule langue et pas de sous-titres pour ce titre",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (audio.size >= 2) {
            val cur = audio.firstOrNull { it.group.isTrackSelected(it.index) }?.label ?: "automatique"
            labels.add("Langue : $cur")
            actions.add { pickTrack(C.TRACK_TYPE_AUDIO, audio) }
        }
        if (text.isNotEmpty()) {
            val cur = text.firstOrNull { it.group.isTrackSelected(it.index) }?.label ?: "désactivés"
            labels.add("Sous-titres : $cur")
            actions.add { pickTrack(C.TRACK_TYPE_TEXT, text) }
        }
        if (actions.size == 1) {
            actions[0]()
            return
        }
        dialog().setTitle("Langue et sous-titres")
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    private fun pickTrack(type: Int, list: List<Choice>) {
        val isText = type == C.TRACK_TYPE_TEXT
        val labels = ArrayList<String>()
        if (isText) labels.add("Désactivés")
        list.forEach { labels.add(it.label) }
        val sel = list.indexOfFirst { it.group.isTrackSelected(it.index) }
        val checked = if (isText) sel + 1 else sel.coerceAtLeast(0)
        dialog().setTitle(if (isText) "Sous-titres" else "Langue")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, w ->
                val b = player.trackSelectionParameters.buildUpon()
                val edit = prefs.edit()
                if (isText && w == 0) {
                    b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    edit.putString("text", "off")
                } else {
                    val c = list[if (isText) w - 1 else w]
                    b.setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
                    val lang = c.group.getTrackFormat(c.index).language
                    if (!lang.isNullOrEmpty() && lang != "und") {
                        if (isText) {
                            b.setPreferredTextLanguage(lang)
                            edit.putString("text", lang)
                        } else {
                            b.setPreferredAudioLanguage(lang)
                            edit.putString("audio", lang)
                        }
                    } else if (isText) {
                        edit.remove("text")
                    }
                }
                player.trackSelectionParameters = b.build()
                edit.apply()
                d.dismiss()
                showOverlay(false)
            }
            .show()
    }

    // ---------- Bandeaux d'information ----------

    private fun showInfo(i: Int) {
        val it = items.getOrNull(i) ?: return
        titleView.text = it.title
        subView.text = it.sub
        subView.visibility = if (it.sub.isEmpty()) View.GONE else View.VISIBLE
        if (live && it.num > 0) {
            numView.text = it.num.toString()
            numView.visibility = View.VISIBLE
        } else {
            numView.visibility = View.GONE
        }
        hintView.text = when {
            !tv -> ""
            live -> "▲ ▼  chaînes     ◀ ▶  langue     0-9  numéro     Retour  quitter"
            else -> "◀ ▶  10 s     OK  pause     ▼  langue et sous-titres     Retour  quitter"
        }
        showOverlay(false)
    }

    private fun showOverlay(sticky: Boolean) {
        topBar.visibility = View.VISIBLE
        if (tv) bottomBar.visibility = View.VISIBLE
        updateTime()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (!sticky) handler.postDelayed(hideRunnable, 4500)
    }

    private fun hideOverlay() {
        if (!::player.isInitialized) return
        if (!player.playWhenReady || errorView.visibility == View.VISIBLE) return
        if (!tv && playerView.isControllerFullyVisible) return
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        handler.removeCallbacks(tick)
    }

    private fun updateTime() {
        if (!::player.isInitialized) return
        if (live) {
            timeView.text = "●  EN DIRECT"
            timeView.setTextColor(CORAL)
            progress.visibility = View.INVISIBLE
            return
        }
        timeView.setTextColor(Color.WHITE)
        val d = player.duration
        val p = player.currentPosition
        if (d == C.TIME_UNSET || d <= 0) {
            timeView.text = fmt(p)
            progress.visibility = View.INVISIBLE
            return
        }
        progress.visibility = View.VISIBLE
        progress.progress = (p * 1000 / d).toInt().coerceIn(0, 1000)
        timeView.text = "${fmt(p)}  /  ${fmt(d)}"
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, x)
        else String.format(Locale.ROOT, "%d:%02d", m, x)
    }

    // ---------- Fin ----------

    private fun finishWithResult() {
        if (finished) return
        finished = true
        if (::player.isInitialized) {
            val r = JSONObject()
            r.put("mode", if (live) "live" else "vod")
            r.put("index", player.currentMediaItemIndex)
            r.put("position", player.currentPosition)
            r.put("duration", if (player.duration == C.TIME_UNSET) -1L else player.duration)
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, r.toString()))
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && live) player.play()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
