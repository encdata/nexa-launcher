package com.termux.gui.protocol.shared.v0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.HashMap

class RecyclerListAdapter(
    context: Context,
    initialItems: List<Item>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    preferredTextColor: Int? = null
) : RecyclerView.Adapter<RecyclerListAdapter.Holder>() {
    data class Item(
        val text: String,
        val subtitle: String,
        val marker: String,
        val image: Bitmap?,
        val key: String = "",
        val cpu: String = "",
        val memory: String = "",
        val background: Int? = null
    )

    private val inflaterContext = context
    private var items = initialItems
    private val horizontalPadding = context.dp(12)
    private val iconSize = context.dp(30)
    private val iconGap = context.dp(8)
    private val titleColor: Int
    private val subtitleColor: Int

    init {
        val colors = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
        titleColor = preferredTextColor ?: colors.getColor(0, 0xFF202124.toInt())
        subtitleColor = if (preferredTextColor == null) {
            colors.getColor(1, 0xFF666666.toInt())
        } else {
            (preferredTextColor and 0x00FFFFFF) or 0xB0000000.toInt()
        }
        colors.recycle()
        setHasStableIds(false)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = LinearLayout(inflaterContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, inflaterContext.dp(56))
        }
        val icon = ImageView(inflaterContext).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
        val marker = TextView(inflaterContext).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(subtitleColor)
            layoutParams = LinearLayout.LayoutParams(iconSize, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val labels = LinearLayout(inflaterContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        labels.addView(TextView(inflaterContext).apply {
            textSize = 14f
            setTextColor(titleColor)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, inflaterContext.dp(30))
        })
        labels.addView(TextView(inflaterContext).apply {
            textSize = 10f
            setTextColor(subtitleColor)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, inflaterContext.dp(18))
        })
        row.addView(icon)
        row.addView(marker)
        val labelGap = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        labelGap.marginStart = iconGap
        row.addView(labels, labelGap)
        val cpu = TextView(inflaterContext).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(subtitleColor)
            layoutParams = LinearLayout.LayoutParams(inflaterContext.dp(56), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val memory = TextView(inflaterContext).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(subtitleColor)
            layoutParams = LinearLayout.LayoutParams(inflaterContext.dp(72), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        row.addView(cpu)
        row.addView(memory)
        return Holder(row, icon, marker, labels.getChildAt(0) as TextView,
            labels.getChildAt(1) as TextView, cpu, memory)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.text
        holder.subtitle.text = item.subtitle
        holder.cpu.text = item.cpu
        holder.cpu.visibility = if (item.cpu.isEmpty()) View.GONE else View.VISIBLE
        holder.memory.text = item.memory
        holder.memory.visibility = if (item.memory.isEmpty()) View.GONE else View.VISIBLE
        if (item.background != null) holder.itemView.setBackgroundColor(item.background)
        if (item.image != null) {
            holder.icon.setImageBitmap(item.image)
            holder.icon.visibility = View.VISIBLE
            holder.marker.visibility = View.GONE
        } else if (item.marker.isNotEmpty()) {
            holder.icon.setImageDrawable(null)
            holder.icon.visibility = View.GONE
            holder.marker.text = item.marker
            holder.marker.visibility = View.VISIBLE
        } else {
            holder.icon.setImageDrawable(null)
            holder.icon.visibility = View.GONE
            holder.marker.visibility = View.GONE
        }
        holder.itemView.setOnClickListener {
            val index = holder.adapterPosition
            if (index != RecyclerView.NO_POSITION) onClick(index)
        }
        holder.itemView.setOnLongClickListener {
            val index = holder.adapterPosition
            if (index != RecyclerView.NO_POSITION) onLongClick(index)
            index != RecyclerView.NO_POSITION
        }
    }

    override fun getItemCount() = items.size

    fun keyAt(position: Int) = items.getOrNull(position)?.key.orEmpty()

    fun submitItems(newItems: List<Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    class Holder(
        view: View,
        val icon: ImageView,
        val marker: TextView,
        val title: TextView,
        val subtitle: TextView,
        val cpu: TextView,
        val memory: TextView
    ) : RecyclerView.ViewHolder(view)

    companion object {
        fun decodeImage(bytes: ByteArray): Bitmap? {
            if (bytes.isEmpty()) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        fun fromJson(context: Context, values: JsonArray?, images: JsonObject? = null): List<Item> {
            if (values == null) return emptyList()
            val decoded = HashMap<String, Bitmap?>()
            return values.mapNotNull { value ->
                if (!value.isJsonObject) return@mapNotNull null
                val row = value.asJsonObject
                val imageKey = row.get("imageKey")?.takeUnless { it.isJsonNull }?.asString
                val encoded = if (imageKey != null) {
                    images?.get(imageKey)?.takeUnless { it.isJsonNull }?.asString
                } else {
                    row.get("image")?.takeUnless { it.isJsonNull }?.asString
                }
                val bitmap = if (encoded.isNullOrEmpty()) null else decoded.getOrPut(encoded) {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                Item(
                    text = row.get("text")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    subtitle = row.get("subtitle")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    marker = row.get("marker")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    image = bitmap,
                    key = row.get("key")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    cpu = row.get("cpu")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    memory = row.get("memory")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    background = row.get("background")?.takeUnless { it.isJsonNull }?.asInt
                )
            }
        }

    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
