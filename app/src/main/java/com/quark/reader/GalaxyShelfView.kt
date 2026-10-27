package com.quark.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.*
import org.json.JSONArray
import kotlin.math.*

class GalaxyShelfView(private val act: MainActivity) : FrameLayout(act), ShelfNavigator {
    private val sky: HemisphereSkyView
    private val back = TextView(context)
    private var card: View? = null

    init {
        setBackgroundColor(0xFF030713.toInt())
        sky = HemisphereSkyView(act, { book, x, y -> showBook(book, x, y) }, { focused ->
            back.visibility = if (focused) VISIBLE else GONE
        })
        addView(sky, LayoutParams(-1, -1))
        back.apply {
            text = "‹"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(0xFFE0E9FF.toInt())
            contentDescription = "返回星图"
            background = GradientDrawable().apply { cornerRadius = 24f.dp(context); setColor(0xAA182441.toInt()) }
            visibility = GONE
            setOnClickListener {
                if (card != null) closeCard() else sky.overview()
            }
        }
        addView(back, LayoutParams(48.dp(context), 48.dp(context), Gravity.TOP or Gravity.START).apply {
            topMargin = 76.dp(context); leftMargin = 16.dp(context)
        })
    }

    private fun closeCard() { card?.let { it.animate().cancel(); removeView(it) }; card = null }

    override fun focusBook(id: String) { closeCard(); sky.focusBook(id) }

    private fun showBook(book: Books.Meta, starX: Float, starY: Float) {
        closeCard()
        val cover = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(context), 20.dp(context), 20.dp(context), 16.dp(context))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF2213257.toInt(), 0xF20A1224.toInt())).apply {
                cornerRadius = 18f.dp(context); setStroke(1.dp(context), 0x668BADEC)
            }
            elevation = 24f.dp(context)
            contentDescription = book.title
            isClickable = true
            setOnLongClickListener { act.showActions(book); true }
        }
        val bitmap = act.coverBitmap(book)
        if (bitmap != null) cover.addView(ImageView(context).apply {
            setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        else cover.addView(TextView(context).apply {
            val hash = Books.hash(book.id)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Books.spineColor(hash), Books.spineColor(hash ushr 5))).apply { cornerRadius = 6f.dp(context) }
            text = book.title; gravity = Gravity.CENTER; textSize = 21f; setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE); setPadding(16.dp(context), 16.dp(context), 16.dp(context), 16.dp(context))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (bitmap != null) cover.addView(TextView(context).apply {
            text = book.title; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp(context) })
        cover.addView(TextView(context).apply {
            text = "阅读"; contentDescription = "阅读 ${book.title}"
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFFE7EEFF.toInt())
            background = GradientDrawable().apply { cornerRadius = 22f.dp(context); setColor(0xFF304C80.toInt()) }
            setOnClickListener { act.openBook(book) }
        }, LinearLayout.LayoutParams(-1, 48.dp(context)).apply { topMargin = 16.dp(context) })
        card = cover
        addView(cover, LayoutParams(minOf(250.dp(context), width - 64.dp(context)), minOf(370.dp(context), height - 180.dp(context)), Gravity.CENTER))
        back.bringToFront()
        if (motionEnabled(context)) {
            cover.translationX = (starX - width / 2f) * 0.65f
            cover.translationY = (starY - height / 2f) * 0.65f
            cover.scaleX = 0.92f; cover.scaleY = 0.92f; cover.alpha = 0f
            cover.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        cover.announceForAccessibility(book.title)
    }

    override fun onDetachedFromWindow() { closeCard(); super.onDetachedFromWindow() }
}

