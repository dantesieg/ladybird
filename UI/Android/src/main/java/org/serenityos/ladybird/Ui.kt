/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

// The look of the browser interface: the palette, the type and the shapes from UI/Android/design.
class Ui(val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(value: Float): Int = (value * density + 0.5f).toInt()
    fun dp(value: Int): Int = dp(value.toFloat())

    fun color(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    fun sans(weight: Int): Typeface = typeface("fonts/InstrumentSans.ttf", weight)
    fun mono(weight: Int = 400): Typeface = typeface("fonts/JetBrainsMono.ttf", weight)

    private fun typeface(path: String, weight: Int): Typeface =
        typefaces.getOrPut("$path:$weight") {
            Typeface.Builder(context.assets, path).setFontVariationSettings("'wght' $weight").build()
        }

    fun rounded(@ColorRes fill: Int?, radius: Float, @ColorRes stroke: Int? = null, strokeWidth: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill?.let { color(it) } ?: 0)
            if (stroke != null)
                setStroke(dp(strokeWidth), color(stroke))
        }

    // A shape that ripples when pressed, as every control does.
    fun pressable(@ColorRes fill: Int?, radius: Float, @ColorRes stroke: Int? = null, strokeWidth: Float = 1f): RippleDrawable {
        val mask = rounded(R.color.ink, radius)
        val ripple = ColorStateList.valueOf(color(R.color.ink) and 0x00FFFFFF or 0x1F000000)
        return RippleDrawable(ripple, rounded(fill, radius, stroke, strokeWidth), mask)
    }

    fun text(value: CharSequence, sizeSp: Float, weight: Int = 400, @ColorRes color: Int = R.color.ink, mono: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (mono) mono(weight) else sans(weight)
            setTextColor(color(color))
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun icon(@DrawableRes id: Int, sizeDp: Int, @ColorRes tint: Int = R.color.ink): ImageView =
        ImageView(context).apply {
            setImageResource(id)
            imageTintList = ColorStateList.valueOf(color(tint))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    // A square or round control that holds one icon and answers to taps.
    fun iconButton(@DrawableRes id: Int, sizeDp: Int, iconDp: Int, description: String, @ColorRes fill: Int? = null, radius: Float = 999f, onClick: () -> Unit): FrameLayout =
        FrameLayout(context).apply {
            contentDescription = description
            background = pressable(fill, radius)
            isClickable = true
            setOnClickListener { onClick() }
            addView(icon(id, iconDp), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
        }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun line(@ColorRes id: Int = R.color.line): View = View(context).apply { setBackgroundColor(color(id)) }

    companion object {
        private val typefaces = HashMap<String, Typeface>()

        val tileTints = intArrayOf(R.color.tile_violet, R.color.tile_green, R.color.tile_blue, R.color.tile_sand)

        // Each site keeps one tint, so that its initial reads the same in the tab strip and on the new tab page.
        fun tileTintFor(key: String): Int = tileTints[Math.floorMod(key.hashCode(), tileTints.size)]
    }
}

fun ViewGroup.MarginLayoutParams.margins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) = apply { setMargins(left, top, right, bottom) }

fun lp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
