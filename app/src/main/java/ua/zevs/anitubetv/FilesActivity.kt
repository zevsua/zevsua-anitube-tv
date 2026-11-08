package ua.zevs.anitubetv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FilesActivity : TvActivity() {

    private data class Entry(val label: String, val file: File, val isDir: Boolean)

    private lateinit var list: RecyclerView
    private lateinit var pathView: TextView
    private lateinit var message: TextView
    private lateinit var adapter: TextAdapter

    private var current: File? = null          // null = list of drives
    private var entries: List<Entry> = emptyList()
    private val rootPaths = HashSet<String>()
    private val focusMemory = HashMap<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_files)
        list = findViewById(R.id.list)
        pathView = findViewById(R.id.path)
        message = findViewById(R.id.message)
        adapter = TextAdapter(onClick = ::open)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        if (!hasAccess() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        showRoots()
    }

    override fun onResume() {
        super.onResume()
        // refresh watched marks after returning from the player
        current?.let { show(it, keepFocus = true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (current == null) showRoots() else show(current!!)
    }

    private fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun showRoots() {
        current = null
        pathView.text = "Накопичувачі"
        val sm = getSystemService(StorageManager::class.java)
        val out = ArrayList<Entry>()
        rootPaths.clear()
        for (v in sm.storageVolumes) {
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = volumeDir(v) ?: continue
            rootPaths.add(dir.absolutePath)
            val icon = if (v.isRemovable) "💾" else "🗄"
            val name = v.getDescription(this) ?: dir.name
            out.add(Entry("$icon  $name", dir, true))
        }
        entries = out
        adapter.set(out.map { it.label })
        val hasUsb = sm.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        if (!hasUsb) {
            message.text = "Флешку не знайдено. Вийми й встав її ще раз (краще в інший USB-порт), зачекай 10 секунд і зайди сюди знову."
            message.visibility = View.VISIBLE
        } else message.visibility = View.GONE
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    private fun volumeDir(v: android.os.storage.StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        return try {
            val m = v.javaClass.getMethod("getPath")
            File(m.invoke(v) as String)
        } catch (e: Exception) {
            null
        }
    }

    private fun show(dir: File, keepFocus: Boolean = false) {
        current = dir
        pathView.text = dir.absolutePath
        val files = dir.listFiles()
        if (files == null) {
            message.text = if (hasAccess()) "Не вдалося прочитати цю папку." else
                "Немає дозволу на читання файлів. Дозволь доступ або запусти скрипт встановлення ще раз."
            message.visibility = View.VISIBLE
            entries = emptyList(); adapter.set(emptyList()); return
        }
        val dirs = files.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "LOST.DIR" }
            .sortedWith { a, b -> natural(a.name, b.name) }
        val videos = files.filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }
            .sortedWith { a, b -> natural(a.name, b.name) }
        val out = ArrayList<Entry>()
        dirs.forEach { out.add(Entry("📁  ${it.name}", it, true)) }
        val green = getColor(R.color.watched)
        val orange = getColor(R.color.accent)
        val colors = ArrayList<Int?>()
        dirs.forEach { _ -> colors.add(null) }
        videos.forEach { f ->
            val key = "file://" + f.absolutePath
            val mark = when {
                Store.isWatched(key) -> "✓"
                Store.isStarted(key) -> "▶"
                else -> "🎬"
            }
            colors.add(if (Store.isWatched(key)) green else if (Store.isStarted(key)) orange else null)
            out.add(Entry("$mark  ${f.nameWithoutExtension}    ${size(f.length())}", f, false))
        }
        entries = out
        adapter.set(out.map { it.label }, colors)
        if (out.isEmpty()) {
            message.text = "Тут немає папок чи відео."
            message.visibility = View.VISIBLE
        } else message.visibility = View.GONE

        val idx = if (keepFocus) focusMemory[dir.absolutePath] ?: 0 else {
            // jump to the first unwatched video if any
            val firstNew = out.indexOfFirst { !it.isDir && !Store.isWatched("file://" + it.file.absolutePath) }
            if (firstNew >= 0) firstNew else 0
        }
        list.scrollToPosition(idx)
        list.post {
            (list.findViewHolderForAdapterPosition(idx)?.itemView ?: list.getChildAt(0))?.requestFocus()
        }
    }

    private fun open(pos: Int) {
        val e = entries.getOrNull(pos) ?: return
        current?.let { focusMemory[it.absolutePath] = pos }
        if (e.isDir) { show(e.file); return }
        val dir = e.file.parentFile ?: return
        val videos = entries.filter { !it.isDir }
        val eps = videos.map { Episode(it.file.nameWithoutExtension, "file://" + it.file.absolutePath, "local") }
        Session.anime = AnimeItem(0, dir.name, "local:" + dir.absolutePath, null)
        Session.episodes = eps
        Session.index = videos.indexOfFirst { it.file == e.file }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = current
        if (c == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed(); return
        }
        val parent = c.parentFile
        if (c.absolutePath in rootPaths || parent == null) showRoots() else show(parent, keepFocus = true)
    }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f ГБ", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%d МБ", b / (1L shl 20))
        else -> String.format("%d КБ", b / 1024)
    }

    companion object {
        val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts", "flv", "wmv", "mpg", "mpeg", "3gp")

        /** "Ep 2" < "Ep 10" */
        fun natural(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]; val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0'); val ny = y.trimStart('0')
                    if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size - rb.size
        }
    }
}
