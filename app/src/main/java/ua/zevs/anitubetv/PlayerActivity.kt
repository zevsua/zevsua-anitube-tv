package ua.zevs.anitubetv

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var bigToast: TextView
    private lateinit var osd: View
    private lateinit var osdTitle: TextView
    private lateinit var osdEpisode: TextView
    private lateinit var osdPos: TextView
    private lateinit var osdDur: TextView
    private lateinit var osdBar: ProgressBar
    private lateinit var osdHint: TextView

    private var player: ExoPlayer? = null
    private lateinit var dataSourceFactory: OkHttpDataSource.Factory
    private var resolving: Cancelable? = null
    /** fenix needs ?token= on every playlist/segment request */
    @Volatile private var urlToken: String? = null

    private var index = 0
    private var currentUrl: String? = null
    private var errorState = false
    private var okDownAt = 0L
    private var okLongHandled = false

    private val hideOsd = Runnable { if (player?.isPlaying == true) osd.visibility = View.GONE }
    private val hideToast = Runnable { bigToast.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            updateOsd()
            saveProgress()
            Bg.main.postDelayed(this, 1000)
        }
    }
    private var tick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        bigToast = findViewById(R.id.bigToast)
        osd = findViewById(R.id.osd)
        osdTitle = findViewById(R.id.osdTitle)
        osdEpisode = findViewById(R.id.osdEpisode)
        osdPos = findViewById(R.id.osdPos)
        osdDur = findViewById(R.id.osdDur)
        osdBar = findViewById(R.id.osdBar)
        osdHint = findViewById(R.id.osdHint)

        if (Session.episodes.isEmpty()) { finish(); return }
        index = Session.index.coerceIn(0, Session.episodes.size - 1)

        dataSourceFactory = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.UA)
        buildPlayer()
        osdTitle.text = Session.anime?.title ?: ""
        updateHint()
        playEpisode(index)
        Bg.main.post(ticker)
    }

    private fun buildPlayer() {
        val trackSelector = DefaultTrackSelector(this)
        val q = Store.maxQuality
        if (q > 0) {
            trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, q))
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(tokenFactory()))
            .build()
        p.setAudioAttributes(AudioAttributes.DEFAULT, true)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> { loadingText.text = ""; loading.visibility = View.VISIBLE }
                    Player.STATE_READY -> loading.visibility = View.GONE
                    Player.STATE_ENDED -> onEnded()
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) { showOsd(sticky = true); saveProgress(force = true) } else showOsd()
            }

            override fun onPlayerError(error: PlaybackException) {
                showError("Помилка відтворення: ${error.errorCodeName}\n${error.cause?.message ?: ""}")
            }
        })
        playerView.player = p
        player = p
    }

    private fun tokenFactory() = ResolvingDataSource.Factory(dataSourceFactory) { spec ->
        val t = urlToken
        val uri = spec.uri
        if (t.isNullOrBlank() || !uri.isHierarchical || uri.getQueryParameter("token") != null) spec
        else spec.withUri(uri.buildUpon().appendQueryParameter("token", t).build())
    }

    private fun episode() = Session.episodes[index]

    private fun playEpisode(i: Int) {
        if (i !in Session.episodes.indices) return
        saveProgress(force = true)
        resolving?.cancel()
        index = i
        Session.index = i
        errorState = false
        val ep = episode()
        currentUrl = null
        player?.stop()
        osdEpisode.text = "${ep.name}   (${i + 1} з ${Session.episodes.size})"
        loadingText.text = "${ep.name}\nОтримую відео…"
        loading.visibility = View.VISIBLE
        showOsd(sticky = true)
        Session.anime?.let { Store.addHistory(it, ep.name) }

        resolving = Extractors.resolve(this, ep.iframe) { r ->
            r.onSuccess { startStream(it) }
                .onFailure { showError(it.message ?: "Не вдалося отримати відео") }
        }
    }

    private fun startStream(s: Stream) {
        val p = player ?: return
        val props = s.headers.filterKeys { !it.equals("User-Agent", true) }
        dataSourceFactory.setDefaultRequestProperties(props)
        urlToken = s.token

        val b = MediaItem.Builder().setUri(Uri.parse(s.url))
        if (s.url.lowercase().contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        val sub = s.subtitle
        if (!sub.isNullOrBlank() && sub.startsWith("http")) {
            val l = sub.lowercase()
            val mime = when {
                l.contains(".vtt") -> MimeTypes.TEXT_VTT
                l.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
                l.contains(".ass") || l.contains(".ssa") -> MimeTypes.TEXT_SSA
                else -> null
            }
            if (mime != null) {
                b.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                            .setMimeType(mime).setLanguage("uk")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                    )
                )
            }
        }
        val ep = episode()
        currentUrl = ep.iframe
        p.setMediaItem(b.build())
        val saved = Store.pos(ep.iframe)
        val dur = Store.dur(ep.iframe)
        if (saved > 30_000 && (dur == 0L || saved < dur - 60_000)) {
            p.seekTo(saved)
            toast("Продовжено з ${fmt(saved)}")
        }
        p.prepare()
        p.playWhenReady = true
    }

    private fun onEnded() {
        val ep = currentUrl ?: return
        val d = player?.duration ?: 0
        if (d > 0) Store.savePos(ep, d, d)
        if (Store.autoNext && index + 1 < Session.episodes.size) {
            toast("Наступна серія ▶")
            playEpisode(index + 1)
        } else if (index + 1 >= Session.episodes.size) {
            toast("Це остання серія")
            showOsd(sticky = true)
        } else showOsd(sticky = true)
    }

    private fun showError(msg: String) {
        errorState = true
        loading.visibility = View.VISIBLE
        loadingText.text = "$msg\n\nOK — спробувати ще раз     ☰ / довге OK — меню"
        showOsd(sticky = true)
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) { okDownAt = System.currentTimeMillis(); okLongHandled = false }
                else if (!okLongHandled && System.currentTimeMillis() - okDownAt > 600) {
                    okLongHandled = true; showMenu()
                }
            } else if (event.action == KeyEvent.ACTION_UP && !okLongHandled) {
                if (errorState) playEpisode(index) else togglePause()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val p = player
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-stepFor(event)); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(stepFor(event)); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount == 0) skipOpening()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) {
                    if (osd.visibility == View.VISIBLE && p?.isPlaying == true) osd.visibility = View.GONE
                    else showOsd()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) togglePause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.repeatCount == 0) nextEpisode(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.repeatCount == 0) prevEpisode(); return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // back = return to the episode / dub list (AnimeActivity)
                if (event.repeatCount == 0) { saveProgress(force = true); finish() }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                if (event.repeatCount == 0) showMenu(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Hold the button to seek faster. */
    private fun stepFor(e: KeyEvent): Int {
        val base = Store.seekStep
        return when {
            e.repeatCount > 12 -> base * 6
            e.repeatCount > 4 -> base * 3
            else -> base
        }
    }

    private fun seekBy(seconds: Int) {
        val p = player ?: return
        if (currentUrl == null) return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + seconds * 1000L).coerceIn(0, dur - 1000)
        p.seekTo(target)
        val sign = if (seconds > 0) "+" else "−"
        toast("${if (seconds > 0) "»" else "«"}  $sign${kotlin.math.abs(seconds)} с     ${fmt(target)}")
        showOsd()
    }

    private fun skipOpening() {
        val p = player ?: return
        if (currentUrl == null) return
        val len = Store.skipLen
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + len * 1000L).coerceAtMost(dur - 1000)
        p.seekTo(target)
        toast("⏭  +$len с     ${fmt(target)}")
        showOsd()
    }

    private fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); toast("❚❚  Пауза") } else { p.play(); toast("▶") }
    }

    private fun nextEpisode() {
        if (index + 1 < Session.episodes.size) playEpisode(index + 1) else toast("Це остання серія")
    }

    private fun prevEpisode() {
        if (index > 0) playEpisode(index - 1) else toast("Це перша серія")
    }

    private fun showMenu() {
        val items = arrayOf(
            "▶  Наступна серія",
            "◀  Попередня серія",
            "☰  Список серій",
            "⟲  З початку серії",
            "⚙  Налаштування",
            "✕  Вийти до списку серій"
        )
        AlertDialog.Builder(this).setTitle(episode().name).setItems(items) { _, which ->
            when (which) {
                0 -> nextEpisode()
                1 -> prevEpisode()
                2 -> showEpisodeList()
                3 -> { player?.seekTo(0); toast("З початку") }
                4 -> MainActivity.showSettings(this) { updateHint() }
                5 -> finish()
            }
        }.show()
    }

    private fun showEpisodeList() {
        val names = Session.episodes.mapIndexed { i, e ->
            val mark = when {
                i == index -> "▶ "
                Store.isWatched(e.iframe) -> "✓ "
                else -> ""
            }
            mark + e.name
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Серії")
            .setSingleChoiceItems(names, index) { d, which -> d.dismiss(); playEpisode(which) }
            .show()
    }

    // ---------------------------------------------------------------- OSD

    private fun updateHint() {
        osdHint.text = "←/→ ±${Store.seekStep} с (утримуй — швидше)    ↑ +${Store.skipLen} с опенінг    " +
                "OK пауза    ↓ панель    ☰ або довге OK — меню"
    }

    private fun showOsd(sticky: Boolean = false) {
        osd.visibility = View.VISIBLE
        updateOsd()
        Bg.main.removeCallbacks(hideOsd)
        if (!sticky) Bg.main.postDelayed(hideOsd, 4000)
    }

    private fun updateOsd() {
        if (osd.visibility != View.VISIBLE) return
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        osdPos.text = fmt(pos)
        osdDur.text = if (dur > 0) fmt(dur) else "--:--"
        osdBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        osdBar.secondaryProgress = if (dur > 0) (p.bufferedPosition * 1000 / dur).toInt() else 0
    }

    private fun toast(text: String) {
        bigToast.text = text
        bigToast.visibility = View.VISIBLE
        Bg.main.removeCallbacks(hideToast)
        Bg.main.postDelayed(hideToast, 1300)
    }

    private fun saveProgress(force: Boolean = false) {
        tick++
        if (!force && tick % 5 != 0) return
        val p = player ?: return
        val url = currentUrl ?: return
        val dur = p.duration
        if (dur > 0 && p.currentPosition > 0) Store.savePos(url, p.currentPosition, dur)
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Bg.main.removeCallbacks(ticker)
        Bg.main.removeCallbacks(hideOsd)
        Bg.main.removeCallbacks(hideToast)
        resolving?.cancel()
        player?.release()
        player = null
    }
}
