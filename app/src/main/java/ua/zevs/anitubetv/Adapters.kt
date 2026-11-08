package ua.zevs.anitubetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private fun View.focusZoom(scale: Float = 1.06f) {
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
    }
}

class AnimeAdapter(
    private val onClick: (AnimeItem) -> Unit,
    private val onLongClick: ((AnimeItem) -> Unit)? = null,
    private val onNearEnd: (() -> Unit)? = null
) : RecyclerView.Adapter<AnimeAdapter.VH>() {

    val items = ArrayList<AnimeItem>()

    fun set(list: List<AnimeItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun add(list: List<AnimeItem>) {
        val start = items.size
        val known = items.map { it.url }.toHashSet()
        val fresh = list.filter { it.url !in known }
        items.addAll(fresh); notifyItemRangeInserted(start, fresh.size)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_anime, parent, false)
        v.focusZoom()
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.subtitle.visibility = if (it.info.isNullOrBlank()) View.GONE else View.VISIBLE
        h.subtitle.text = it.info ?: ""
        ImageLoader.load(h.poster, it.poster)
        h.itemView.setOnClickListener { _ -> onClick(it) }
        h.itemView.setOnLongClickListener { _ -> onLongClick?.invoke(it); onLongClick != null }
        if (position >= items.size - 6) onNearEnd?.invoke()
    }
}

class TextAdapter(
    private val onClick: (Int) -> Unit,
    private val onFocus: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<TextAdapter.VH>() {

    val items = ArrayList<String>()
    val colors = ArrayList<Int?>()
    var selected = -1

    fun set(list: List<String>, colorList: List<Int?> = emptyList()) {
        items.clear(); items.addAll(list)
        colors.clear(); colors.addAll(colorList)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_text, parent, false)
        v.focusZoom(1.03f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.text.text = items[position]
        val c = colors.getOrNull(position)
        h.text.setTextColor(c ?: h.text.context.getColor(R.color.text))
        h.text.setTypeface(null, if (position == selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
        h.itemView.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
            if (has) onFocus?.invoke(h.bindingAdapterPosition)
        }
    }
}
