package com.quark.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import org.json.JSONArray
import kotlin.math.*

/** Camera at the centre of an upper celestial hemisphere, not a translated star map. */
internal class HemisphereSkyView(
    private val act: MainActivity,
    private val onBook: (Books.Meta, Float, Float) -> Unit,
    private val onFocus: (Boolean) -> Unit
) : View(act) {
    private data class Pattern(val points: List<PointF>, val lines: List<Pair<Int, Int>>)
    private val catalogue = act.assets.open("constellations.json").bufferedReader().use { stream ->
        val array = JSONArray(stream.readText())
        List(array.length()) { i ->
            val item = array.getJSONObject(i); val points = item.getJSONArray("points"); val lines = item.getJSONArray("lines")
            Pattern(List(points.length()) { n -> PointF(points.getJSONArray(n).getDouble(0).toFloat(), points.getJSONArray(n).getDouble(1).toFloat()) },
                List(lines.length()) { n -> lines.getJSONArray(n).getInt(0) to lines.getJSONArray(n).getInt(1) })
        }.also { require(it.size == 100 && it.all { p -> p.points.size == 7 }) }
    }
    private val field = ConstellationField(StarShelfCapacity.count(act.books.size))
    private val offsets = List(field.count) { index ->
        val angle = SkyGeometry.radians(((index * 17) % 42 - 21).toDouble())
        catalogue[index % catalogue.size].points.map { p ->
            PointF((p.x * cos(angle) - p.y * sin(angle)).toFloat(),
                (p.x * sin(angle) + p.y * cos(angle)).toFloat())
        }
    }
    private val dust = List(480) { i ->
        SkyGeometry.direction(SkyGeometry.wrap(i * 137.507764), asin((i + 0.5) / 480.0) * 180 / PI)
    }
    private var yaw = 0.0
    private var pitch = 50.0
    private var savedYaw = 0.0
    private var savedPitch = 50.0
    private var zoom = 1f
    private var focused = -1
    private var travel: ValueAnimator? = null
    private var lastFrame = 0L
    private var time = 0f
    private var animateMotion = motionEnabled(context)
    private var motionCheckAt = 0L
    private var dragging = false
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x888BAFFF.toInt(), 0x188BAFFF, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }
    private val centerY get() = height * 0.5f + 30f.dp(context)
    private val focal get() = width * 0.5f / tan(Math.toRadians(37.5)).toFloat() * zoom
    private fun project(vector: SkyGeometry.Vector, camera: SkyGeometry.Camera = SkyGeometry.Camera(yaw, pitch)): PointF? {
        val p = camera.project(vector) ?: return null
        return PointF(width * 0.5f + (p.x * focal).toFloat(), centerY + (p.y * focal).toFloat())
    }
    private fun starVector(index: Int, slot: Int): SkyGeometry.Vector {
        val body = field.bodies[index]; val offset = offsets[index][slot]
        return SkyGeometry.offset(body.azimuth, body.elevation, offset.x * body.radius * 0.72, -offset.y * body.radius * 0.72)
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != VISIBLE || act.books.size < 7) return
            val now = SystemClock.uptimeMillis()
            if (now - motionCheckAt > 1000) { animateMotion = motionEnabled(context); motionCheckAt = now }
            if (!animateMotion) { lastFrame = 0; postDelayed(this, 250); return }
            if (lastFrame > 0) {
                val dt = ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
                time += dt
                field.advance(dt) { StarShelfCapacity.full(it, act.books.size) }
                if (focused >= 0 && travel?.isRunning != true && !dragging) {
                    yaw = field.bodies[focused].azimuth; pitch = field.bodies[focused].elevation
                }
            }
            lastFrame = now; invalidate(); postDelayed(this, 33)
        }
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean { travel?.cancel(); travel = null; dragging = false; return true }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (focused >= 0) return true
            dragging = true
            yaw = SkyGeometry.wrap(yaw + dx / focal * 180.0 / PI / cos(SkyGeometry.radians(pitch)).coerceAtLeast(0.35))
            pitch = (pitch - dy / focal * 180.0 / PI).coerceIn(20.0, 80.0)
            invalidate(); return true
        }
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (focused < 0) {
                val candidates = field.bodies.mapIndexedNotNull { index, body ->
                    val point = project(body.vector) ?: return@mapIndexedNotNull null
                    val distance = hypot(event.x - point.x, event.y - point.y)
                    val edge = project(SkyGeometry.offset(body.azimuth, body.elevation, body.radius, 0.0))
                    val radius = if (edge != null) hypot(edge.x - point.x, edge.y - point.y) else 0f
                    if (distance < radius + 12f.dp(context)) index to distance else null
                }
                candidates.minByOrNull { it.second }?.let { focusGroup(it.first) }
            } else findStar(event.x, event.y)?.let { (book, point) -> onBook(book, point.x, point.y) }
            return true
        }
        override fun onLongPress(event: MotionEvent) { findStar(event.x, event.y)?.let { act.showActions(it.first) } }
    })
    init {
        isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "星海，${act.books.size}本，${field.count}个星座"
    }
    private fun findStar(x: Float, y: Float): Pair<Books.Meta, PointF>? {
        if (focused < 0 || travel?.isRunning == true) return null
        return offsets[focused].indices.mapNotNull { slot ->
            val book = act.books.getOrNull(focused * 7 + slot) ?: return@mapNotNull null
            project(starVector(focused, slot))?.let { book to it }
        }.minByOrNull { hypot(it.second.x - x, it.second.y - y) }
            ?.takeIf { hypot(it.second.x - x, it.second.y - y) <= 30f.dp(context) }
    }
    fun focusBook(id: String) {
        val bookIndex = act.books.indexOfFirst { it.id == id }
        if (bookIndex < 0) return
        val group = StarShelfCapacity.group(bookIndex)
        focusGroup(group) {
            project(starVector(group, StarShelfCapacity.slot(bookIndex)))?.let { onBook(act.books[bookIndex], it.x, it.y) }
        }
    }
    private fun focusGroup(index: Int, done: () -> Unit = {}) {
        if (focused < 0) { savedYaw = yaw; savedPitch = pitch }
        focused = index; onFocus(true)
        fly((22.0 / field.bodies[index].radius).toFloat(), { field.bodies[index].azimuth to field.bodies[index].elevation }, done)
    }
    fun overview() {
        focused = -1
        fly(1f, { savedYaw to savedPitch }) { onFocus(false) }
    }
    private fun fly(targetZoom: Float, target: () -> Pair<Double, Double>, done: () -> Unit) {
        travel?.cancel()
        val startYaw = yaw; val startPitch = pitch; val startZoom = zoom
        if (!motionEnabled(context)) {
            val dest = target(); yaw = dest.first; pitch = dest.second; zoom = targetZoom
            invalidate(); done(); return
        }
        travel = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420; interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val f = it.animatedValue as Float; val dest = target()
                yaw = SkyGeometry.wrap(startYaw + SkyGeometry.shortest(startYaw, dest.first) * f)
                pitch = startPitch + (dest.second - startPitch) * f
                zoom = startZoom + (targetZoom - startZoom) * f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) done() }
            })
            start()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backdrop.shader = RadialGradient(w * 0.5f, h * 0.45f, maxOf(w, h) * 0.75f,
            intArrayOf(0xFF132541.toInt(), 0xFF080F22.toInt(), 0xFF030713.toInt()), null, Shader.TileMode.CLAMP)
    }
    private fun drawGrid(canvas: Canvas, camera: SkyGeometry.Camera) {
        line.strokeWidth = 0.65f.dp(context); line.color = 0x183F638C
        fun curve(points: List<SkyGeometry.Vector>) {
            val path = Path(); var connected = false
            points.forEach { vector ->
                val p = project(vector, camera)
                if (p == null || abs(p.x) > width * 4 || abs(p.y) > height * 4) connected = false
                else { if (connected) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y); connected = true }
            }
            canvas.drawPath(path, line)
        }
        listOf(30.0, 50.0, 70.0).forEach { elevation -> curve((0..360 step 3).map { SkyGeometry.direction(it.toDouble(), elevation) }) }
        (0 until 360 step 45).forEach { azimuth -> curve((0..90 step 3).map { SkyGeometry.direction(azimuth.toDouble(), it.toDouble()) }) }
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdrop)
        val camera = SkyGeometry.Camera(yaw, pitch)
        drawGrid(canvas, camera)
        dust.forEachIndexed { i, vector -> project(vector, camera)?.let { p ->
            star.color = if (i % 9 == 0) 0x888EA9CF.toInt() else 0x405A7097
            canvas.drawCircle(p.x, p.y, (if (i % 9 == 0) 0.9f else 0.55f).dp(context), star)
        } }
        for (index in field.bodies.indices) {
            val body = field.bodies[index]
            if (project(body.vector, camera) == null) continue
            val targetZoom = (22.0 / body.radius).toFloat()
            val fade = if (focused < 0 || focused == index) 1f else (1f - (zoom - 1f) / (targetZoom - 1f)).coerceIn(0f, 1f)
            if (fade < 0.01f) continue
            val points = offsets[index].indices.map { project(starVector(index, it), camera) }
            if (points.none { it != null && it.x in -40f..(width + 40f) && it.y in 0f..height.toFloat() }) continue
            val lit = (act.books.size - index * 7).coerceIn(0, 7)
            val complete = lit == 7
            val pulse = if (complete && animateMotion) 0.78f + sin(time * 1.35f + index * 2.3f) * 0.22f else 1f
            line.strokeWidth = (if (lit > 0) 0.85f else 0.65f).dp(context)
            catalogue[index % catalogue.size].lines.forEach { (a, b) ->
                val p = points[a]; val q = points[b]
                if (p != null && q != null) {
                    line.color = if (a < lit && b < lit) 0xAA96B9F7.toInt() else 0x485E739E
                    line.alpha = (line.alpha * fade * pulse).toInt()
                    canvas.drawLine(p.x, p.y, q.x, q.y, line)
                }
            }
            // Glow also fits inside each spherical cap, so densely packed patterns stay distinct.
            val glow = minOf(12f.dp(context), (focal * tan(SkyGeometry.radians(body.radius * 0.12))).toFloat())
            points.forEachIndexed { slot, point -> if (point != null) {
                val hasBook = slot < lit
                if (hasBook) {
                    val save = canvas.save(); canvas.translate(point.x, point.y); canvas.scale(glow, glow)
                    halo.alpha = (fade * pulse * 230).toInt()
                    canvas.drawCircle(0f, 0f, 1f, halo); canvas.restoreToCount(save)
                }
                star.color = if (hasBook) 0xFFE0ECFF.toInt() else 0x806B7C9C.toInt()
                star.alpha = (star.alpha * fade * if (hasBook) pulse else 1f).toInt()
                canvas.drawCircle(point.x, point.y, minOf(glow * 0.28f, (if (hasBook) 2.1f else 1.4f).dp(context)), star)
            } }
            if (zoom < 1.5f) {
                project(SkyGeometry.offset(body.azimuth, body.elevation, 0.0, -body.radius * 0.87), camera)?.let { p ->
                    label.textSize = minOf(10f.dp(context), glow * 1.6f); label.color = 0x908294B5.toInt()
                    canvas.drawText("$lit", p.x, p.y, label)
                }
            }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            travel?.cancel(); focused = -1; zoom = 1f; onFocus(false)
            yaw = SkyGeometry.wrap(yaw + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 50.0 else -50.0)
            invalidate(); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0; removeCallbacks(ticker); post(ticker) }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus); removeCallbacks(ticker); lastFrame = 0
        if (hasWindowFocus) { animateMotion = motionEnabled(context); post(ticker) }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); removeCallbacks(ticker); lastFrame = 0
        if (visibility == VISIBLE && isAttachedToWindow) post(ticker)
    }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); travel?.cancel(); super.onDetachedFromWindow() }
}
