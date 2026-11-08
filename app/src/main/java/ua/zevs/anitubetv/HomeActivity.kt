package ua.zevs.anitubetv

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class Tile(
    val label: String,
    val emoji: String? = null,
    val icon: Drawable? = null,
    val pkg: String? = null,
    val action: () -> Unit
)

class TileAdapter(private val onLong: ((Tile) -> Unit)? = null) : RecyclerView.Adapter<TileAdapter.VH>() {
    val items = ArrayList<Tile>()

    fun set(list: List<Tile>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val emoji: TextView = v.findViewById(R.id.emoji)
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        h.label.text = t.label
        if (t.icon != null) {
            h.icon.setImageDrawable(t.icon); h.icon.visibility = View.VISIBLE; h.emoji.visibility = View.GONE
        } else {
            h.emoji.text = t.emoji ?: "•"; h.emoji.visibility = View.VISIBLE; h.icon.visibility = View.GONE
        }
        h.itemView.setOnClickListener { t.action() }
        h.itemView.setOnLongClickListener { onLong?.invoke(t); onLong != null }
    }
}

class HistoryAdapter(
    private val onClick: (AnimeItem) -> Unit,
    private val onLong: ((AnimeItem) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.VH>() {
    val items = ArrayList<AnimeItem>()

    fun set(list: List<AnimeItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.title.text = a.title
        h.subtitle.text = a.info ?: ""
        ImageLoader.load(h.poster, a.poster)
        h.itemView.setOnClickListener { onClick(a) }
        h.itemView.setOnLongClickListener { onLong?.invoke(a); onLong != null }
    }
}

class HomeActivity : TvActivity() {

    private lateinit var tiles: RecyclerView
    private lateinit var history: RecyclerView
    private lateinit var historyTitle: View
    private lateinit var apps: RecyclerView
    private lateinit var net: TextView

    private val tileAdapter = TileAdapter()
    private lateinit var appAdapter: TileAdapter
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_home)
        tiles = findViewById(R.id.tiles)
        history = findViewById(R.id.history)
        historyTitle = findViewById(R.id.historyTitle)
        apps = findViewById(R.id.apps)
        net = findViewById(R.id.net)

        tiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tiles.adapter = tileAdapter
        tiles.itemAnimator = null

        historyAdapter = HistoryAdapter { a ->
            startActivity(
                Intent(this, AnimeActivity::class.java)
                    .putExtra("url", a.url).putExtra("title", a.title).putExtra("poster", a.poster)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        history.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        history.adapter = historyAdapter
        history.itemAnimator = null

        appAdapter = TileAdapter { t ->
            val pkg = t.pkg ?: return@TileAdapter
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
        apps.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        apps.adapter = appAdapter
        apps.itemAnimator = null

        tileAdapter.set(buildTiles())
        tiles.post { tiles.getChildAt(0)?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        val h = Store.history()
        historyAdapter.set(h)
        val vis = if (h.isEmpty()) View.GONE else View.VISIBLE
        history.visibility = vis
        historyTitle.visibility = vis
        appAdapter.set(buildApps())
        net.text = networkText()
        if (currentFocus == null) tiles.post { tiles.getChildAt(0)?.requestFocus() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already here: jump back to the first tile
        findViewById<View>(R.id.scroll).scrollTo(0, 0)
        tiles.scrollToPosition(0)
        tiles.post { tiles.getChildAt(0)?.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // this is the launcher: Back does nothing
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun launchIntent(pkg: String): Intent? {
        val pm = packageManager
        return try {
            (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            null
        }
    }

    private fun start(i: Intent?) {
        if (i == null) { Toast.makeText(this, "Не знайдено на цьому пристрої", Toast.LENGTH_SHORT).show(); return }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Не вдалося відкрити: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildTiles(): List<Tile> {
        val out = ArrayList<Tile>()
        out.add(Tile("AniTube", "🎬") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        launchIntent(YOUTUBE)?.let { i -> out.add(Tile("YouTube", "▶️") { start(i) }) }
        out.add(Tile("Флешка / файли", "💾") { startActivity(Intent(this, FilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        launchIntent(MIRACAST)?.let { i -> out.add(Tile("Трансляція", "📡") { start(i) }) }
        launchIntent(SOURCE)?.let { i -> out.add(Tile("HDMI / джерело", "🔌") { start(i) }) }
        launchIntent(PROJECTOR_SETTINGS)?.let { i -> out.add(Tile("Фокус, трапеція", "🔧") { start(i) }) }
        out.add(Tile("Wi-Fi", "📶") { start(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        out.add(Tile("Налаштування", "⚙️") { start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        val factory = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(OLD_LAUNCHER, "com.htc.activity.HomeActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageManager.resolveActivity(factory, 0) != null) {
            out.add(Tile("Заводський екран", "🏠") { start(factory) })
        }
        return out
    }

    private fun buildApps(): List<Tile> {
        val pm = packageManager
        val skip = setOf(packageName, YOUTUBE, MIRACAST, SOURCE, PROJECTOR_SETTINGS, OLD_LAUNCHER, "com.android.tv.settings")
        val found = LinkedHashMap<String, Tile>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(q, 0) } catch (e: Exception) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg in skip || found.containsKey(pkg)) continue
                val label = ri.loadLabel(pm).toString()
                val icon = try {
                    pm.getApplicationBanner(pkg) ?: ri.loadIcon(pm)
                } catch (e: PackageManager.NameNotFoundException) {
                    ri.loadIcon(pm)
                }
                val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setComponent(ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                found[pkg] = Tile(label, null, icon, pkg) { start(intent) }
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    private fun networkText(): String {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "⚠ Немає мережі"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "📶 Wi-Fi підключено"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "🔗 Кабель підключено"
                else -> "Мережа підключена"
            }
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val MIRACAST = "com.softwinner.miracastReceiver"
        const val SOURCE = "com.softwinner.awsource"
        const val PROJECTOR_SETTINGS = "com.htc.htcsettings"
        const val OLD_LAUNCHER = "com.htc.htclauncherhighenglishd08"
    }
}
