package com.quark.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * 分页阅读视图（支持“从左往右 ltr”与“从右往左 rtl”两种翻页方向）：
 * - StaticLayout 行级分页 + 拖拽跟手 + 松手翻页
 * - 支持以画面最上面三行作为基准捕获与恢复 `ReadingAnchor`（在字号/行间距/段间距/背景/翻页模式切换时保持刚刚读到的位置）
 * - 支持段间距 (`paraSp`)、章节主/副标题加粗加大、正文关键词查找定位与高亮
 */
class PagedView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    class Style(
        var fontSizePx: Float,
        var lineSp: Float,
        var paraSp: Float,
        var marginXPx: Int,
        var theme: ThemeSpec,
        var typeface: Typeface? = null
    )

    var style: Style = Style(19f.dp(ctx), 2.02f, 0.9f, 24.dp(ctx), ThemeSpec.builtIn()["day"]!!)
    var handle: Books.Handle? = null
    var onPageChange: ((Int, Int) -> Unit)? = null       // page, pageCount
    var onTapCenter: (() -> Unit)? = null
    var tapSideEnabled = false
    var onSideTap: ((Boolean) -> Unit)? = null
    var onChapterBoundary: ((forward: Boolean) -> Unit)? = null

    /**
     * 翻页方向模式：
     * - "ltr": 从左往右（向右翻到下一页：点击右侧或从右向左滑进入下一页）
     * - "rtl": 从右往左（向左翻到下一页：点击左侧或从左向右滑进入下一页）
     */
    var animMode = "ltr"

    private val isRtlMode: Boolean get() = animMode == "rtl"

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null
    private var formattedChapter: ChapterTextFormatter.FormattedChapter? = null
    private var pages = mutableListOf<IntArray>()        // [startLine, endLine)
    var curPage = 0
        private set
    var curChapter = 0
        private set
    private var showMeta = false
    private var pendingRatio: Float? = null
    private var pendingAnchor: ReadingAnchor? = null
    private var pendingAtEnd: Boolean = false

    private var highlightChapter = -1
    private var highlightRange: IntRange? = null

    private var dragging = false
    private var dragOffset = 0f
    private var downX = 0f
    private val shadePaint = Paint()

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = width
            if (tapSideEnabled && e.x < w * 0.32f) {
                // 点击左侧：在“从左往右”模式下是上一页(false)，在“从右往左”模式下是下一页(true)
                onSideTap?.invoke(isRtlMode)
            } else if (tapSideEnabled && e.x > w * 0.68f) {
                // 点击右侧：在“从左往右”模式下是下一页(true)，在“从右往左”模式下是上一页(false)
                onSideTap?.invoke(!isRtlMode)
            } else {
                onTapCenter?.invoke()
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > 850 && abs(vx) > abs(vy) * 1.35f) {
                val forward = if (isRtlMode) vx > 0f else vx < 0f
                turn(forward)
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) downX = e.x
        gesture.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragOffset = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragOffset = e.x - downX
                applyDrag()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    settle()
                }
            }
        }
        return true
    }

    fun setHighlight(ci: Int, rawRange: IntRange?) {
        highlightChapter = ci
        highlightRange = rawRange
    }

    fun clearHighlight() {
        if (highlightRange != null) {
            highlightChapter = -1
            highlightRange = null
            val anchor = captureTopThreeLinesAnchor()
            setChapter(curChapter, showMeta, anchor = anchor)
        }
    }

    /**
     * 捕获当前页最上面三行文字的精确锚点 `ReadingAnchor`
     */
    fun captureTopThreeLinesAnchor(): ReadingAnchor {
        val l = layout
        val fmt = formattedChapter
        val pg = pages.getOrNull(curPage)
        if (l == null || fmt == null || pg == null || l.lineCount == 0) {
            val fallbackOffset = pendingAnchor?.primaryCharOffset ?: 0
            return ReadingAnchor(curChapter, fallbackOffset, intArrayOf(fallbackOffset))
        }
        val startLine = pg[0].coerceIn(0, l.lineCount - 1)
        val endLineExclusive = pg[1].coerceIn(startLine + 1, l.lineCount)
        // 如果第 0 页顶部是元信息/章节标题行，优先取当前页最上面三行中属于正文或紧随其后的首行偏移
        val lineCountOnPage = (endLineExclusive - startLine).coerceAtLeast(1)
        val sampleCount = minOf(3, lineCountOnPage)
        val offsets = IntArray(sampleCount) { i ->
            val lineIdx = startLine + i
            fmt.toRawOffset(l.getLineStart(lineIdx))
        }
        // 若第一行在标题区（rawOffset == 0）但第 2/3 行已经进入正文，且当前不是第 0 页，取首个非零偏移
        val primary = if (curPage == 0) {
            offsets[0]
        } else {
            offsets.firstOrNull { it > 0 } ?: offsets[0]
        }
        return ReadingAnchor(curChapter, primary, offsets, 0)
    }

    fun restoreAnchor(anchor: ReadingAnchor) {
        if (curChapter != anchor.chapterIndex || layout == null) {
            setChapter(anchor.chapterIndex, anchor.chapterIndex == 0 && showMeta, anchor = anchor)
            return
        }
        val l = layout
        val fmt = formattedChapter
        if (l == null || fmt == null || width == 0 || height == 0) {
            pendingAnchor = anchor
            return
        }
        pendingAnchor = null
        if (anchor.primaryCharOffset <= 0) {
            setPage(0)
            return
        }
        val targetFmtOffset = fmt.toFormattedOffset(anchor.primaryCharOffset)
        val lineStarts = IntArray(l.lineCount) { i -> l.getLineStart(i) }
        val lineEnds = IntArray(l.lineCount) { i -> l.getLineEnd(i) }
        val pageIdx = ChapterTextFormatter.findPageForFormattedOffset(pages, lineStarts, lineEnds, targetFmtOffset)
        setPage(pageIdx)
    }

    fun setChapter(ci: Int, withMeta: Boolean, atEnd: Boolean = false, anchor: ReadingAnchor? = null) {
        val h = handle ?: return
        if (ci !in 0 until h.chapters.size) return
        curChapter = ci
        showMeta = withMeta
        if (anchor != null) {
            pendingAnchor = anchor
            pendingRatio = null
            pendingAtEnd = false
        } else if (atEnd) {
            pendingAtEnd = true
            pendingAnchor = null
            pendingRatio = null
        }

        if (width == 0 || height == 0) {
            layout = null
            formattedChapter = null
            pages.clear()
            curPage = 0
            return
        }
        val st = style
        paint.textSize = st.fontSizePx
        paint.typeface = st.typeface ?: Typeface.DEFAULT
        paint.color = st.theme.fg
        val contentW = (width - st.marginXPx * 2).coerceAtLeast(120)

        val rawBody = h.chapterText(ci)
        val hlRange = if (ci == highlightChapter) highlightRange else null
        val formatted = ChapterTextFormatter.format(
            rawBody = rawBody,
            chapterTitle = h.chapters[ci].title,
            bookTitle = h.title,
            bookAuthor = h.author,
            bookName = h.meta.name,
            includeHeaderInText = true,
            withMeta = withMeta,
            theme = st.theme,
            fontSizePx = st.fontSizePx,
            paraSp = st.paraSp,
            highlightRawRange = hlRange
        )
        formattedChapter = formatted

        val builtLayout = StaticLayout.Builder.obtain(formatted.text, 0, formatted.text.length, paint, contentW)
            .setLineSpacing(st.fontSizePx * (st.lineSp - 1f), 1f)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        layout = builtLayout
        paginate()

        when {
            pendingAnchor != null -> {
                val a = pendingAnchor!!
                pendingAnchor = null
                restoreAnchor(a)
            }
            pendingAtEnd -> {
                pendingAtEnd = false
                curPage = (pages.size - 1).coerceAtLeast(0)
            }
            pendingRatio != null -> {
                val r = pendingRatio!!
                pendingRatio = null
                curPage = (r * (pages.size - 1).coerceAtLeast(0)).toInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            }
            else -> {
                curPage = 0
            }
        }
        onPageChange?.invoke(curPage, pages.size)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (handle == null || w <= 0 || h <= 0) return
        val savedAnchor = pendingAnchor ?: if (layout != null) captureTopThreeLinesAnchor() else null
        val savedRatio = pendingRatio
        val savedAtEnd = pendingAtEnd
        setChapter(curChapter, showMeta, atEnd = savedAtEnd, anchor = savedAnchor)
        if (savedAnchor == null && !savedAtEnd && savedRatio != null) {
            setRatio(savedRatio)
        }
    }

    fun setRatio(progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        if (layout == null || width == 0 || height == 0) {
            pendingRatio = value
        } else {
            pendingRatio = null
            setPage((value * (pages.size - 1).coerceAtLeast(0)).toInt())
        }
    }

    private fun paginate() {
        val l = layout ?: return
        // 留出顶部安全区和底部状态条空间（34dp 底部信息栏 + 顶部 10dp 呼吸边距）
        val pageH = (height - 46.dp(context)).coerceAtLeast(180)
        pages.clear()
        var start = 0
        var accH = 0f
        for (i in 0 until l.lineCount) {
            val h = (l.getLineBottom(i) - l.getLineTop(i)).toFloat()
            if (accH + h > pageH && start < i) {
                pages.add(intArrayOf(start, i))
                start = i
                accH = 0f
            }
            accH += h
        }
        if (start < l.lineCount) pages.add(intArrayOf(start, l.lineCount))
        if (pages.isEmpty()) pages.add(intArrayOf(0, l.lineCount))
    }

    override fun onDraw(c: Canvas) {
        val st = style
        val l = layout ?: return
        val mx = st.marginXPx.toFloat()
        val topPad = 8.dp(context).toFloat()
        val bottomClip = (height - 34.dp(context)).coerceAtLeast(topPad.toInt() + 100)
        val pg = pages.getOrNull(curPage) ?: return

        c.save()
        c.clipRect(0f, topPad, width.toFloat(), bottomClip.toFloat())
        c.translate(mx, topPad)
        c.translate(0f, -l.getLineTop(pg[0]).toFloat())
        if (dragging && abs(dragOffset) > 0.5f) {
            c.translate(dragOffset * 0.45f, 0f)
        }
        l.draw(c)
        c.restore()

        if (dragging && abs(dragOffset) > 0.5f) {
            val edge = if (dragOffset > 0) dragOffset else width + dragOffset
            c.save()
            c.translate(if (dragOffset > 0) edge - 46f else edge, 0f)
            if (dragOffset > 0) {
                shadePaint.shader = LinearGradient(0f, 0f, 46f, 0f, intArrayOf(0x38000000, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 46f, height.toFloat(), shadePaint)
            } else {
                shadePaint.shader = LinearGradient(46f, 0f, 0f, 0f, intArrayOf(0x38000000, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 46f, height.toFloat(), shadePaint)
            }
            c.restore()
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) downX = e.x
        return super.dispatchTouchEvent(e)
    }

    private fun applyDrag() { invalidate() }

    private fun settle() {
        val w = width
        val threshold = w * 0.18f
        if (dragOffset < -threshold) {
            // 手指向左划：在“从左往右”模式下是下一页(true)，在“从右往左”模式下是上一页(false)
            turn(!isRtlMode)
        } else if (dragOffset > threshold) {
            // 手指向右划：在“从左往右”模式下是上一页(false)，在“从右往左”模式下是下一页(true)
            turn(isRtlMode)
        } else {
            animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            dragOffset = 0f
            invalidate()
        }
    }

    fun turn(next: Boolean) {
        if (pages.isEmpty()) return
        if (next && curPage >= pages.size - 1) {
            dragOffset = 0f
            invalidate()
            onChapterBoundary?.invoke(true)
            return
        }
        if (!next && curPage <= 0) {
            dragOffset = 0f
            invalidate()
            onChapterBoundary?.invoke(false)
            return
        }
        curPage += if (next) 1 else -1
        dragOffset = 0f
        animate().cancel()
        onPageChange?.invoke(curPage, pages.size)
        invalidate()
    }

    fun pageCount(): Int = pages.size

    fun setPage(k: Int) {
        curPage = k.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        invalidate()
        onPageChange?.invoke(curPage, pages.size)
    }

    fun ratio(): Float = pendingRatio ?: if (pages.size > 1) {
        curPage.toFloat() / (pages.size - 1)
    } else if (pages.isEmpty()) {
        0f
    } else {
        0f
    }
}
