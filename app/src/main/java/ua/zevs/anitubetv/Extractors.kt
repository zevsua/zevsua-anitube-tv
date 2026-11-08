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
import java.io.IOException

data class Stream(val url: String, val headers: Map<String, String>, val subtitle: String? = null)

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
                Regex("\\.(m3u8|mp4)(\\?|$)").containsMatchIn(lower) ->
                    Stream(iframe, mapOf("User-Agent" to Net.UA, "Referer" to ANITUBE_REFERER))
                lower.contains("ashdi.") -> ashdi(iframe)
                lower.contains("tortuga.") -> tortuga(iframe)
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
