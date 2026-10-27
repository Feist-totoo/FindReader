package com.quark.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

fun Float.dp(ctx: Context): Float = this * ctx.resources.displayMetrics.density
fun Int.dp(ctx: Context): Int = (this * ctx.resources.displayMetrics.density).toInt()

/**
 * 阅读位置锚点（以画面中最上面三行文字作为基准）：
 * - [chapterIndex]：当前最上面三行所在的章节索引
 * - [rawCharOffset]：最上面第一行（基准行）在章节原始文本 (`chapterText(ci)`) 中的字符偏移量
 * - [topThreeOffsets]：画面最上面三行各自在章节原始文本中的起始字符偏移量（长度 1..3）
 * - [firstLineTopOffsetPx]：第一行顶部距离视口内容区顶部的像素微调量（滚动模式下用于行内无感对齐）
 */
data class ReadingAnchor(
    val chapterIndex: Int,
    val rawCharOffset: Int,
    val topThreeOffsets: IntArray = intArrayOf(rawCharOffset),
    val firstLineTopOffsetPx: Int = 0
) {
    /**
     * 以画面最上面三行作为基准，计算目标偏移：优先取第一行起始字符，确保该行及紧随的第二、三行在字号/行距重排后依然处于画面顶部区域
     */
    val primaryCharOffset: Int
        get() = if (topThreeOffsets.isNotEmpty()) topThreeOffsets[0].coerceAtLeast(0) else rawCharOffset.coerceAtLeast(0)
}

/**
 * 段落末尾额外段间距 Span（只在该段落最后一行底部增加 extraBottomPx，使行间距与段间距独立生效）
 */
class ParagraphSpacingSpan(private val extraBottomPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        if (extraBottomPx <= 0) return
        val spanEnd = (text as? Spanned)?.getSpanEnd(this) ?: return
        // 当本行包含段落结尾换行符或到达该段末尾时，追加段间距
        if (end >= spanEnd) {
            fm.descent += extraBottomPx
            fm.bottom += extraBottomPx
        }
    }
}

/**
 * 强化加粗 Span（同时设置 Typeface.BOLD 与 isFakeBoldText = true，解决中文系统字体下普通 StyleSpan(BOLD) 不加粗的问题）
 */
class ChineseBoldSpan : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        applyBold(tp)
    }
    override fun updateMeasureState(tp: TextPaint) {
        applyBold(tp)
    }
    private fun applyBold(tp: TextPaint) {
        val old = tp.typeface
        tp.typeface = if (old != null) Typeface.create(old, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        tp.isFakeBoldText = true
    }
}

/**
 * 章节排版构建器：负责把章节原始文本构建为带双向字符偏移映射的 SpannableStringBuilder，
 * 并统一处理：
 * 1. 章节主标题与章内小标题的加粗加大（ChineseBoldSpan + RelativeSizeSpan）
 * 2. 段间距（ParagraphSpacingSpan）与首行两字符缩进
 * 3. 关键词查找高亮（BackgroundColorSpan + ForegroundColorSpan）
 * 4. 格式化文本偏移 <-> 原始章节文本偏移的精确映射（支撑最上面三行基准定位）
 */
object ChapterTextFormatter {
    class FormattedChapter(
        val text: SpannableStringBuilder,
        val bodyFormattedStart: Int,
        val formattedToRaw: IntArray,
        val rawLength: Int
    ) {
        fun toRawOffset(formattedOffset: Int): Int {
            if (formattedToRaw.isEmpty()) return 0
            val idx = formattedOffset.coerceIn(0, formattedToRaw.size - 1)
            return formattedToRaw[idx]
        }

        fun toFormattedOffset(rawOffset: Int): Int {
            if (formattedToRaw.isEmpty()) return bodyFormattedStart
            val target = rawOffset.coerceIn(0, rawLength)
            var lo = bodyFormattedStart.coerceIn(0, formattedToRaw.size - 1)
            var hi = formattedToRaw.size - 1
            var ans = lo
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (formattedToRaw[mid] <= target) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            // 对齐到跳过前导全角空格后的首个实际字符
            while (ans > bodyFormattedStart && formattedToRaw[ans - 1] == target && text[ans - 1] != '　') {
                ans--
            }
            return ans
        }
    }

    /**
     * 在给定每一行的 `[lineStart, lineEnd)` 列表中，给定分页的页行区间 `[pageStartLine, pageEndLine)`，
     * 找出包含 `targetFormattedOffset`（即画面最上面三行基准点）的那一页索引。
     */
    fun findPageForFormattedOffset(
        pages: List<IntArray>,
        lineStarts: IntArray,
        lineEnds: IntArray,
        targetFormattedOffset: Int
    ): Int {
        if (pages.isEmpty() || lineStarts.isEmpty()) return 0
        var targetLine = 0
        for (i in lineStarts.indices) {
            if (targetFormattedOffset >= lineStarts[i] && targetFormattedOffset <= lineEnds[i]) {
                targetLine = i
                break
            }
            if (lineStarts[i] <= targetFormattedOffset) {
                targetLine = i
            }
        }
        for (pIdx in pages.indices) {
            val range = pages[pIdx]
            if (targetLine >= range[0] && targetLine < range[1]) {
                return pIdx
            }
        }
        return (pages.size - 1).coerceAtLeast(0)
    }

    fun format(
        rawBody: String,
        chapterTitle: String,
        bookTitle: String,
        bookAuthor: String,
        bookName: String,
        includeHeaderInText: Boolean,
        withMeta: Boolean,
        theme: ThemeSpec,
        fontSizePx: Float,
        paraSp: Float,
        highlightRawRange: IntRange? = null
    ): FormattedChapter {
        val ssb = SpannableStringBuilder()
        val mapList = ArrayList<Int>(rawBody.length + 256)

        fun appendUnmapped(s: String, rawRef: Int) {
            for (i in s.indices) {
                ssb.append(s[i])
                mapList.add(rawRef)
            }
        }

        val paraExtraPx = (fontSizePx * paraSp.coerceIn(0.1f, 2.5f)).roundToInt()

        if (includeHeaderInText) {
            if (withMeta) {
                val nameStart = ssb.length
                appendUnmapped("《$bookTitle》\n", 0)
                ssb.setSpan(ChineseBoldSpan(), nameStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(0.95f), nameStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(theme.fgTitle), nameStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                val metaStart = ssb.length
                appendUnmapped("作者：$bookAuthor\n来源：本地书籍\n网址：$bookName\n", 0)
                ssb.setSpan(RelativeSizeSpan(0.65f), metaStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(theme.fgMeta), metaStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ParagraphSpacingSpan((fontSizePx * 0.8f).roundToInt()), metaStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (chapterTitle.isNotBlank()) {
                val tStart = ssb.length
                appendUnmapped(chapterTitle + "\n", 0)
                ssb.setSpan(ChineseBoldSpan(), tStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(1.26f), tStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(theme.fgTitle), tStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ParagraphSpacingSpan((fontSizePx * 0.9f).roundToInt()), tStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val bodyFormattedStart = ssb.length
        var rawCursor = 0
        val lines = rawBody.split("\n")
        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            val lineRawStart = rawCursor
            val trimmed = line.trim()
            val isSubHeading = Books.isSubHeadingLine(trimmed) &&
                (lineIdx > 0 || !trimmed.equals(chapterTitle.trim(), ignoreCase = true))
            val isDuplicateMainTitle = lineIdx == 0 && trimmed.equals(chapterTitle.trim(), ignoreCase = true)

            if (!isDuplicateMainTitle && trimmed.isNotEmpty()) {
                val paraStart = ssb.length
                if (isSubHeading) {
                    // 章内二级标题或未切分的章节标题：不加段首缩进，直接加粗加大并设标题色
                    for (i in line.indices) {
                        ssb.append(line[i])
                        mapList.add(lineRawStart + i)
                    }
                    if (lineIdx < lines.size - 1) {
                        ssb.append('\n')
                        mapList.add((lineRawStart + line.length).coerceAtMost(rawBody.length))
                    }
                    ssb.setSpan(ChineseBoldSpan(), paraStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(RelativeSizeSpan(1.18f), paraStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(theme.fgTitle), paraStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ParagraphSpacingSpan((paraExtraPx * 1.15f).roundToInt()), paraStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    appendUnmapped("　　", lineRawStart)
                    for (i in line.indices) {
                        ssb.append(line[i])
                        mapList.add(lineRawStart + i)
                    }
                    if (lineIdx < lines.size - 1) {
                        ssb.append('\n')
                        mapList.add((lineRawStart + line.length).coerceAtMost(rawBody.length))
                    }
                    ssb.setSpan(ParagraphSpacingSpan(paraExtraPx), paraStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            rawCursor += line.length + 1
        }
        mapList.add(rawBody.length)

        val formattedToRaw = mapList.toIntArray()
        val formatted = FormattedChapter(ssb, bodyFormattedStart, formattedToRaw, rawBody.length)

        if (highlightRawRange != null && !highlightRawRange.isEmpty()) {
            val hStart = formatted.toFormattedOffset(highlightRawRange.first)
            val hEnd = (formatted.toFormattedOffset(highlightRawRange.last + 1)).coerceIn(hStart, ssb.length)
            if (hEnd > hStart) {
                ssb.setSpan(BackgroundColorSpan(0x99FFD54F.toInt()), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(0xFF1A1A1A.toInt()), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ChineseBoldSpan(), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return formatted
    }
}

/** 电池小图标 */
class BatteryIcon @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var level = 0.93f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bodyH = h * 0.72f; val top = (h - bodyH) / 2
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1.4f, h * 0.12f)
        p.color = currentTextColor()
        val r = RectF(1f, top, w - h * 0.22f, top + bodyH)
        c.drawRoundRect(r, 2f, 2f, p)
        c.drawRect(w - h * 0.16f, h * 0.34f, w - 0.5f, h * 0.66f, p)
        p.style = Paint.Style.FILL
        val fillW = (r.width() - p.strokeWidth) * level
        c.drawRect(
            r.left + p.strokeWidth / 2 + (r.width() - p.strokeWidth - fillW) * 0f,
            r.top + p.strokeWidth / 2 + (r.height() - p.strokeWidth) * (1 - level),
            r.right - p.strokeWidth / 2, r.bottom - p.strokeWidth / 2, p
        )
    }
    private fun currentTextColor() = 0xFF9A9A9A.toInt()
}

/** 进度胶囊（夸克样式）：拖动跳转，拖动时显示百分比 */
class PillBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (!dragging) invalidate() }
    var onRelease: ((Float) -> Unit)? = null
    var accent = 0xFF3B6EF0.toInt()
    var pillColor = 0xFFE9E9E9.toInt()
    var textColor = 0xFF333333.toInt()
    private var dragging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent.requestDisallowInterceptTouchEvent(true); update(e.x); performClick(); return true }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP -> { update(e.x); dragging = false; onRelease?.invoke(progress); invalidate() }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
    private fun update(x: Float) {
        progress = if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)
        invalidate()
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = pillColor
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        if (dragging) {
            p.color = accent
            val fw = w * progress
            if (fw > 0) c.drawRoundRect(0f, 0f, fw, h, h / 2, h / 2, p)
            p.color = accent
            c.drawCircle(fw.coerceIn(h / 2, w - h / 2), h / 2, h * 0.26f, p)
            textP.color = if (progress > 0.55f && abs(progress - 0f) > 0.01f && fw > h * 1.2f) Color.WHITE else textColor
            textP.textSize = h * 0.36f
            val label = if (progress >= 0.999f) "100%" else String.format("%.1f%%", progress * 100)
            c.drawText(label, w / 2, h / 2 - (textP.descent() + textP.ascent()) / 2, textP)
        }
    }
}
