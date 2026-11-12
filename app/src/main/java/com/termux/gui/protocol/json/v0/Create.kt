package com.termux.gui.protocol.json.v0

import android.content.Context
import android.os.Build
import android.text.method.LinkMovementMethod
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import com.termux.gui.ConnectionHandler
import com.termux.gui.Util
import com.termux.gui.protocol.shared.v0.*
import com.termux.gui.views.KeyboardImageView
import com.termux.gui.views.SnappingHorizontalScrollView
import com.termux.gui.views.SnappingNestedScrollView
import java.io.DataOutputStream
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt

class Create {
    companion object {
        @Suppress("DEPRECATION")
        fun handleCreateMessage(m: ConnectionHandler.Message, activities: MutableMap<Int, DataClasses.ActivityState>,
                                overlays: MutableMap<Int, DataClasses.Overlay>, rand: Random, out: DataOutputStream, app: Context,
                                eventQueue: LinkedBlockingQueue<ConnectionHandler.Event>) {
            val aid = m.params?.get("aid")?.asInt
            val parent = m.params?.get("parent")?.asInt
            val a = activities[aid]
            val o = overlays[aid]
            if (aid != null) {
                if (a != null) {
                    if (m.method == "registerReusableObject") {
                        val name = m.params?.get("name")?.asString
                            ?: throw IllegalArgumentException("registerReusableObject requires a name")
                        val views = m.params?.get("views")?.asJsonArray
                            ?: throw IllegalArgumentException("registerReusableObject requires views")
                        if (name.isBlank() || views.size() == 0) {
                            throw IllegalArgumentException("reusable object needs a name and at least one view")
                        }
                        a.reusableObjects[name] = views.deepCopy()
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(true))
                        return
                    }
                    if (m.method == "createViewBatch" || m.method == "createReusableObject") {
                        val specifications = if (m.method == "createViewBatch") {
                            m.params?.get("views")?.asJsonArray
                                ?: throw IllegalArgumentException("createViewBatch requires views")
                        } else {
                            val name = m.params?.get("name")?.asString
                                ?: throw IllegalArgumentException("createReusableObject requires a name")
                            val template = a.reusableObjects[name]
                                ?: throw IllegalArgumentException("unknown reusable object: $name")
                            val properties = m.params?.get("properties")?.asJsonObject
                            val mutableProperties = setOf(
                                "align", "background", "blockinput", "bottom", "checked", "clickable",
                                "enabled", "fillViewport", "gravity", "height", "image", "inputtype",
                                "left", "nobar",
                                "line", "margins", "max", "options", "progress", "right", "selected",
                                "singleline", "text", "textColor", "textSize", "top", "visibility",
                                "weight", "width", "x", "y")
                            template.deepCopy().also { copy ->
                                for (viewElement in copy) {
                                    val view = viewElement.asJsonObject
                                    val key = view.get("key")?.asString ?: continue
                                    val overrides = properties?.getAsJsonObject(key) ?: continue
                                    for ((property, value) in overrides.entrySet()) {
                                        if (property !in mutableProperties) {
                                            throw IllegalArgumentException("unsupported reusable object property: $property")
                                        }
                                        view.add(property, value.deepCopy())
                                    }
                                }
                            }
                        }
                        val ids = ArrayList<Int>(specifications.size())
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) { activity ->
                            val density = activity.resources.displayMetrics.density
                            fun dimension(specification: com.google.gson.JsonObject, name: String): Int {
                                val value = specification.get(name) ?: return android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                                    return when (value.asString) {
                                        "MATCH_PARENT" -> android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                        "WRAP_CONTENT" -> android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                        else -> value.asInt
                                    }
                                }
                                return (value.asFloat * density).roundToInt()
                            }
                            fun margin(specification: com.google.gson.JsonObject, name: String): Int =
                                ((specification.get("margins")?.asJsonObject?.get(name)?.asFloat ?: 0f)
                                    * density).roundToInt()

                            for (specificationElement in specifications) {
                                val specification = specificationElement.asJsonObject
                                val parentIndex = specification.get("parentIndex")?.asInt
                                val parentId = if (parentIndex != null) ids[parentIndex]
                                    else specification.get("parent")?.asInt ?: parent
                                val parentView = parentId?.let {
                                    activity.findViewReimplemented<android.view.ViewGroup>(it)
                                } ?: throw IllegalArgumentException("batch view needs a parent")
                                val view = when (specification.get("type")?.asString) {
                                    "frame" -> FrameLayout(activity)
                                    "linear" -> LinearLayout(activity).apply {
                                        orientation = if (specification.get("vertical")?.asBoolean == false)
                                            LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                                    }
                                    "scroll" -> NestedScrollView(activity).apply {
                                        isFillViewport = specification.get("fillViewport")?.asBoolean ?: true
                                        if (specification.get("nobar")?.asBoolean == true) {
                                            isVerticalScrollBarEnabled = false
                                        }
                                    }
                                    "horizontal_scroll" -> HorizontalScrollView(activity).apply {
                                        isFillViewport = specification.get("fillViewport")?.asBoolean ?: true
                                        if (specification.get("nobar")?.asBoolean == true) {
                                            isHorizontalScrollBarEnabled = false
                                        }
                                    }
                                    "edit" -> EditText(activity).apply {
                                        when (specification.get("inputtype")?.asString ?: "text") {
                                            "text" -> inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                                            "textMultiLine" -> inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                                            "phone" -> inputType = EditorInfo.TYPE_CLASS_PHONE
                                            "date" -> inputType = EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_DATE
                                            "time" -> inputType = EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_TIME
                                            "datetime" -> inputType = EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_NORMAL
                                            "number" -> inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                            "numberDecimal" -> inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                            "numberPassword" -> inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
                                            "numberSigned" -> inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                            "numberDecimalSigned" -> inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL or EditorInfo.TYPE_NUMBER_FLAG_SIGNED or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                            "textEmailAddress" -> inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                                            "textPassword" -> inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                                        }
                                        if (specification.get("singleline")?.asBoolean == true) {
                                            inputType = EditorInfo.TYPE_CLASS_TEXT
                                        }
                                        if (specification.get("blockinput")?.asBoolean == true) {
                                            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_FILTER or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                        }
                                        if (specification.get("line")?.asBoolean == false) {
                                            setBackgroundResource(android.R.color.transparent)
                                        }
                                        setText(specification.get("text")?.asString ?: "", TextView.BufferType.EDITABLE)
                                    }
                                    "checkbox" -> CheckBox(activity).apply {
                                        text = specification.get("text")?.asString ?: ""
                                        isChecked = specification.get("checked")?.asBoolean ?: false
                                    }
                                    "switch" -> SwitchCompat(activity).apply {
                                        text = specification.get("text")?.asString ?: ""
                                        isChecked = specification.get("checked")?.asBoolean ?: false
                                    }
                                    "spinner" -> Spinner(activity)
                                    "slider" -> SeekBar(activity).apply {
                                        max = specification.get("max")?.asInt ?: 100
                                        progress = specification.get("progress")?.asInt ?: 0
                                    }
                                    "text" -> TextView(activity).apply {
                                        text = specification.get("text")?.asString ?: ""
                                    }
                                    "image" -> ImageView(activity).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        specification.get("image")?.asString?.let {
                                            val bytes = Base64.decode(it, Base64.DEFAULT)
                                            setImageBitmap(BitmapCache.decode(bytes))
                                        }
                                    }
                                    else -> throw IllegalArgumentException("unsupported batch view type")
                                }
                                val id = Util.generateViewID(rand, activity)
                                view.id = id
                                if (view is TextView) {
                                    specification.get("textSize")?.asFloat?.let {
                                        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, it)
                                    }
                                    specification.get("textColor")?.asInt?.let { view.setTextColor(it) }
                                    view.gravity = when (specification.get("gravity")?.asString) {
                                        "start_top" -> Gravity.START or Gravity.TOP
                                        "start_center_vertical" -> Gravity.START or Gravity.CENTER_VERTICAL
                                        "start_bottom" -> Gravity.START or Gravity.BOTTOM
                                        "center_top" -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
                                        "center" -> Gravity.CENTER
                                        "center_bottom" -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                                        "end_top" -> Gravity.END or Gravity.TOP
                                        "end_center_vertical" -> Gravity.END or Gravity.CENTER_VERTICAL
                                        "end_bottom" -> Gravity.END or Gravity.BOTTOM
                                        "center_vertical" -> Gravity.CENTER_VERTICAL
                                        else -> view.gravity
                                    }
                                }
                                if (specification.get("clickable")?.asBoolean == true) {
                                    Util.setClickListener(view, aid, true, eventQueue)
                                }
                                if (view is Spinner) {
                                    val options = specification.get("options")?.asJsonArray
                                        ?.map { it.asString } ?: emptyList()
                                    view.adapter = ArrayAdapter(activity, R.layout.spinner_text, options)
                                    specification.get("selected")?.asInt?.let { view.setSelection(it) }
                                    Util.setSpinnerListener(view, aid, eventQueue)
                                }
                                if (view is SeekBar) {
                                    view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int,
                                                                       fromUser: Boolean) {
                                            if (fromUser) {
                                                eventQueue.offer(ConnectionHandler.Event("progress",
                                                    ConnectionHandler.gson.toJsonTree(mapOf(
                                                        "aid" to aid, "id" to id, "progress" to progress))))
                                            }
                                        }

                                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                                    })
                                }
                                specification.get("background")?.asInt?.let(view::setBackgroundColor)
                                specification.get("visibility")?.asInt?.let {
                                    view.visibility = when (it) {
                                        0 -> android.view.View.GONE
                                        1 -> android.view.View.INVISIBLE
                                        else -> android.view.View.VISIBLE
                                    }
                                }
                                specification.get("enabled")?.asBoolean?.let { view.isEnabled = it }
                                val width = dimension(specification, "width")
                                val height = dimension(specification, "height")
                                val weight = specification.get("weight")?.asFloat ?: 0f
                                val left = margin(specification, "left")
                                val top = margin(specification, "top")
                                val params = if (parentView is LinearLayout) {
                                    LinearLayout.LayoutParams(width, height, weight)
                                } else {
                                    FrameLayout.LayoutParams(width, height)
                                }
                                params.setMargins(
                                    left + (specification.get("x")?.asFloat?.times(density)?.roundToInt() ?: 0),
                                    top + (specification.get("y")?.asFloat?.times(density)?.roundToInt() ?: 0),
                                    margin(specification, "right"),
                                    margin(specification, "bottom"))
                                val gravity = when (specification.get("align")?.asString) {
                                    "center" -> Gravity.CENTER
                                    "start" -> Gravity.START or Gravity.CENTER_VERTICAL
                                    "end" -> Gravity.END or Gravity.CENTER_VERTICAL
                                    "top" -> Gravity.TOP
                                    "bottom" -> Gravity.BOTTOM
                                    "center_vertical" -> Gravity.CENTER_VERTICAL
                                    "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                                    "fill" -> Gravity.FILL
                                    else -> null
                                }
                                if (gravity != null) {
                                    (params as? LinearLayout.LayoutParams)?.gravity = gravity
                                    (params as? FrameLayout.LayoutParams)?.gravity = gravity
                                }
                                parentView.addView(view, params)
                                ids.add(id)
                            }
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(ids))
                        return
                    }
                    if (m.method == "createTextView") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = TextView(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            v.setTextIsSelectable(m.params?.get("selectableText")?.asBoolean ?: false)
                            if (m.params?.get("clickableLinks")?.asBoolean == true) {
                                v.movementMethod = LinkMovementMethod.getInstance()
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createEditText") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = EditText(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            when (m.params?.get("type")?.asString) {
                                "text" -> v.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                                "textMultiLine" -> v.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                                "phone" -> v.inputType = EditorInfo.TYPE_CLASS_PHONE
                                "date" -> v.inputType = EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_DATE
                                "time" -> v.inputType = EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_TIME
                                "datetime" -> v.inputType = EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_NORMAL
                                "number" -> v.inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                "numberDecimal" -> v.inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                "numberPassword" -> v.inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
                                "numberSigned" -> v.inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                "numberDecimalSigned" -> v.inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL or EditorInfo.TYPE_NUMBER_FLAG_SIGNED or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL
                                "textEmailAddress" -> v.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                                "textPassword" -> v.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                            }
                            if (m.params?.get("singleline")?.asBoolean == true) {
                                //println("singleline")
                                v.inputType = EditorInfo.TYPE_CLASS_TEXT
                            }
                            if (m.params?.get("line")?.asBoolean == false) {
                                v.setBackgroundResource(android.R.color.transparent)
                            }
                            if (m.params?.get("blockinput")?.asBoolean == true) {
                                v.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_FILTER or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            }
                            v.setText(m.params?.get("text")?.asString, TextView.BufferType.EDITABLE)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createLinearLayout") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = LinearLayout(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.orientation = if (m.params?.get("vertical")?.asBoolean != false) {
                                LinearLayout.VERTICAL
                            } else {
                                LinearLayout.HORIZONTAL
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createButton") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = Button(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.isAllCaps = m.params?.get("allcaps")?.asBoolean ?: false
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createImageView") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val keyboard = m.params?.get("keyboard")?.asBoolean ?: false
                            val v = if (keyboard) KeyboardImageView(it) else ImageView(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            if (v is KeyboardImageView) {
                                v.isFocusable = true
                                v.isFocusableInTouchMode = true
                                Util.setKeyListenerJSON(v, aid, eventQueue)
                                v.textListener = { text -> Util.sendKeyTextJSON(v, aid, text, eventQueue) }
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSpace") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = Space(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createFrameLayout") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = FrameLayout(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createCheckbox") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = CheckBox(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            v.freezesText = true
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createNestedScrollView") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = if (m.params?.get("snapping")?.asBoolean == true) SnappingNestedScrollView(it) else NestedScrollView(it)
                            if (m.params?.get("fillviewport")?.asBoolean == true) {
                                v.isFillViewport = true
                            }
                            if (m.params?.get("nobar")?.asBoolean == true) {
                                v.scrollBarSize = 0
                            }
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createRecyclerView") {
                        val initialItems = RecyclerListAdapter.fromJson(app, m.params?.get("items")?.asJsonArray)
                        val sendClick = m.params?.get("click")?.asBoolean ?: true
                        val sendLongClick = m.params?.get("longclick")?.asBoolean ?: true
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = RecyclerView(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.layoutManager = LinearLayoutManager(it)
                            v.setHasFixedSize(true)
                            v.adapter = RecyclerListAdapter(it, initialItems, { position ->
                                if (sendClick) {
                                    val key = (v.adapter as RecyclerListAdapter).keyAt(position)
                                    eventQueue.offer(ConnectionHandler.Event("click", ConnectionHandler.gson.toJsonTree(
                                        mapOf("aid" to aid, "id" to id, "position" to position, "key" to key))))
                                }
                            }, { position ->
                                if (sendLongClick) {
                                    val key = (v.adapter as RecyclerListAdapter).keyAt(position)
                                    eventQueue.offer(ConnectionHandler.Event("longClick", ConnectionHandler.gson.toJsonTree(
                                        mapOf("aid" to aid, "id" to id, "position" to position, "key" to key))))
                                }
                            }, it.theme?.textColor)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createHorizontalScrollView") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = if (m.params?.get("snapping")?.asBoolean == true) SnappingHorizontalScrollView(it) else HorizontalScrollView(it)
                            if (m.params?.get("fillviewport")?.asBoolean == true) {
                                v.isFillViewport = true
                            }
                            if (m.params?.get("nobar")?.asBoolean == true) {
                                v.scrollBarSize = 0
                            }
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createRadioGroup") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = RadioGroup(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.setCheckedListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createRadioButton") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = RadioButton(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSpinner") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = Spinner(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.setSpinnerListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createToggleButton") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = ToggleButton(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.freezesText = true
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSwitch") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = SwitchCompat(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createProgressBar") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = ProgressBar(it, null, android.R.attr.progressBarStyleHorizontal)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSwipeRefreshLayout") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = SwipeRefreshLayout(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.setRefreshListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createTabLayout") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = TabLayout(it)
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.setTabSelectedListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createGridLayout") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = GridLayout(app)
                            val rows: Int = m.params!!["rows"]!!.asInt
                            val cols: Int = m.params!!["cols"]!!.asInt
                            v.rowCount = rows
                            v.columnCount = cols
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createWebView") {
                        var id = -1
                        V0Shared.runOnUIThreadActivityStartedBlocking(a) {
                            val v = WebView(app)
                            val settings = v.settings
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.domStorageEnabled = true
                            settings.setGeolocationEnabled(false)
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.saveFormData = false
                            settings.savePassword = false
                            
                            val l = object: WebEventListener {
                                override fun onNavigation(url: String) {
                                    val map = HashMap<String, Any?>()
                                    map["url"] = url
                                    eventQueue.offer(ConnectionHandler.Event("webviewNavigation", ConnectionHandler.gson.toJsonTree(map)))
                                }

                                override fun onHTTPError(url: String, code: Int) {
                                    val map = HashMap<String, Any?>()
                                    map["url"] = url
                                    map["code"] = code
                                    eventQueue.offer(ConnectionHandler.Event("webviewHTTPError", ConnectionHandler.gson.toJsonTree(map)))
                                }

                                override fun onReceivedError(url: String, description: String, code: Int) {
                                    val map = HashMap<String, Any?>()
                                    map["url"] = url
                                    eventQueue.offer(ConnectionHandler.Event("webviewError", ConnectionHandler.gson.toJsonTree(map)))
                                }

                                override fun onRenderProcessGone(v: WebView) {
                                    val map = HashMap<String, Any?>()
                                    eventQueue.offer(ConnectionHandler.Event("webviewDestroyed", ConnectionHandler.gson.toJsonTree(map)))
                                }

                                override fun onProgressChanged(progress: Int) {
                                    val map = HashMap<String, Any?>()
                                    map["progress"] = progress
                                    eventQueue.offer(ConnectionHandler.Event("webviewProgress", ConnectionHandler.gson.toJsonTree(map)))
                                }

                                override fun onConsoleMessage(m: ConsoleMessage) {
                                    val map = HashMap<String, Any?>()
                                    map["msg"] = m.message()
                                    eventQueue.offer(ConnectionHandler.Event("webviewConsoleMessage", ConnectionHandler.gson.toJsonTree(map)))
                                }
                            }
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
                            }
                            v.webViewClient = GUIWebViewClient(l)
                            v.webChromeClient = GUIWebChromeClient(l)
                            
                            id = Util.generateViewID(rand, it)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            Util.setViewActivity(it, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                }
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                // ---------------------------------------------------------------------------------------------
                // ------------------------ OVERLAY METHODS ----------------------------------------------------
                // ---------------------------------------------------------------------------------------------
                
                
                
                
                
                
                
                
                
                
                
                
                if (o != null) {
                    if (m.method == "createTextView") {
                        val v = TextView(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            v.setTextIsSelectable(m.params?.get("selectableText")?.asBoolean ?: false)
                            if (m.params?.get("clickableLinks")?.asBoolean == true) {
                                v.movementMethod = LinkMovementMethod.getInstance()
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createEditText") {
                        val v = EditText(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            if (m.params?.get("line")?.asBoolean == false) {
                                v.setBackgroundResource(android.R.color.transparent)
                            }
                            v.setText(m.params?.get("text")?.asString, TextView.BufferType.EDITABLE)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createLinearLayout") {
                        val v = LinearLayout(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            v.orientation = if (m.params?.get("vertical")?.asBoolean != false) {
                                LinearLayout.VERTICAL
                            } else {
                                LinearLayout.HORIZONTAL
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createButton") {
                        val v = Button(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            v.isAllCaps = m.params?.get("allcaps")?.asBoolean ?: false
                            v.freezesText = true
                            v.text = m.params?.get("text")?.asString
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createImageView") {
                        val keyboard = m.params?.get("keyboard")?.asBoolean ?: false
                        val v = if (keyboard) KeyboardImageView(app) else ImageView(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            if (v is KeyboardImageView) {
                                v.isFocusable = true
                                v.isFocusableInTouchMode = true
                                Util.setKeyListenerJSON(v, aid, eventQueue)
                                v.textListener = { text -> Util.sendKeyTextJSON(v, aid, text, eventQueue) }
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSpace") {
                        val v = Space(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createFrameLayout") {
                        val v = FrameLayout(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createCheckbox") {
                        val v = CheckBox(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            v.freezesText = true
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createNestedScrollView") {
                        val v = NestedScrollView(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            if (m.params?.get("fillviewport")?.asBoolean == true) {
                                v.isFillViewport = true
                            }
                            if (m.params?.get("nobar")?.asBoolean == true) {
                                v.scrollBarSize = 0
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createHorizontalScrollView") {
                        val v = HorizontalScrollView(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            if (m.params?.get("fillviewport")?.asBoolean == true) {
                                v.isFillViewport = true
                            }
                            if (m.params?.get("nobar")?.asBoolean == true) {
                                v.scrollBarSize = 0
                            }
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createRadioGroup") {
                        val v = RadioGroup(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            Util.setCheckedListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createRadioButton") {
                        val v = RadioButton(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSpinner") {
                        val v = Spinner(app)
                        val id = Util.generateViewIDRaw(rand, o.usedIds)
                        Util.runOnUIThreadBlocking {
                            v.id = id
                            Util.setSpinnerListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createToggleButton") {
                        var id = -1
                        Util.runOnUIThreadBlocking {
                            val v = ToggleButton(app)
                            id = Util.generateViewIDRaw(rand, o.usedIds)
                            v.id = id
                            v.freezesText = true
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSwitch") {
                        var id = -1
                        Util.runOnUIThreadBlocking {
                            val v = SwitchCompat(app)
                            id = Util.generateViewIDRaw(rand, o.usedIds)
                            v.id = id
                            v.text = m.params?.get("text")?.asString
                            v.freezesText = true
                            v.isChecked = m.params?.get("checked")?.asBoolean ?: false
                            Util.setClickListener(v, aid, true, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createProgressBar") {
                        var id = -1
                        Util.runOnUIThreadBlocking {
                            val v = ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal)
                            id = Util.generateViewIDRaw(rand, o.usedIds)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createSwipeRefreshLayout") {
                        var id = -1
                        Util.runOnUIThreadBlocking {
                            val v = SwipeRefreshLayout(app)
                            id = Util.generateViewIDRaw(rand, o.usedIds)
                            v.id = id
                            Util.setRefreshListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createTabLayout") {
                        var id = -1
                        Util.runOnUIThreadBlocking {
                            val v = TabLayout(app)
                            id = Util.generateViewIDRaw(rand, o.usedIds)
                            v.id = id
                            Util.setTabSelectedListener(v, aid, eventQueue)
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                    if (m.method == "createGridLayout") {
                        var id = -1
                        Util.runOnUIThreadBlocking {
                            val v = GridLayout(app)
                            val rows: Int = m.params!!["rows"]!!.asInt
                            val cols: Int = m.params!!["cols"]!!.asInt
                            v.rowCount = rows
                            v.columnCount = cols
                            id = Util.generateViewIDRaw(rand, o.usedIds)
                            v.id = id
                            Util.createViewOptionalsJSON(v, m.params)
                            V0Shared.setViewOverlay(o, v, parent)
                        }
                        Util.sendMessage(out, ConnectionHandler.gson.toJson(id))
                        return
                    }
                }
            }
        }
    }
}
