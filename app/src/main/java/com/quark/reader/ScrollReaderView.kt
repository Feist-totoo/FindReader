package com.quark.reader

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * 连续滚动阅读视图：
 * - 章节无缝衔接，动态前后增删章节块
 * - 支持以画面最上面三行作为基准捕获与恢复 `ReadingAnchor`（在字号/行间距/段间距/背景/翻页模式切换时保持顶部三行内容不变）
 * - 支持段间距 (`paraSp`)、章节主/副标题加粗加大、按章节精确跳转、正文关键词查找定位与高亮
 */
class ScrollReaderView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
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
    var onVisibleChapter: ((Int) -> Unit)? = null
    var onScrollRatio: ((Int, Float) -> Unit)? = null
    var onTapCenter: (() -> Unit)? = null
    var tapSideEnabled = false
    var onSideTap: ((Boolean) -> Unit)? = null

    private val scrollView = ScrollView(ctx)
    private val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var range = 0..0
    private var metaChapter = -1
    private var positioning = false

    private var highlightChapter = -1
    private var highlightRange: IntRange? = null

    private class ChapterHolder(
        val block: LinearLayout,
        val bodyView: TextView,
        val formatted: ChapterTextFormatter.FormattedChapter
    )
    private val holders = HashMap<Int, ChapterHolder>()

    init {
        scrollView.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrollView.overScrollMode = ScrollView.OVER_SCROLL_NEVER
        scrollView.isVerticalScrollBarEnabled = false
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (positioning) return@addOnScrollChangedListener
            ensureRange()
            updateVisible()
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = width
            if (tapSideEnabled && e.x < w * 0.30f) onSideTap?.invoke(false)
            else if (tapSideEnabled && e.x > w * 0.70f) onSideTap?.invoke(true)
            else onTapCenter?.invoke()
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    fun setTapHandler() {
        // 手势在 dispatchTouchEvent 统一分发，保持接口兼容
    }

    private fun buildChapterBlock(ci: Int): View {
        val h = handle ?: return View(context)
        val st = style
        val pad = st.marginXPx
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            tag = ci
        }
        val baseTf = st.typeface ?: Typeface.DEFAULT
        val boldTf = Typeface.create(baseTf, Typeface.BOLD)

        if (ci == metaChapter) {
            val name = TextView(context).apply {
                text = "《${h.title}》"
                typeface = boldTf
                textSize = st.fontSizePx / resources.displayMetrics.scaledDensity * 0.95f
                setTextColor(st.theme.fgTitle)
                paint.isFakeBoldText = true
                setPadding(0, 8.dp(context), 0, 2.dp(context))
            }
            block.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            listOf("作者：${h.author}", "来源：本地书籍", "网址：${h.meta.name}").forEach {
                block.addView(TextView(context).apply {
                    text = it
                    typeface = baseTf
                    setTextColor(st.theme.fgMeta)
                    textSize = st.fontSizePx / resources.displayMetrics.scaledDensity * 0.65f
                    setLineSpacing(st.fontSizePx * 0.38f, 1f)
                })
            }
        }

        val chTitleText = h.chapters.getOrNull(ci)?.title?.trim().orEmpty()
        if (chTitleText.isNotEmpty()) {
            val title = TextView(context).apply {
                text = chTitleText
                typeface = boldTf
                textSize = st.fontSizePx / resources.displayMetrics.scaledDensity * 1.26f
                setTextColor(st.theme.fgTitle)
                paint.isFakeBoldText = true
                setLineSpacing(st.fontSizePx * 0.45f, 1f)
                setPadding(0, 28.dp(context), 0, 14.dp(context))
            }
            block.addView(title)
        }

        val rawText = h.chapterText(ci)
        val hlRange = if (ci == highlightChapter) highlightRange else null
        val formatted = ChapterTextFormatter.format(
            rawBody = rawText,
            chapterTitle = chTitleText,
            bookTitle = h.title,
            bookAuthor = h.author,
            bookName = h.meta.name,
            includeHeaderInText = false,
            withMeta = false,
            theme = st.theme,
            fontSizePx = st.fontSizePx,
            paraSp = st.paraSp,
            highlightRawRange = hlRange
        )

        val body = TextView(context).apply {
            typeface = baseTf
            setText(formatted.text, TextView.BufferType.SPANNABLE)
            textSize = st.fontSizePx / resources.displayMetrics.scaledDensity
            setTextColor(st.theme.fg)
            setLineSpacing(st.fontSizePx * (st.lineSp - 1f), 1f)
            includeFontPadding = false
        }
        block.addView(body)
        holders[ci] = ChapterHolder(block, body, formatted)
        return block
    }

    /**
     * 捕获当前画面最上面三行文字的精确锚点 `ReadingAnchor`
     */
    fun captureTopThreeLinesAnchor(): ReadingAnchor {
        val h = handle ?: return ReadingAnchor(0, 0)
        if (h.chapters.isEmpty()) return ReadingAnchor(0, 0)
        val scrollY = scrollView.scrollY
        var targetCi = visibleChapter()
        var holder = holders[targetCi]

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val ci = child.tag as? Int ?: continue
            if (child.bottom > scrollY + 4.dp(context)) {
                targetCi = ci
                holder = holders[ci]
                break
            }
        }
        val chHolder = holder ?: return ReadingAnchor(targetCi, 0)
        val body = chHolder.bodyView
        val layout = body.layout ?: return ReadingAnchor(targetCi, 0)
        val bodyGlobalTop = chHolder.block.top + body.top

        // 如果当前视口顶部还在章节标题区域（正文尚未滚过视口顶部）
        if (scrollY <= bodyGlobalTop) {
            val firstThree = IntArray(min(3, layout.lineCount.coerceAtLeast(1))) { idx ->
                if (idx < layout.lineCount) chHolder.formatted.toRawOffset(layout.getLineStart(idx)) else 0
            }
            return ReadingAnchor(targetCi, 0, firstThree, 0)
        }

        val relativeY = (scrollY - bodyGlobalTop).coerceAtLeast(0)
        var firstVisibleLine = layout.getLineForVertical(relativeY)
        // 如果该行底部几乎已经滚出画面顶部（露出不足 35%），则以紧接着的下一行作为最上面第一行
        val lineTop = layout.getLineTop(firstVisibleLine)
        val lineBottom = layout.getLineBottom(firstVisibleLine)
        val lineH = (lineBottom - lineTop).coerceAtLeast(1)
        if (relativeY - lineTop > lineH * 0.65f && firstVisibleLine + 1 < layout.lineCount) {
            firstVisibleLine++
        }

        val count = min(3, (layout.lineCount - firstVisibleLine).coerceAtLeast(1))
        val topThree = IntArray(count) { idx ->
            val l = (firstVisibleLine + idx).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
            chHolder.formatted.toRawOffset(layout.getLineStart(l))
        }
        val rawOffset = topThree.firstOrNull() ?: 0
        return ReadingAnchor(targetCi, rawOffset, topThree, 0)
    }

    /**
     * 按画面最上面三行基准锚点恢复阅读位置
     */
    fun restoreAnchor(anchor: ReadingAnchor) {
        val h = handle ?: return
        if (h.chapters.isEmpty()) return
        val ci = anchor.chapterIndex.coerceIn(0, h.chapters.size - 1)
        val needsRebuild = container.childCount == 0 || container.findViewWithTag<View>(ci) == null
        if (needsRebuild) {
            rebuildChaptersRange(ci)
        }
        scrollToAnchorInternal(anchor)
        post {
            scrollToAnchorInternal(anchor)
            ensureRange()
            updateVisible()
        }
    }

    private fun scrollToAnchorInternal(anchor: ReadingAnchor) {
        val ci = anchor.chapterIndex
        val holder = holders[ci] ?: return
        val blk = holder.block
        val body = holder.bodyView
        val layout = body.layout
        positioning = true
        try {
            val targetScrollY = if (anchor.primaryCharOffset <= 0 || layout == null || layout.lineCount == 0) {
                blk.top
            } else {
                val fmtOffset = holder.formatted.toFormattedOffset(anchor.primaryCharOffset)
                val line = layout.getLineForOffset(fmtOffset.coerceIn(0, holder.formatted.text.length))
                blk.top + body.top + layout.getLineTop(line)
            }
            val maxScroll = max(0, container.height - scrollView.height)
            scrollView.scrollTo(0, targetScrollY.coerceIn(0, maxScroll))
        } finally {
            positioning = false
        }
    }

    /**
     * 字号、行间距、段间距、页边距、字体或主题变更后重新排版，并严格以画面最上面三行作为基准保持阅读位置
     */
    fun relayout(explicitAnchor: ReadingAnchor? = null) {
        val anchor = explicitAnchor ?: captureTopThreeLinesAnchor()
        rebuildChaptersRange(anchor.chapterIndex)
        restoreAnchor(anchor)
    }

    fun openAt(ci: Int, ratio: Float, charOffset: Int = -1) {
        metaChapter = ci
        rebuildChaptersRange(ci)
        post {
            if (charOffset >= 0) {
                restoreAnchor(ReadingAnchor(ci, charOffset, intArrayOf(charOffset)))
            } else {
                scrollToChapter(ci, ratio)
            }
        }
    }

    private fun rebuildChaptersRange(centerCi: Int) {
        val h = handle ?: return
        if (h.chapters.isEmpty()) return
        val ci = centerCi.coerceIn(0, h.chapters.size - 1)
        positioning = true
        try {
            container.removeAllViews()
            holders.clear()
            val first = max(0, ci - 1)
            val last = min(h.chapters.size - 1, ci + 2)
            range = first..last
            for (i in first..last) {
                container.addView(buildChapterBlock(i))
            }
        } finally {
            positioning = false
        }
    }

    fun setHighlight(ci: Int, rawRange: IntRange?) {
        highlightChapter = ci
        highlightRange = rawRange
    }

    fun clearHighlight() {
        if (highlightRange != null) {
            highlightChapter = -1
            highlightRange = null
            relayout()
        }
    }

    fun appendChapter(ci: Int) {
        val h = handle ?: return
        if (ci !in 0 until h.chapters.size) return
        if (container.findViewWithTag<View>(ci) != null) return
        container.addView(buildChapterBlock(ci))
        range = min(range.first, ci)..max(range.last, ci)
    }

    fun prependChapter(ci: Int) {
        val h = handle ?: return
        if (ci !in 0 until h.chapters.size) return
        if (container.findViewWithTag<View>(ci) != null) return
        val anchor = container.getChildAt(0)
        val oldScrollY = scrollView.scrollY
        val oldBottom = anchor?.bottom ?: 0
        container.addView(buildChapterBlock(ci), 0)
        range = min(range.first, ci)..max(range.last, ci)
        post {
            val newTop = anchor?.top ?: 0
            if (newTop > 0) {
                positioning = true
                scrollView.scrollTo(0, oldScrollY + newTop)
                positioning = false
            }
        }
    }

    fun ensureRange() {
        val h = handle ?: return
        val vh = scrollView.height
        if (vh <= 0) return
        val st = scrollView.scrollY
        val sh = container.height
        var n = 0
        while (n++ < 4 && range.last < h.chapters.size - 1 && sh - (st + vh) < vh * 2.0f)
            appendChapter(range.last + 1)
        n = 0
        while (n++ < 2 && range.first > 0 && st < vh * 1.2f)
            prependChapter(range.first - 1)
        trimRange()
    }

    private fun trimRange() {
        val maxChapters = 14
        while (range.last - range.first + 1 > maxChapters) {
            val vis = visibleChapter()
            if (vis - range.first >= range.last - vis) {
                val rm = range.last
                container.findViewWithTag<View>(rm)?.let { container.removeView(it) }
                holders.remove(rm)
                range = range.first..(range.last - 1)
            } else {
                val rm = range.first
                val blk = container.findViewWithTag<View>(rm)
                val hh = blk?.height ?: 0
                if (blk != null) {
                    positioning = true
                    scrollView.scrollBy(0, -hh)
                    container.removeView(blk)
                    positioning = false
                }
                holders.remove(rm)
                range = (range.first + 1)..range.last
            }
        }
    }

    fun visibleChapter(): Int {
        val y = scrollView.scrollY + 24.dp(context)
        var cur = range.first
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c.top <= y) cur = c.tag as Int else break
        }
        return cur
    }

    fun ratioOf(ci: Int): Float {
        val blk = container.findViewWithTag<View>(ci) ?: return 0f
        if (blk.height <= 1) return 0f
        val r = (scrollView.scrollY - blk.top).toFloat() / blk.height.coerceAtLeast(1)
        return r.coerceIn(0f, 1f)
    }

    fun scrollToChapter(ci: Int, ratio: Float) {
        val h = handle ?: return
        if (ci !in 0 until h.chapters.size) return
        if (container.findViewWithTag<View>(ci) == null) {
            rebuildChaptersRange(ci)
        }
        val applyScroll = {
            val blk = container.findViewWithTag<View>(ci)
            if (blk != null && blk.height > 0) {
                positioning = true
                try {
                    val offsetInChapter = if (ratio <= 0.001f) {
                        0
                    } else if (ratio >= 0.999f) {
                        max(0, blk.height - scrollView.height)
                    } else {
                        (ratio.coerceIn(0f, 1f) * blk.height).toInt()
                    }
                    val targetY = blk.top + offsetInChapter
                    val maxScroll = max(0, container.height - scrollView.height)
                    scrollView.scrollTo(0, targetY.coerceIn(0, maxScroll))
                } finally {
                    positioning = false
                }
                ensureRange()
                updateVisible()
            }
        }
        applyScroll()
        post { applyScroll() }
    }

    private fun updateVisible() {
        val ci = visibleChapter()
        onVisibleChapter?.invoke(ci)
        onScrollRatio?.invoke(ci, ratioOf(ci))
    }

    /** 章节精确跳转（跳到该章开头或末尾） */
    fun gotoChapter(ci: Int, atEnd: Boolean = false) {
        val h = handle ?: return
        if (ci !in 0 until h.chapters.size) return
        rebuildChaptersRange(ci)
        scrollToChapter(ci, if (atEnd) 1f else 0f)
    }

    fun scrollPage(next: Boolean) {
        scrollView.smoothScrollBy(0, (if (next) 1 else -1) * (scrollView.height * 0.90).toInt())
    }

    fun autoScrollStep(dtMs: Long, speed: Float) {
        ensureRange()
        scrollView.scrollBy(0, (speed * 30 * dtMs / 1000f).toInt().coerceAtLeast(1))
        val maxS = container.height - scrollView.height
        if (scrollView.scrollY >= maxS - 1 && range.last < (handle?.chapters?.size ?: 0) - 1) {
            ensureRange()
        }
    }
}
