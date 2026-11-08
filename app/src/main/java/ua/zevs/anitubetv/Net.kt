package ua.zevs.anitubetv

import android.os.Handler
import android.os.Looper
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Net {
    const val BASE = "https://anitube.in.ua"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                synchronized(list) {
                    for (c in cookies) {
                        list.removeAll { it.name == c.name }
                        list.add(c)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val list = cookieStore[url.host] ?: return emptyList()
                synchronized(list) { return list.filter { it.matches(url) } }
            }
        })
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(fix(url)).header("User-Agent", UA)
            .header("Accept-Language", "uk-UA,uk;q=0.9,en;q=0.6")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val b = Request.Builder().url(fix(url)).post(fb.build()).header("User-Agent", UA)
            .header("Accept-Language", "uk-UA,uk;q=0.9,en;q=0.6")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    /** Makes absolute https URL from relative / protocol-relative one. */
    fun fix(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> BASE + u
            u.startsWith("http://") || u.startsWith("https://") -> u
            else -> "$BASE/$u"
        }
    }
}

/** Tiny background runner. */
object Bg {
    private val pool = Executors.newFixedThreadPool(3)
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }
}
