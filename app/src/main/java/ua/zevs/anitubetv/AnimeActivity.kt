package ua.zevs.anitubetv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AnimeActivity : TvActivity() {

    private lateinit var sourcesView: RecyclerView
    private lateinit var episodesView: RecyclerView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var episodesTitle: TextView

    private lateinit var sourcesAdapter: TextAdapter
    private lateinit var episodesAdapter: TextAdapter

    private var details: Details? = null
    private var current = 0
    private lateinit var item: AnimeItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_anime)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        item = AnimeItem(Anitube.animeId(url), intent.getStringExtra("title") ?: "", url, intent.getStringExtra("poster"))

        sourcesView = findViewById(R.id.sources)
        episodesView = findViewById(R.id.episodes)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        episodesTitle = findViewById(R.id.episodesTitle)

        findViewById<TextView>(R.id.title).text = item.title
        ImageLoader.load(findViewById(R.id.poster), item.poster)

        sourcesAdapter = TextAdapter(onClick = { pos ->
            selectSource(pos)
            focusEpisode()
        }, onFocus = { pos -> if (pos != current) selectSource(pos) })
        sourcesView.layoutManager = LinearLayoutManager(this)
        sourcesView.adapter = sourcesAdapter
        sourcesView.itemAnimator = null

        episodesAdapter = TextAdapter(onClick = ::play)
        episodesView.layoutManager = GridLayoutManager(this, 3)
        episodesView.adapter = episodesAdapter
        episodesView.itemAnimator = null

        load()
    }

    override fun onResume() {
        super.onResume()
        if (details != null) {
            showEpisodes()
            focusEpisode()
        }
    }

    /** Back: episodes -> dub/player list -> anime list. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (episodesView.hasFocus() && sourcesAdapter.itemCount > 0) {
            sourcesView.scrollToPosition(current)
            sourcesView.post {
                val vh = sourcesView.findViewHolderForAdapterPosition(current)
                (vh?.itemView ?: sourcesView.getChildAt(0))?.requestFocus()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        message.visibility = View.GONE
        Bg.run({ Anitube.details(item.url) }) { r ->
            progress.visibility = View.GONE
            r.onSuccess { d ->
                details = d
                if (item.title.isBlank()) findViewById<TextView>(R.id.title).text = d.title
                findViewById<TextView>(R.id.description).text = d.description
                if (item.poster == null && d.poster != null) {
                    item = item.copy(poster = d.poster)
                    ImageLoader.load(findViewById<ImageView>(R.id.poster), d.poster)
                }
                if (item.title.isBlank()) item = item.copy(title = d.title)
                if (d.sources.isEmpty()) {
                    message.text = "Серій поки немає (або сайт показує їх лише після входу)."
                    message.visibility = View.VISIBLE
                    return@onSuccess
                }
                sourcesAdapter.set(d.sources.map { "${it.name}  (${it.episodes.size})" })
                val last = Store.lastSource(d.id)
                current = d.sources.indexOfFirst { it.id == last }.coerceAtLeast(0)
                selectSource(current)
                sourcesView.scrollToPosition(current)
                focusEpisode()
            }.onFailure {
                message.text = "Помилка: ${it.message}\nНатисни OK, щоб спробувати ще раз."
                message.visibility = View.VISIBLE
                message.isFocusable = true
                message.setOnClickListener { load() }
                message.requestFocus()
            }
        }
    }

    private fun selectSource(pos: Int) {
        val d = details ?: return
        if (pos !in d.sources.indices) return
        current = pos
        sourcesAdapter.selected = pos
        for (i in 0 until sourcesView.childCount) {
            val child = sourcesView.getChildAt(i) as? TextView ?: continue
            val p = sourcesView.getChildAdapterPosition(child)
            child.setTypeface(null, if (p == pos) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        showEpisodes()
    }

    private fun showEpisodes() {
        val src = details?.sources?.getOrNull(current) ?: return
        episodesTitle.text = "Серії (${src.episodes.size})"
        val green = getColor(R.color.watched)
        val orange = getColor(R.color.accent)
        episodesAdapter.set(
            src.episodes.map { e ->
                when {
                    Store.isWatched(e.iframe) -> "✓ ${e.name}"
                    Store.isStarted(e.iframe) -> "▶ ${e.name}"
                    else -> e.name
                }
            },
            src.episodes.map { e ->
                when {
                    Store.isWatched(e.iframe) -> green
                    Store.isStarted(e.iframe) -> orange
                    else -> null
                }
            }
        )
    }

    /** Focus the first episode that is not watched yet. */
    private fun focusEpisode() {
        val src = details?.sources?.getOrNull(current) ?: return
        var idx = src.episodes.indexOfFirst { !Store.isWatched(it.iframe) }
        if (idx < 0) idx = 0
        episodesView.scrollToPosition(idx)
        episodesView.post {
            val vh = episodesView.findViewHolderForAdapterPosition(idx)
            (vh?.itemView ?: episodesView.getChildAt(0))?.requestFocus()
        }
    }

    private fun play(pos: Int) {
        val d = details ?: return
        val src = d.sources.getOrNull(current) ?: return
        if (pos !in src.episodes.indices) return
        Store.setLastSource(d.id, src.id)
        Session.anime = item.copy(title = item.title.ifBlank { d.title })
        Session.episodes = src.episodes
        Session.index = pos
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
