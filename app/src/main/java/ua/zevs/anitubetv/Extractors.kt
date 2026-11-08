package ua.zevs.anitubetv

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.webkit.CookieManager
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

data class Stream(
    val url: String,
    val headers: Map<String, String>,
    val subtitle: String? = null,
    /** added as ?token= to every request (fenix) */
    val token: String? = null
)

object Extractors {

    private const val ANITUBE_REFERER = "https://anitube.in.ua/"

    /**
     * Finds a playable stream for an episode iframe url.
     * Fast path: parse known players (ashdi, tortuga) directly.
     * Fallback: open the player page in a hidden WebView and catch the video request.
     */
    fun resolve(activity: Activity, iframe: String, cb: (Result<Stream>) -> Unit): Cancelable {
        val handle = Cancelable()
        Bg.run({ direct(iframe) }) { r ->
            if (handle.cancelled) return@run
            val s = r.getOrNull()
            if (s != null) {
                cb(Result.success(s))
            } else {
                val sniffer = WebSniffer(activity, iframe) { res -> if (!handle.cancelled) cb(res) }
                handle.onCancel = { sniffer.stop() }
                sniffer.start()
            }
        }
        return handle
    }

    private fun direct(iframe: String): Stream? {
        val lower = iframe.lowercase()
        return try {
            when {
                lower.startsWith("file://") || lower.startsWith("content://") -> Stream(iframe, emptyMap())
                Regex("\\.(m3u8|mp4)(\\?|$)").containsMatchIn(lower) ->
                    Stream(iframe, mapOf("User-Agent" to Net.UA, "Referer" to ANITUBE_REFERER))
                lower.contains("ashdi.") -> ashdi(iframe)
                lower.contains("tortuga.") -> tortuga(iframe)
                lower.contains("fenixplay.") -> fenix(iframe)
                lower.contains("moonanime.") -> moon(iframe)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun unescape(s: String) = s.replace("\\/", "/").replace("\\u0026", "&").trim()

    private fun ashdi(url: String): Stream? {
        val html = Net.get(url, mapOf("Referer" to ANITUBE_REFERER))
        val file = Regex("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
            ?.let { unescape(it) } ?: return null
        val sub = Regex("[\"']?subtitle[\"']?\\s*:\\s*[\"']([^\"']*)[\"']").find(html)?.groupValues?.get(1)
            ?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        val link = pickQuality(file) ?: return null
        if (!link.startsWith("http")) return null
        return Stream(
            link,
            mapOf("User-Agent" to Net.UA, "Referer" to "https://ashdi.vip/", "Origin" to "https://ashdi.vip"),
            sub?.let { pickQuality(it) }
        )
    }

    private fun tortuga(url: String): Stream? {
        val page = url.replace(Regex("https?://tortuga\\.[a-z]{2,4}/vod/"), "https://tortuga.tw/vod/")
        val html = Net.get(page, mapOf("Referer" to ANITUBE_REFERER))
        val enc = Regex("file:\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1) ?: return null
        val link = if (enc.startsWith("http")) enc else {
            val clean = enc.replace("=", "")
            val bytes = Base64.decode(clean, Base64.NO_WRAP or Base64.NO_PADDING)
            String(bytes, Charsets.UTF_8).reversed()
        }
        if (!link.startsWith("http")) return null
        return Stream(link, mapOf("User-Agent" to Net.UA, "Referer" to "https://tortuga.tw/", "Origin" to "https://tortuga.tw"))
    }

    // ------------------------------------------------------------ fenixplay
    private fun fenix(url: String): Stream? {
        val page = Net.get(url, mapOf("Referer" to ANITUBE_REFERER))
        val playlistJson = Regex("window\\.FENIX_PLAYLIST\\s*=\\s*(\\{.+?\\});", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1) ?: return null
        val accessJson = Regex("window\\.FENIX_MEDIA_ACCESS\\s*=\\s*(\\{.+?\\});", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
        val playlist = JSONObject(playlistJson)
        val access = accessJson?.let { runCatching { JSONObject(it) }.getOrNull() }

        val token = access?.optString("token")?.takeIf { it.isNotBlank() }
            ?: playlist.optJSONObject("playback")?.optString("token")?.takeIf { it.isNotBlank() }
        val origin = access?.optString("origin")?.takeIf { it.isNotBlank() } ?: "https://media.fenixplay.xyz"

        val slug = playlist.optString("slug").takeIf { it.isNotBlank() } ?: return null
        var edition = playlist.optString("default_edition").ifBlank { "tv" }
        var number = 1
        playlist.optJSONArray("episodes")?.optJSONObject(0)?.let { ep ->
            ep.optString("edition").takeIf { it.isNotBlank() }?.let { edition = it }
            ep.optInt("number", 0).takeIf { it > 0 }?.let { number = it }
        }

        val apiHeaders = HashMap<String, String>()
        apiHeaders["Referer"] = url
        if (token != null) apiHeaders["x-fenix-playback"] = token
        val playback = JSONObject(Net.get("https://fenixplay.xyz/api/playback/$slug/episodes/$edition/$number", apiHeaders))
        val ep = playback.optJSONObject("episode") ?: return null
        var master = ep.optString("hls_master_url").takeIf { it.isNotBlank() }
            ?: ep.optJSONArray("tracks")?.optJSONObject(0)?.optString("hls_url")?.takeIf { it.isNotBlank() }
            ?: return null
        if (master.startsWith("/")) master = origin + master

        var subtitle: String? = null
        ep.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val o = subs.optJSONObject(i) ?: continue
                var u = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (u.startsWith("/")) u = origin + u
                val good = o.optString("language") == "uk" && (o.optString("kind") == "full" || o.optBoolean("is_default"))
                if (good || subtitle == null) subtitle = u
                if (good) break
            }
        }
        return Stream(
            master,
            mapOf("User-Agent" to Net.UA, "Origin" to origin, "Referer" to "$origin/"),
            subtitle,
            token
        )
    }

    // ------------------------------------------------------------ moonanime
    private val moonHeaders = mapOf(
        "User-Agent" to Net.UA,
        "Accept" to "*/*",
        "Accept-Language" to "uk;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://moonanime.art",
        "Origin" to "https://moonanime.art"
    )

    private fun moon(url: String): Stream? {
        val html = Net.get(url, moonHeaders)
        val doc = Jsoup.parse(html)
        val script = doc.select("script").map { it.html() }.firstOrNull { it.contains("atob(") } ?: return null
        val b64 = Regex("atob\\([\"']([A-Za-z0-9+/=]+)[\"']\\)").find(script)?.groupValues?.get(1) ?: return null
        val decrypted = moonDecryptScript(b64)
        if (decrypted.isBlank()) return null

        val fn = Regex("([a-zA-Z0-9_$]+)\\([\"']([A-Za-z0-9+/=]+)[\"']\\)").find(decrypted)?.groupValues?.get(1)
        var clean = decrypted
        if (fn != null) {
            val body = Regex("function " + Regex.escape(fn) + "\\(e\\)\\{(.*?)\\}").find(decrypted)?.groupValues?.get(1) ?: ""
            val key = Regex("(?:var|let|const)\\s+\\w+\\s*=\\s*[\"'](.*?)[\"']").find(body)?.groupValues?.get(1)
            if (!key.isNullOrEmpty()) {
                clean = Regex(Regex.escape(fn) + "\\([\"']([A-Za-z0-9+/=]+)[\"']\\)").replace(decrypted) { m ->
                    "\"" + moonDecryptLink(m.groupValues[1], key) + "\""
                }
            }
        }
        val file = Regex("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']").find(clean)?.groupValues?.get(1)
            ?.let { unescape(it) } ?: return null
        val sub = Regex("[\"']?subtitle[\"']?\\s*:\\s*[\"']([^\"']*)[\"']").find(clean)?.groupValues?.get(1)
            ?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        val link = pickQuality(file) ?: return null
        if (!link.startsWith("http")) return null
        return Stream(link, moonHeaders, sub?.let { pickQuality(it) })
    }

    private fun moonDecryptScript(b64: String): String {
        return try {
            val d = Base64.decode(b64, Base64.DEFAULT)
            if (d.size <= 33) return ""
            var acc = d[0].toInt() and 0xFF
            val key = d.copyOfRange(1, 33)
            val out = ByteArray(d.size - 33)
            for (i in out.indices) {
                val c = d[i + 33].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF
                out[i] = (c xor k xor acc).toByte()
                acc = (c + k) and 0xFF
            }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun moonDecryptLink(b64: String, key: String): String {
        return try {
            val d = Base64.decode(b64, Base64.DEFAULT)
            val k = key.toByteArray(Charsets.UTF_8)
            val out = ByteArray(d.size) { i -> (d[i].toInt() xor k[i % k.size].toInt()).toByte() }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /** PlayerJS strings may look like "[1080p]url1,[720p]url2". Pick the best one. */
    private fun pickQuality(file: String): String? {
        if (!file.startsWith("[")) return file.split(" or ").first().trim()
        val items = Regex("\\[([^\\]]*)\\]([^,\\[]+)").findAll(file).map {
            val q = Regex("(\\d{3,4})").find(it.groupValues[1])?.value?.toIntOrNull() ?: 0
            q to it.groupValues[2].split(" or ").first().trim()
        }.toList()
        return items.maxByOrNull { it.first }?.second
    }
}

class Cancelable {
    @Volatile var cancelled = false
    var onCancel: (() -> Unit)? = null
    fun cancel() {
        cancelled = true
        onCancel?.invoke()
    }
}

/** Loads the player page invisibly and grabs the first video stream request. */
@SuppressLint("SetJavaScriptEnabled")
class WebSniffer(
    private val activity: Activity,
    private val url: String,
    private val cb: (Result<Stream>) -> Unit
) {
    private var web: WebView? = null
    private var done = false
    private var mp4Candidate: Stream? = null
    private val started = System.currentTimeMillis()

    private val ticker = object : Runnable {
        override fun run() {
            if (done) return
            val w = web ?: return
            w.evaluateJavascript(CLICK_JS, null)
            val elapsed = System.currentTimeMillis() - started
            val mp4 = mp4Candidate
            if (mp4 != null && elapsed > 6000) {
                finish(Result.success(mp4)); return
            }
            if (elapsed > 30000) {
                finish(Result.failure(IOException("Не вдалося отримати відео з цього плеєра.\nСпробуй іншу озвучку або інший плеєр.")))
                return
            }
            Bg.main.postDelayed(this, 1500)
        }
    }

    fun start() {
        val w = WebView(activity)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.userAgentString = Net.UA
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                val l = u.lowercase()
                val isHls = Regex("\\.m3u8(\\?|#|$)").containsMatchIn(l)
                val isMp4 = Regex("\\.mp4(\\?|#|$)").containsMatchIn(l)
                if (isHls || isMp4) {
                    val h = HashMap<String, String>()
                    request.requestHeaders?.forEach { (k, v) ->
                        if (k.equals("Referer", true) || k.equals("Origin", true)) h[k] = v
                    }
                    h["User-Agent"] = Net.UA
                    if (!h.keys.any { it.equals("Referer", true) }) h["Referer"] = url
                    if (!h.keys.any { it.equals("Origin", true) }) {
                        val o = android.net.Uri.parse(url)
                        h["Origin"] = "${o.scheme}://${o.host}"
                    }
                    try {
                        CookieManager.getInstance().getCookie(u)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
                    } catch (_: Exception) {
                    }
                    val s = Stream(u, h)
                    Bg.main.post {
                        if (isHls) finish(Result.success(s)) else if (mp4Candidate == null) mp4Candidate = s
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // stay on the player page, ignore ad redirects
                return request.isForMainFrame && request.url.host != android.net.Uri.parse(url).host
            }
        }
        val root = activity.window.decorView as ViewGroup
        root.addView(w, FrameLayout.LayoutParams(320, 180))
        w.alpha = 0.01f
        w.isFocusable = false
        w.visibility = View.VISIBLE
        w.loadUrl(url, mapOf("Referer" to "https://anitube.in.ua/"))
        Bg.main.postDelayed(ticker, 2500)
    }

    private fun finish(r: Result<Stream>) {
        if (done) return
        done = true
        stop()
        cb(r)
    }

    fun stop() {
        done = true
        val w = web ?: return
        web = null
        try {
            w.stopLoading()
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CLICK_JS = """
            (function(){try{
              var v=document.querySelector('video');
              if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}
              var sel=['.vjs-big-play-button','.plyr__control--overlaid','[class*=big-play]','[class*=play-button]','[class*=playbtn]'];
              for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);if(b){b.click();}}
              var c=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);
              if(c){c.click();}
            }catch(e){}})();
        """
    }
}
