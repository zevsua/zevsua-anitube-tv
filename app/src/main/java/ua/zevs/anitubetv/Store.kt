package ua.zevs.anitubetv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("anitube_tv", Context.MODE_PRIVATE)
    }

    // ---- settings
    var seekStep: Int
        get() = p.getInt("seek_step", 10)
        set(v) = p.edit().putInt("seek_step", v).apply()

    var skipLen: Int
        get() = p.getInt("skip_len", 85)
        set(v) = p.edit().putInt("skip_len", v).apply()

    /** 0 = auto, otherwise max height */
    var maxQuality: Int
        get() = p.getInt("max_quality", 0)
        set(v) = p.edit().putInt("max_quality", v).apply()

    var autoNext: Boolean
        get() = p.getBoolean("auto_next", true)
        set(v) = p.edit().putBoolean("auto_next", v).apply()

    /** "rating" or "news_read" */
    var seasonSort: String
        get() = p.getString("season_sort", "rating") ?: "rating"
        set(v) = p.edit().putString("season_sort", v).apply()

    // ---- progress
    private fun key(url: String) = url.hashCode().toString()

    fun savePos(url: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        p.edit().putLong("pos_" + key(url), pos).putLong("dur_" + key(url), dur).apply()
    }

    fun pos(url: String): Long = p.getLong("pos_" + key(url), 0)
    fun dur(url: String): Long = p.getLong("dur_" + key(url), 0)

    fun isWatched(url: String): Boolean {
        val d = dur(url)
        return d > 0 && pos(url) >= d * 0.9
    }

    fun isStarted(url: String): Boolean = pos(url) > 30_000 && !isWatched(url)

    // ---- chosen source per anime
    fun lastSource(animeId: Int): String? = p.getString("src_$animeId", null)
    fun setLastSource(animeId: Int, sourceId: String) = p.edit().putString("src_$animeId", sourceId).apply()

    // ---- history
    fun history(): List<AnimeItem> {
        val arr = try { JSONArray(p.getString("history", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<AnimeItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                AnimeItem(
                    o.optInt("id"), o.optString("title"), o.optString("url"),
                    o.optString("poster").ifBlank { null }, o.optString("episode").ifBlank { null }
                )
            )
        }
        return out
    }

    fun addHistory(item: AnimeItem, episode: String) {
        val old = history().filter { it.url != item.url }
        val arr = JSONArray()
        arr.put(JSONObject().put("id", item.id).put("title", item.title).put("url", item.url)
            .put("poster", item.poster ?: "").put("episode", episode))
        old.take(19).forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)
                .put("poster", it.poster ?: "").put("episode", it.info ?: ""))
        }
        p.edit().putString("history", arr.toString()).apply()
    }

    fun removeHistory(url: String) {
        val arr = JSONArray()
        history().filter { it.url != url }.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)
                .put("poster", it.poster ?: "").put("episode", it.info ?: ""))
        }
        p.edit().putString("history", arr.toString()).apply()
    }
}

/** What is currently being watched (shared between screens). */
object Session {
    var anime: AnimeItem? = null
    var episodes: List<Episode> = emptyList()
    var index: Int = 0
}
