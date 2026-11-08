package ua.zevs.anitubetv

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

data class AnimeItem(
    val id: Int,
    val title: String,
    val url: String,
    val poster: String?,
    val info: String? = null
)

data class Episode(val name: String, val iframe: String, val sourceId: String)

data class Source(val id: String, val name: String, val episodes: List<Episode>)

data class Details(
    val id: Int,
    val title: String,
    val url: String,
    val poster: String?,
    val description: String,
    val sources: List<Source>
)

object Anitube {

    fun animeId(url: String): Int =
        Regex("/(\\d+)-").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun imageOf(el: Element?): String? {
        val img = el?.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return if (src.isBlank()) null else Net.fix(src)
    }

    private fun parseList(doc: Document): List<AnimeItem> {
        val out = ArrayList<AnimeItem>()
        val seen = HashSet<String>()
        for (story in doc.select("article.story")) {
            val a = story.selectFirst("h2 a") ?: story.selectFirst("a[href*=.html]") ?: continue
            val url = Net.fix(a.attr("href"))
            if (!seen.add(url)) continue
            val title = (story.selectFirst("h2")?.text() ?: a.text()).trim()
            if (title.isBlank()) continue
            val poster = imageOf(story.selectFirst(".story_c_l")) ?: imageOf(story)
            var info: String? = null
            try {
                val dt = story.selectFirst("div.story_infa dt:contains(Серій)")
                val s = dt?.nextSibling()?.toString()?.let { Jsoup.parse(it).text() }?.trim()
                if (!s.isNullOrBlank()) info = "Серій: $s"
            } catch (_: Exception) {
            }
            out.add(AnimeItem(animeId(url), title, url, poster, info))
        }
        return out
    }

    /** page >= 1 */
    fun latest(page: Int): List<AnimeItem> {
        val url = if (page <= 1) "${Net.BASE}/anime/" else "${Net.BASE}/anime/page/$page/"
        return parseList(Jsoup.parse(Net.get(url), Net.BASE))
    }

    fun search(query: String): List<AnimeItem> {
        val html = Net.post(
            "${Net.BASE}/index.php?do=search",
            mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to "0",
                "full_search" to "0",
                "result_from" to "1",
                "story" to query
            ),
            mapOf("Referer" to "${Net.BASE}/")
        )
        return parseList(Jsoup.parse(html, Net.BASE))
    }

    fun details(url: String): Details {
        val html = Net.get(url)
        val doc = Jsoup.parse(html, Net.BASE)
        val id = animeId(url)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("article.story h2")?.text()
            ?: doc.title()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { Net.fix(it) }
            ?: imageOf(doc.selectFirst(".story_c_left"))
        val description = (doc.selectFirst("div.story_c_text")?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: "").trim()

        var sources: List<Source> = emptyList()
        var ajaxError: String? = null
        if (html.contains("playlists-ajax")) {
            try {
                sources = loadAjaxPlaylists(url, id, html)
            } catch (e: Exception) {
                ajaxError = e.message
            }
        }
        if (sources.isEmpty()) {
            sources = parseRalode(doc)
        }
        if (sources.isEmpty() && ajaxError != null) throw IOException("Не вдалося завантажити серії: $ajaxError")
        return Details(id, title.trim(), url, poster, description, sources)
    }

    private fun loadAjaxPlaylists(pageUrl: String, id: Int, pageHtml: String): List<Source> {
        val hash = Regex("dle_login_hash\\s*=\\s*'([^']+)'").find(pageHtml)?.groupValues?.get(1) ?: ""
        val url = "${Net.BASE}/engine/ajax/playlists.php?news_id=$id&xfield=playlist&user_hash=" +
                URLEncoder.encode(hash, "UTF-8")
        val body = Net.get(url, mapOf("Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest"))
        val json = JSONObject(body)
        if (!json.optBoolean("success", false)) {
            throw IOException(json.optString("message", "сайт повернув помилку"))
        }
        val doc = Jsoup.parse(json.optString("response", ""), Net.BASE)

        val names = LinkedHashMap<String, String>()
        for (li in doc.select(".playlists-lists .playlists-items li")) {
            names[li.attr("data-id")] = li.text().trim()
        }
        val groups = LinkedHashMap<String, MutableList<Episode>>()
        for (li in doc.select(".playlists-videos .playlists-items li")) {
            val sid = li.attr("data-id")
            val file = li.attr("data-file")
            if (file.isBlank()) continue
            groups.getOrPut(sid) { ArrayList() }.add(Episode(li.text().trim(), Net.fix(file), sid))
        }
        return groups.map { (sid, eps) -> Source(sid, pathName(sid, names), eps) }
    }

    private fun pathName(id: String, names: Map<String, String>): String {
        val parts = id.split("_")
        val list = ArrayList<String>()
        for (i in parts.indices) {
            val key = parts.subList(0, i + 1).joinToString("_")
            names[key]?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        }
        return if (list.isEmpty()) "Варіант $id" else list.joinToString("  ›  ")
    }

    private fun parseRalode(doc: Document): List<Source> {
        val script = doc.select("script").map { it.data() }.firstOrNull { it.contains("RalodePlayer.init(") }
            ?: return emptyList()
        val m = Regex("RalodePlayer\\.init\\((.*),(\\[\\[.*\\]\\])", RegexOption.DOT_MATCHES_ALL).find(script)
            ?: return emptyList()
        return try {
            val audios = JSONArray(m.groupValues[1])
            val videos = JSONArray(m.groupValues[2])
            val out = ArrayList<Source>()
            for (i in 0 until audios.length()) {
                val arr = videos.optJSONArray(i) ?: continue
                val eps = ArrayList<Episode>()
                for (j in 0 until arr.length()) {
                    val o = arr.optJSONObject(j) ?: continue
                    val src = Regex("src=\"([^\"]+)\"").find(o.optString("code"))?.groupValues?.get(1) ?: continue
                    eps.add(Episode(o.optString("name", "Серія ${j + 1}"), Net.fix(src), i.toString()))
                }
                if (eps.isNotEmpty()) out.add(Source(i.toString(), Jsoup.parse(audios.optString(i)).text(), eps))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
