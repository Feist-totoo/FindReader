package com.quark.reader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/**
 * 自定义书架风格渲染容器
 * 支持 cabinet (双层宝柜) / carousel (全息聚焦书卡) / grid (扁平书墙) / gallery (3D 展馆) / cloud (云端) / galaxy (星海) 空间架构
 * 附带实时氛围粒子系统 ShelfParticleView
 */
class CustomShelfView(
    private val act: MainActivity,
    private val spec: CustomShelfSpec
) : FrameLayout(act), ShelfNavigator {

    private val ctx get() = context
    private var innerShelf: View? = null

    init {
        // 1. 设置自定义背景底图 / 渐变
        background = buildBackgroundDrawable()

        // 2. 根据 layoutMode 挂载对应架构
        val content: View = when (spec.layoutMode.lowercase()) {
            "gallery" -> GalleryShelfView(act)
            "cloud" -> CloudShelfView(act)
            "galaxy" -> GalaxyShelfView(act)
            "carousel" -> MinimalShelfView(act)
            "grid" -> CustomGridShelfView(act, spec)
            else -> CustomCabinetShelfView(act, spec) // 默认 cabinet
        }
        innerShelf = content
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 3. 挂载实时氛围粒子层（覆盖在书架上层，不拦截点击）
        if (spec.particleEffect.isNotEmpty() && spec.particleEffect != "none") {
            val particleView = ShelfParticleView(ctx, spec.particleEffect, spec.particleColor).apply {
                isClickable = false
                isFocusable = false
            }
            addView(particleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildBackgroundDrawable(): Drawable {
        if (!spec.bgImagePath.isNullOrEmpty()) {
            val file = File(spec.bgImagePath)
            if (file.exists()) {
                val bmp = try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
                if (bmp != null) {
                    return SkinBackgroundDrawable(bmp, spec.bgColor, spec.bgGradient, spec.bgScaleMode, spec.bgOverlayColor)
                }
            }
        }
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            spec.bgGradient
        )
    }

    override fun focusBook(id: String) {
        (innerShelf as? ShelfNavigator)?.focusBook(id)
    }
}

/**
 * 自定义双层书柜架构 (cabinet)
 */
class CustomCabinetShelfView(
    private val act: MainActivity,
    private val spec: CustomShelfSpec
) : ScrollView(act), ShelfNavigator {

    private val ctx get() = context
    private val contentLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(ctx), 64.dp(ctx), 16.dp(ctx), 80.dp(ctx))
    }

    init {
        isFillViewport = true
        addView(contentLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buildShelves()
    }

    private fun buildShelves() {
        contentLayout.removeAllViews()
        val books = act.books
        if (books.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "书架虚位以待\\n点击右上角 ＋ 导入图书"
                textSize = 14f
                setTextColor(spec.coverTextColor)
                gravity = Gravity.CENTER
                setPadding(0, 120.dp(ctx), 0, 0)
                if (spec.serifTitle) typeface = Typeface.SERIF
            }
            contentLayout.addView(emptyTv)
            return
        }

        val chunkSize = 3
        val rows = books.chunked(chunkSize)
        rows.forEachIndexed { rIdx, rowBooks ->
            // 书籍行容器
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setPadding(8.dp(ctx), 18.dp(ctx), 8.dp(ctx), 0)
            }

            rowBooks.forEachIndexed { bIdx, b ->
                val bookIndex = rIdx * chunkSize + bIdx
                val bookCard = createBookSpine(b, bookIndex)
                val lp = LinearLayout.LayoutParams(92.dp(ctx), 140.dp(ctx)).apply {
                    setMargins(10.dp(ctx), 0, 10.dp(ctx), 0)
                }
                rowLayout.addView(bookCard, lp)
            }
            contentLayout.addView(rowLayout)

            // 书架隔板
            val board = View(ctx).apply {
                val boardBmp = spec.boardImagePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null } else null
                }
                if (boardBmp != null) {
                    background = SkinBackgroundDrawable(boardBmp, spec.boardColor, null, "stretch", 0)
                } else {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(spec.boardHighlightColor, spec.boardColor)
                    ).apply {
                        cornerRadius = 3.dp(ctx).toFloat()
                    }
                }
                elevation = 6.dp(ctx).toFloat()
            }
            contentLayout.addView(board, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 16.dp(ctx)).apply {
                topMargin = 0
                bottomMargin = 24.dp(ctx)
            })
        }
    }

    private fun createBookSpine(b: Books.Meta, index: Int): View {
        val h = Books.hash(b.id + b.title)
        val palette = spec.spinePalette
        val c1 = palette[h % palette.size]
        val c2 = palette[(h ushr 4) % palette.size]

        val card = FrameLayout(ctx).apply {
            val bm = act.coverBitmap(b)
            if (bm != null) {
                background = GradientDrawable().apply {
                    cornerRadius = spec.coverCornerDp.dp(ctx).toFloat()
                    setStroke(1.dp(ctx), spec.coverStrokeColor)
                }
                clipToOutline = true
                addView(ImageView(ctx).apply {
                    setImageBitmap(bm)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } else {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
                    cornerRadius = spec.coverCornerDp.dp(ctx).toFloat()
                    setStroke(1.dp(ctx), spec.coverStrokeColor)
                }
                val tv = TextView(ctx).apply {
                    text = b.title
                    setTextColor(spec.coverTextColor)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    if (spec.serifTitle) typeface = Typeface.SERIF
                    setPadding(8.dp(ctx), 12.dp(ctx), 8.dp(ctx), 12.dp(ctx))
                }
                addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            elevation = 8.dp(ctx).toFloat()

            setOnClickListener { act.openBook(b) }
            setOnLongClickListener { act.showActions(b); true }
        }
        return card
    }

    override fun focusBook(id: String) {
        val index = act.books.indexOfFirst { it.id == id }
        if (index >= 0) {
            val targetY = (index / 3) * 180.dp(ctx)
            smoothScrollTo(0, targetY)
        }
    }
}

/**
 * 自定义扁平网格书架架构 (grid)
 */
class CustomGridShelfView(
    private val act: MainActivity,
    private val spec: CustomShelfSpec
) : ScrollView(act), ShelfNavigator {

    private val ctx get() = context
    private val gridLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(ctx), 70.dp(ctx), 16.dp(ctx), 80.dp(ctx))
    }

    init {
        isFillViewport = true
        addView(gridLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buildGrid()
    }

    private fun buildGrid() {
        gridLayout.removeAllViews()
        val books = act.books
        if (books.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "藏卷尚空 · 点击右上角 ＋ 导入"
                textSize = 14f
                setTextColor(spec.coverTextColor)
                gravity = Gravity.CENTER
                setPadding(0, 120.dp(ctx), 0, 0)
            }
            gridLayout.addView(emptyTv)
            return
        }

        val cols = 3
        books.chunked(cols).forEach { rowBooks ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 10.dp(ctx), 0, 10.dp(ctx))
            }

            rowBooks.forEach { b ->
                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }

                val cover = FrameLayout(ctx).apply {
                    val bm = act.coverBitmap(b)
                    if (bm != null) {
                        background = GradientDrawable().apply {
                            cornerRadius = spec.coverCornerDp.dp(ctx).toFloat()
                            setStroke(1.dp(ctx), spec.coverStrokeColor)
                        }
                        clipToOutline = true
                        addView(ImageView(ctx).apply {
                            setImageBitmap(bm)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    } else {
                        val h = Books.hash(b.id)
                        val p = spec.spinePalette
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(p[h % p.size], p[(h ushr 3) % p.size])
                        ).apply {
                            cornerRadius = spec.coverCornerDp.dp(ctx).toFloat()
                            setStroke(1.dp(ctx), spec.coverStrokeColor)
                        }
                    }
                    elevation = 6.dp(ctx).toFloat()
                }

                item.addView(cover, LinearLayout.LayoutParams(92.dp(ctx), 132.dp(ctx)))

                val titleTv = TextView(ctx).apply {
                    text = b.title
                    textSize = 11.5f
                    setTextColor(spec.coverTextColor)
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setPadding(4.dp(ctx), 6.dp(ctx), 4.dp(ctx), 0)
                }
                item.addView(titleTv, LinearLayout.LayoutParams(96.dp(ctx), LayoutParams.WRAP_CONTENT))

                item.setOnClickListener { act.openBook(b) }
                item.setOnLongClickListener { act.showActions(b); true }

                row.addView(item, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8.dp(ctx), 0, 8.dp(ctx), 0)
                })
            }
            gridLayout.addView(row)
        }
    }

    override fun focusBook(id: String) {
        val index = act.books.indexOfFirst { it.id == id }
        if (index >= 0) {
            val targetY = (index / 3) * 160.dp(ctx)
            smoothScrollTo(0, targetY)
        }
    }
}

/**
 * 实时氛围粒子特效层
 * 支持 petals (落樱) / fireflies (萤火) / snow (飘雪) / stars (星辰) / rain (细雨)
 */
class ShelfParticleView(
    context: Context,
    private val effect: String,
    private val particleColor: Int
) : View(context) {

    private val density = resources.displayMetrics.density
    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var alpha: Float,
        var rot: Float,
        var vRot: Float,
        var phase: Float
    )

    init {
        paint.color = particleColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) initParticles(w.toFloat(), h.toFloat())
    }

    private fun initParticles(w: Float, h: Float) {
        particles.clear()
        val count = when (effect.lowercase()) {
            "rain" -> 55
            "snow" -> 42
            "petals" -> 32
            "fireflies" -> 24
            "stars" -> 38
            else -> 0
        }

        repeat(count) {
            particles.add(createParticle(w, h, randomY = true))
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                updateParticles(w, h)
                invalidate()
            }
            start()
        }
    }

    private fun createParticle(w: Float, h: Float, randomY: Boolean): Particle {
        val startY = if (randomY) Random.nextFloat() * h else -20f * density
        return when (effect.lowercase()) {
            "petals" -> Particle(
                x = Random.nextFloat() * w,
                y = startY,
                vx = (Random.nextFloat() * 0.8f - 0.2f) * density,
                vy = (Random.nextFloat() * 1.2f + 1.0f) * density,
                size = (Random.nextFloat() * 6f + 7f) * density,
                alpha = Random.nextFloat() * 0.4f + 0.5f,
                rot = Random.nextFloat() * 360f,
                vRot = (Random.nextFloat() * 2f - 1f) * 1.5f,
                phase = Random.nextFloat() * 6.28f
            )
            "fireflies" -> Particle(
                x = Random.nextFloat() * w,
                y = if (randomY) Random.nextFloat() * h else h + 10f * density,
                vx = (Random.nextFloat() * 0.6f - 0.3f) * density,
                vy = -(Random.nextFloat() * 0.7f + 0.3f) * density,
                size = (Random.nextFloat() * 4f + 3f) * density,
                alpha = Random.nextFloat() * 0.6f + 0.2f,
                rot = 0f,
                vRot = 0f,
                phase = Random.nextFloat() * 6.28f
            )
            "snow" -> Particle(
                x = Random.nextFloat() * w,
                y = startY,
                vx = (Random.nextFloat() * 0.4f - 0.2f) * density,
                vy = (Random.nextFloat() * 1.0f + 0.8f) * density,
                size = (Random.nextFloat() * 3.5f + 2f) * density,
                alpha = Random.nextFloat() * 0.4f + 0.5f,
                rot = 0f,
                vRot = 0f,
                phase = Random.nextFloat() * 6.28f
            )
            "rain" -> Particle(
                x = Random.nextFloat() * w,
                y = startY,
                vx = -0.5f * density,
                vy = (Random.nextFloat() * 4f + 14f) * density,
                size = (Random.nextFloat() * 8f + 12f) * density,
                alpha = Random.nextFloat() * 0.25f + 0.35f,
                rot = 0f,
                vRot = 0f,
                phase = 0f
            )
            else -> Particle( // stars
                x = Random.nextFloat() * w,
                y = Random.nextFloat() * h,
                vx = 0f,
                vy = 0f,
                size = (Random.nextFloat() * 2.5f + 1.5f) * density,
                alpha = Random.nextFloat() * 0.6f + 0.3f,
                rot = 0f,
                vRot = 0f,
                phase = Random.nextFloat() * 6.28f
            )
        }
    }

    private fun updateParticles(w: Float, h: Float) {
        val mode = effect.lowercase()
        particles.forEach { p ->
            p.phase += 0.04f
            when (mode) {
                "petals" -> {
                    p.x += p.vx + sin(p.phase) * (0.8f * density)
                    p.y += p.vy
                    p.rot += p.vRot
                    if (p.y > h + 20f * density) {
                        p.y = -15f * density
                        p.x = Random.nextFloat() * w
                    }
                }
                "fireflies" -> {
                    p.x += p.vx + sin(p.phase) * (0.6f * density)
                    p.y += p.vy
                    p.alpha = (sin(p.phase * 1.5f) * 0.35f + 0.55f).coerceIn(0.1f, 0.95f)
                    if (p.y < -20f * density) {
                        p.y = h + 15f * density
                        p.x = Random.nextFloat() * w
                    }
                }
                "snow" -> {
                    p.x += p.vx + sin(p.phase) * (0.4f * density)
                    p.y += p.vy
                    if (p.y > h + 15f * density) {
                        p.y = -10f * density
                        p.x = Random.nextFloat() * w
                    }
                }
                "rain" -> {
                    p.x += p.vx
                    p.y += p.vy
                    if (p.y > h + 25f * density) {
                        p.y = -15f * density
                        p.x = Random.nextFloat() * w
                    }
                }
                "stars" -> {
                    p.alpha = (sin(p.phase) * 0.35f + 0.55f).coerceIn(0.1f, 0.95f)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mode = effect.lowercase()
        particles.forEach { p ->
            val baseA = (Color.alpha(particleColor) / 255f)
            paint.alpha = (p.alpha * baseA * 255).toInt().coerceIn(0, 255)

            when (mode) {
                "petals" -> {
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.rot)
                    val r = RectF(-p.size / 2, -p.size / 3, p.size / 2, p.size / 3)
                    canvas.drawOval(r, paint)
                    canvas.restore()
                }
                "rain" -> {
                    paint.strokeWidth = 1.2f * density
                    canvas.drawLine(p.x, p.y, p.x + p.vx * 2f, p.y + p.size, paint)
                }
                "fireflies" -> {
                    // 柔和萤火光圈
                    paint.alpha = (p.alpha * 0.25f * 255).toInt()
                    canvas.drawCircle(p.x, p.y, p.size * 2.2f, paint)
                    paint.alpha = (p.alpha * 255).toInt()
                    canvas.drawCircle(p.x, p.y, p.size * 0.9f, paint)
                }
                else -> { // snow or stars
                    canvas.drawCircle(p.x, p.y, p.size, paint)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
