package ua.zevs.anitubetv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request

object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String?) {
        view.tag = url
        view.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        cache.get(url)?.let { view.setImageBitmap(it); return }
        Bg.run({ fetch(url) }) { r ->
            val bmp = r.getOrNull() ?: return@run
            cache.put(url, bmp)
            if (view.tag == url) view.setImageBitmap(bmp)
        }
    }

    private fun fetch(url: String): Bitmap? {
        val req = Request.Builder().url(url).header("User-Agent", Net.UA).header("Referer", Net.BASE + "/").build()
        val bytes = Net.client.newCall(req).execute().use { it.body?.bytes() } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 300) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}
