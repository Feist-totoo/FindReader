package com.quark.reader

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import kotlin.math.*

class CloudShelfView(private val act: MainActivity) : FrameLayout(act), ShelfNavigator {
    private val ctx get() = context
    private var page = 0
    private val pageCount get() = CloudShelfPages.count(act.books.size)
    private val cloudLayer = FrameLayout(ctx)
    private val sky = SkyView(ctx)
    private val count = TextView(ctx)
    private val nodes = mutableListOf<Node>()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var focusedId: String? = null
    private var skyAnimator: ValueAnimator? = null
    private var animateMotion = motionEnabled(context)
    private var motionCheckAt = 0L
    private val colors = arrayOf(
        intArrayOf(0xFF9BCFF5.toInt(), 0xFFE9F5FF.toInt()), // First page is daylight, including books 0..6.
        intArrayOf(0xFF66426E.toInt(), 0xFFF5A480.toInt()),
        intArrayOf(0xFF071022.toInt(), 0xFF223551.toInt()),
        intArrayOf(0xFFEABDC1.toInt(), 0xFFF7E8CD.toInt())
    )
    private data class Node(val view: FrameLayout, val book: Books.Meta, var x: Float = 0f, var y: Float = 0f)
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != VISIBLE) return
            val now = SystemClock.uptimeMillis()
            if (now - motionCheckAt > 1000) { animateMotion = motionEnabled(ctx); motionCheckAt = now }
            if (animateMotion && focusedId == null) {
                val t = SystemClock.uptimeMillis() / 1000.0
                nodes.forEachIndexed { i, node ->
                    node.view.translationX = node.x + sin(t * 0.21 + i * 2.1).toFloat() * 8f.dp(ctx)
                    node.view.translationY = node.y + sin(t * 0.34 + i * 1.7).toFloat() * 5f.dp(ctx)
                }
            }
            postDelayed(this, if (animateMotion) 33 else 250)
        }
    }

    init {
        addView(sky, LayoutParams(-1, -1))
        addView(cloudLayer, LayoutParams(-1, -1))
        count.apply { textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xAA35526B.toInt()) }
        addView(count, LayoutParams(-2, 44.dp(ctx), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        sky.setColors(colors[0][0], colors[0][1], 0)
        renderPage()
    }

    private fun renderPage() {
        focusedId = null
        nodes.clear(); cloudLayer.removeAllViews()
        val list = act.books.drop(page * 7).take(7)
        count.text = "${page + 1} / $pageCount"
        count.setTextColor(if (CloudShelfPages.phase(page) in 1..2) 0xBBFFFFFF.toInt() else 0xAA35526B.toInt())
        list.forEach { book ->
            val cloud = FrameLayout(ctx).apply {
                background = CloudShape(CloudShelfPages.phase(page))
                clipChildren = false
                tag = book.id
                contentDescription = book.title
                setOnClickListener { act.openBook(book) }
                setOnLongClickListener { act.showActions(book); true }
            }
            val cover = FrameLayout(ctx).apply {
                val hash = Books.hash(book.id)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Books.spineColor(hash), Books.spineColor(hash ushr 5))).apply { cornerRadius = 4f.dp(ctx) }
                elevation = 5f.dp(ctx)
            }
            val bitmap = act.coverBitmap(book)
            if (bitmap != null) cover.addView(ImageView(ctx).apply {
                setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LayoutParams(-1, -1))
            else cover.addView(TextView(ctx).apply {
                text = book.title; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setPadding(4.dp(ctx), 6.dp(ctx), 4.dp(ctx), 6.dp(ctx)); maxLines = 4
            }, LayoutParams(-1, -1))
            cloud.addView(cover, LayoutParams(58.dp(ctx), 82.dp(ctx), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            cloudLayer.addView(cloud, LayoutParams(132.dp(ctx), 112.dp(ctx)))
            nodes.add(Node(cloud, book))
        }
        post { placeClouds() }
    }

    private fun placeClouds() {
        if (width == 0 || height == 0) return
        val rows = maxOf(1, (nodes.size + 1) / 2)
        val top = 100f.dp(ctx)
        val available = (height - top - 68f.dp(ctx)).coerceAtLeast(112f.dp(ctx))
        val cell = available / rows
        val scale = minOf(1f, cell / 124f.dp(ctx), width / 320f.dp(ctx))
        nodes.forEachIndexed { i, node ->
            val solo = i == nodes.lastIndex && nodes.size % 2 == 1
            val centerX = width * if (solo) 0.5f else if (i % 2 == 0) 0.26f else 0.74f
            node.x = centerX - 66f.dp(ctx)
            node.y = top + cell * (i / 2 + 0.5f) - 56f.dp(ctx)
            if (focusedId == null) {
                node.view.translationX = node.x; node.view.translationY = node.y
                node.view.scaleX = scale; node.view.scaleY = scale
            }
        }
    }

    private fun showPage(target: Int, done: () -> Unit = {}) {
        val next = target.coerceIn(0, pageCount - 1)
        cloudLayer.animate().cancel(); skyAnimator?.cancel()
        if (next == page || !motionEnabled(ctx)) {
            page = next; renderPage(); sky.setColors(colors[CloudShelfPages.phase(page)][0], colors[CloudShelfPages.phase(page)][1], CloudShelfPages.phase(page))
            cloudLayer.alpha = 1f; cloudLayer.translationX = 0f
            post { done() }; return
        }
        val source = intArrayOf(sky.topColor, sky.bottomColor)
        val dest = colors[CloudShelfPages.phase(next)]
        val evaluator = ArgbEvaluator()
        skyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 390
            addUpdateListener {
                val f = it.animatedValue as Float
                sky.setColors(evaluator.evaluate(f, source[0], dest[0]) as Int,
                    evaluator.evaluate(f, source[1], dest[1]) as Int, CloudShelfPages.phase(next))
            }
            start()
        }
        val direction = if (target >= page) 1 else -1
        cloudLayer.animate().translationX(-direction * width * 0.18f).alpha(0f).setDuration(150).withEndAction {
            page = next; renderPage()
            cloudLayer.translationX = direction * width * 0.18f
            cloudLayer.animate().translationX(0f).alpha(1f).setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.8f)).withEndAction { done() }.start()
        }.start()
    }

    override fun focusBook(id: String) {
        val index = act.books.indexOfFirst { it.id == id }
        if (index < 0) return
        showPage(index / 7) {
            focusedId = id
            nodes.forEach { node ->
                node.view.animate().cancel()
                val selected = node.book.id == id
                if (selected) node.view.bringToFront()
                node.view.animate()
                    .translationX(if (selected) (width - node.view.width) * 0.5f else node.x)
                    .translationY(if (selected) (height - node.view.height) * 0.5f else node.y)
                    .scaleX(if (selected) 1.5f else 0.85f).scaleY(if (selected) 1.5f else 0.85f)
                    .alpha(if (selected) 1f else 0.18f).setDuration(if (motionEnabled(ctx)) 280 else 0)
                    .setInterpolator(DecelerateInterpolator(1.8f)).start()
            }
            announceForAccessibility(act.books[index].title)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); post { placeClouds() } }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; dragging = false }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.x - downX) > ViewConfiguration.get(ctx).scaledTouchSlop && abs(event.x - downX) > abs(event.y - downY)) {
            dragging = true; return true
        }
        return super.onInterceptTouchEvent(event)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (abs(dx) > 45f.dp(ctx) && abs(dx) > abs(event.y - downY)) {
                    val target = page + if (dx < 0) 1 else -1
                    if (target in 0 until pageCount) showPage(target)
                }
                else if (focusedId != null && !dragging) { renderPage() }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(ticker); post(ticker) }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        removeCallbacks(ticker)
        if (hasWindowFocus) { animateMotion = motionEnabled(ctx); post(ticker) }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ticker)
        if (visibility == VISIBLE && isAttachedToWindow) post(ticker)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(ticker); skyAnimator?.cancel(); cloudLayer.animate().cancel()
        nodes.forEach { it.view.animate().cancel() }
        super.onDetachedFromWindow()
    }

    private class SkyView(ctx: Context) : View(ctx) {
        var topColor = 0; private set
        var bottomColor = 0; private set
        private var era = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setColors(top: Int, bottom: Int, phase: Int) {
            topColor = top; bottomColor = bottom; era = phase
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val x = width * 0.78f; val y = height * 0.17f
            val radius = 22f.dp(context)
            paint.shader = RadialGradient(x, y, radius * 4, if (era == 2) 0x228CB5EB else 0x44FFF8DC, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius * 4, paint)
            paint.shader = null; paint.color = if (era == 2) 0xBBDCE7FF.toInt() else 0xAAFFF8DE.toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    private class CloudShape(phase: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (phase) { 1 -> 0xFFF9DFD3.toInt(); 2 -> 0xFF435973.toInt(); 3 -> 0xFFFFF0E5.toInt(); else -> Color.WHITE }
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            canvas.drawOval(w * .06f, h * .62f, w * .94f, h * .96f, paint)
            canvas.drawCircle(w * .30f, h * .69f, w * .22f, paint)
            canvas.drawCircle(w * .55f, h * .61f, w * .26f, paint)
            canvas.drawCircle(w * .76f, h * .72f, w * .17f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
