package ua.zevs.anitubetv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var historyList: RecyclerView
    private lateinit var historyTitle: TextView
    private lateinit var listTitle: TextView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar

    private lateinit var gridAdapter: AnimeAdapter
    private lateinit var historyAdapter: AnimeAdapter

    private var page = 1
    private var loading = false
    private var latestMode = true
    private var endReached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        historyList = findViewById(R.id.historyList)
        historyTitle = findViewById(R.id.historyTitle)
        listTitle = findViewById(R.id.listTitle)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)

        gridAdapter = AnimeAdapter(::openAnime, null) { if (latestMode) loadMore() }
        val span = maxOf(3, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 165f).toInt())
        grid.layoutManager = GridLayoutManager(this, span)
        grid.adapter = gridAdapter

        historyAdapter = AnimeAdapter(::openAnime, { item ->
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf("Відкрити", "Прибрати з «Продовжити перегляд»")) { _, which ->
                    if (which == 0) openAnime(item) else { Store.removeHistory(item.url); refreshHistory() }
                }.show()
        })
        historyList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        historyList.adapter = historyAdapter

        findViewById<View>(R.id.btnSearch).setOnClickListener { askSearch() }
        findViewById<View>(R.id.btnNew).setOnClickListener { showLatest() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings(this) }

        showLatest()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
    }

    private fun refreshHistory() {
        val h = Store.history()
        historyAdapter.set(h)
        val vis = if (h.isEmpty()) View.GONE else View.VISIBLE
        historyTitle.visibility = vis
        historyList.visibility = vis
    }

    private fun openAnime(item: AnimeItem) {
        startActivity(
            Intent(this, AnimeActivity::class.java)
                .putExtra("url", item.url)
                .putExtra("title", item.title)
                .putExtra("poster", item.poster)
        )
    }

    private fun showLatest() {
        latestMode = true
        endReached = false
        page = 0
        listTitle.text = "Нові релізи"
        gridAdapter.set(emptyList())
        loadMore()
    }

    private fun loadMore() {
        if (loading || endReached) return
        loading = true
        progress.visibility = View.VISIBLE
        message.visibility = View.GONE
        val next = page + 1
        Bg.run({ Anitube.latest(next) }) { r ->
            loading = false
            progress.visibility = View.GONE
            r.onSuccess { list ->
                page = next
                if (list.isEmpty()) endReached = true
                val wasEmpty = gridAdapter.items.isEmpty()
                gridAdapter.add(list)
                if (wasEmpty && list.isNotEmpty() && Store.history().isEmpty()) {
                    grid.post { grid.getChildAt(0)?.requestFocus() }
                }
            }.onFailure { showError(it) }
        }
    }

    private fun askSearch() {
        val input = EditText(this).apply {
            hint = "Назва аніме"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 20f
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Пошук")
            .setView(input)
            .setPositiveButton("Шукати") { _, _ -> doSearch(input.text.toString()) }
            .setNegativeButton("Скасувати", null)
            .create()
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                dlg.dismiss(); doSearch(input.text.toString()); true
            } else false
        }
        dlg.show()
        input.requestFocus()
    }

    private fun doSearch(q: String) {
        val query = q.trim()
        if (query.length < 2) return
        latestMode = false
        listTitle.text = "Пошук: $query"
        gridAdapter.set(emptyList())
        progress.visibility = View.VISIBLE
        message.visibility = View.GONE
        Bg.run({ Anitube.search(query) }) { r ->
            progress.visibility = View.GONE
            r.onSuccess { list ->
                gridAdapter.set(list)
                if (list.isEmpty()) {
                    message.text = "Нічого не знайдено. Спробуй іншу назву (українською або англійською)."
                    message.visibility = View.VISIBLE
                } else grid.post { grid.getChildAt(0)?.requestFocus() }
            }.onFailure { showError(it) }
        }
    }

    private fun showError(e: Throwable) {
        message.text = "Помилка: ${e.message ?: e.javaClass.simpleName}\nПеревір інтернет і спробуй ще раз."
        message.visibility = View.VISIBLE
    }

    companion object {
        fun showSettings(a: Activity, onDone: (() -> Unit)? = null) {
            val q = Store.maxQuality
            val items = arrayOf(
                "Крок перемотки ←/→: ${Store.seekStep} с",
                "Пропуск опенінгу (кнопка ↑): ${Store.skipLen} с",
                "Максимальна якість: ${if (q == 0) "Авто" else "${q}p"}",
                "Автоматично наступна серія: ${if (Store.autoNext) "так" else "ні"}"
            )
            AlertDialog.Builder(a).setTitle("Налаштування").setItems(items) { _, which ->
                when (which) {
                    0 -> choose(a, "Крок перемотки", listOf(5, 10, 15, 20, 30), Store.seekStep, { "$it с" }) {
                        Store.seekStep = it; showSettings(a, onDone)
                    }
                    1 -> choose(a, "Пропуск опенінгу", listOf(60, 75, 80, 85, 90, 100, 120), Store.skipLen, { "$it с" }) {
                        Store.skipLen = it; showSettings(a, onDone)
                    }
                    2 -> choose(a, "Максимальна якість", listOf(0, 1080, 720, 480), Store.maxQuality,
                        { if (it == 0) "Авто" else "${it}p" }) {
                        Store.maxQuality = it; showSettings(a, onDone)
                    }
                    3 -> { Store.autoNext = !Store.autoNext; showSettings(a, onDone) }
                }
            }.setOnCancelListener { onDone?.invoke() }.show()
        }

        private fun choose(a: Activity, title: String, values: List<Int>, current: Int, label: (Int) -> String, set: (Int) -> Unit) {
            val idx = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(a).setTitle(title)
                .setSingleChoiceItems(values.map(label).toTypedArray(), idx) { d, which ->
                    d.dismiss(); set(values[which])
                }.show()
        }
    }
}
