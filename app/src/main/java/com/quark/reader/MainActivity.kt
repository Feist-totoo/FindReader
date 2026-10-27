package com.quark.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * 书架主页：五种艺术风格（藏書閣、档案馆、展覽廳、云书房、星海）
 * 严格按照 PC 原型（quark-reader-pc/index.html & shelf.js & shelf.css）1:1 复刻
 */
class MainActivity : AppCompatActivity() {
    private val ctx: Context get() = this
    private lateinit var prefs: Prefs
    private lateinit var stage: FrameLayout
    private lateinit var island: LinearLayout
    private lateinit var siTitle: TextView
    private lateinit var bookCount: TextView
    private lateinit var btnSearch: ImageButton
    private lateinit var shelfRoot: FrameLayout
    private var searchPanel: BookSearchPanel? = null
    private val searchBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeSearch() }
    }
    private lateinit var btnTheme: ImageButton
    private lateinit var btnAdd: ImageButton
    private lateinit var themePop: LinearLayout
    var books: MutableList<Books.Meta> = mutableListOf()
    private var editingBook: Books.Meta? = null

    private val pickBook = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        importUri(uri)
    }
    private val pickCover = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val b = editingBook ?: return@registerForActivityResult
        try {
            val f = File(Books.dirCovers(this), b.id + ".img")
            contentResolver.openInputStream(uri)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
            renderStyle()
        } catch (e: Exception) { Toast.makeText(this, "封面设置失败", Toast.LENGTH_SHORT).show() }
    }
    private val pickShelfZip = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val installed = CustomThemeManager.importShelfStyleZip(this, uri)
            prefs.shelfTheme = installed.id
            rebuildThemePop()
            themePop.visibility = View.GONE
            renderStyle(true)
            val syncMsg = if (installed.readerSpec != null) "（含配套阅读主题）" else ""
            Toast.makeText(this, "已添加并启用书架风格「${installed.name}」$syncMsg", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "书架风格包解析失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        ThemeSpec.refreshCustomThemes(this)
        ShelfTheme.refreshCustomShelves(this)
        buildUI()
        Books.importWelcomeIfNeeded(this, object : Books.ImportCallback {
            override fun onHeadReady(id: String) { runOnUiThread { refresh() } }
            override fun onDone(id: String, ok: Boolean) { runOnUiThread { refresh() } }
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun buildUI() {
        val root = FrameLayout(this)
        shelfRoot = root
        root.setBackgroundColor(0xFFF4F6FA.toInt())
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 悬浮玻璃岛头部 (PC 版 .shelf-island)
        island = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(ctx), 0, 6.dp(ctx), 0)
            elevation = 6f.dp(ctx)
        }
        siTitle = TextView(this).apply {
            text = "书架"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.18f
        }
        island.addView(siTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8.dp(ctx) })

        bookCount = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        island.addView(bookCount, LinearLayout.LayoutParams(52.dp(ctx), 44.dp(ctx)))

        btnTheme = ImageButton(this).apply {
            setImageResource(R.drawable.ic_grid)
            contentDescription = "切换风格"
            background = null
        }
        btnTheme.setOnClickListener { themePop.visibility = if (themePop.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        island.addView(btnTheme, LinearLayout.LayoutParams(44.dp(ctx), 44.dp(ctx)))

        btnAdd = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            contentDescription = "导入书籍"
            setPadding(10.dp(ctx), 10.dp(ctx), 10.dp(ctx), 10.dp(ctx))
            setColorFilter(Color.WHITE)
            setOnClickListener { pickBook.launch("*/*") }
        }
        island.addView(btnAdd, LinearLayout.LayoutParams(44.dp(ctx), 44.dp(ctx)).apply { leftMargin = 6.dp(ctx) })

        root.addView(island, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 50.dp(ctx), Gravity.TOP or Gravity.START).apply { topMargin = 10.dp(ctx); leftMargin = 12.dp(ctx) })

        btnSearch = ImageButton(this).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = "搜索书籍"
            setPadding(12.dp(ctx), 12.dp(ctx), 12.dp(ctx), 12.dp(ctx))
            alpha = 0.78f
            elevation = 8f.dp(ctx)
            setOnClickListener { openSearch() }
        }
        root.addView(btnSearch, FrameLayout.LayoutParams(48.dp(ctx), 48.dp(ctx), Gravity.TOP or Gravity.END).apply {
            topMargin = 11.dp(ctx); rightMargin = 12.dp(ctx)
        })

        // 风格弹层 (PC 版 .theme-pop)
        themePop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(ctx), 12.dp(ctx), 12.dp(ctx), 12.dp(ctx))
            visibility = View.GONE
            elevation = 10f.dp(ctx)
        }
        themePop.background = GradientDrawable().apply {
            cornerRadius = 16.dp(ctx).toFloat()
            setColor(0xF8FFFFFF.toInt())
            setStroke(1.dp(ctx), 0x1A000000.toInt())
        }
        rebuildThemePop()
        root.addView(themePop, FrameLayout.LayoutParams(280.dp(ctx), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = 68.dp(ctx); rightMargin = 14.dp(ctx) })

        setContentView(root)
        SafeSystemBars.install(this, root)
        onBackPressedDispatcher.addCallback(this, searchBack)
    }

    private fun openSearch() {
        if (searchPanel != null) return
        themePop.visibility = View.GONE
        val panel = BookSearchPanel(this, books, { book ->
            closeSearch()
            stage.postDelayed({ (stage.getChildAt(0) as? ShelfNavigator)?.focusBook(book.id) }, 220)
        }, { closeSearch() })
        searchPanel = panel
        searchBack.isEnabled = true
        shelfRoot.addView(panel, FrameLayout.LayoutParams(-1, -1))
        panel.showKeyboard()
    }

    private fun closeSearch() {
        searchPanel?.let { it.hideKeyboard(); shelfRoot.removeView(it) }
        searchPanel = null
        searchBack.isEnabled = false
    }

    /** 联动更新悬浮岛配色，严格对齐 PC 版 .shelf-island 在各主题下的专属材质 */
    fun updateIslandTheme(theme: String) {
        val customSpec = ShelfTheme.customOf(theme)
        if (customSpec != null) {
            island.background = GradientDrawable().apply {
                cornerRadius = 24f.dp(ctx)
                setColor(customSpec.islandBgColor)
                setStroke(1.dp(ctx), customSpec.islandStrokeColor)
            }
            siTitle.setTextColor(customSpec.islandTextColor)
            btnTheme.setColorFilter(customSpec.islandTextColor)
            btnAdd.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(customSpec.accentColor) }
            val light = customSpec.lightStatusBar
            val ink = customSpec.islandTextColor
            bookCount.setTextColor(ink)
            bookCount.alpha = 0.72f
            btnSearch.setColorFilter(ink)
            btnSearch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (light) 0xA8FFFFFF.toInt() else 0xA8202432.toInt())
                setStroke(1.dp(ctx), if (light) 0x30FFFFFF else 0x30BCC8E0)
            }
            shelfRoot.setBackgroundColor(customSpec.bgColor)
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
            return
        }
        when (theme) {
            "antique" -> {
                island.background = GradientDrawable().apply {
                    cornerRadius = 24f.dp(ctx)
                    setColor(0xB83E2C18.toInt())
                    setStroke(1.dp(ctx), 0x80B99C6B.toInt())
                }
                siTitle.setTextColor(0xFFF2DFB2.toInt())
                btnTheme.setColorFilter(0xFFEFE0C2.toInt())
                btnAdd.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFB99C6B.toInt()) }
            }
            "minimal" -> {
                island.background = GradientDrawable().apply {
                    cornerRadius = 24f.dp(ctx)
                    setColor(0xD6FFFFFF.toInt())
                    setStroke(1.dp(ctx), 0x1A22252B.toInt())
                }
                siTitle.setTextColor(0xFF22252B.toInt())
                btnTheme.setColorFilter(0xFF22252B.toInt())
                btnAdd.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF22252B.toInt()) }
            }
            "gallery" -> {
                island.background = GradientDrawable().apply {
                    cornerRadius = 24f.dp(ctx)
                    setColor(0xBD1E150D.toInt())
                    setStroke(1.dp(ctx), 0x6BB89A5E.toInt())
                }
                siTitle.setTextColor(0xFFF2DEAE.toInt())
                btnTheme.setColorFilter(0xFFEFDCB0.toInt())
                btnAdd.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFB89A5E.toInt()) }
            }
            "galaxy" -> {
                island.background = GradientDrawable().apply {
                    cornerRadius = 24f.dp(ctx)
                    setColor(0x99161C3A.toInt())
                    setStroke(1.dp(ctx), 0x33A0B4FF.toInt())
                }
                siTitle.setTextColor(0xFFDAE1F6.toInt())
                btnTheme.setColorFilter(0xFFDAE1F6.toInt())
                btnAdd.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6C8CFF.toInt()) }
            }
            else -> { // cloud
                island.background = GradientDrawable().apply {
                    cornerRadius = 24f.dp(ctx)
                    setColor(0x99FFFFFF.toInt())
                    setStroke(1.dp(ctx), 0xD9FFFFFF.toInt())
                }
                siTitle.setTextColor(0xFF2E4C68.toInt())
                btnTheme.setColorFilter(0xFF2E4C68.toInt())
                btnAdd.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF4C86CF.toInt()) }
            }
        }
        val light = theme in setOf("minimal", "cloud")
        val ink = siTitle.currentTextColor
        bookCount.setTextColor(ink)
        bookCount.alpha = 0.65f
        btnSearch.setColorFilter(ink)
        btnSearch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (light) 0xA8FFFFFF.toInt() else 0xA8202432.toInt())
            setStroke(1.dp(ctx), if (light) 0x30FFFFFF else 0x30BCC8E0)
        }
        shelfRoot.setBackgroundColor(when (theme) {
            "antique" -> 0xFF3A2915.toInt()
            "minimal" -> 0xFFF4F5F7.toInt()
            "gallery" -> 0xFF100B06.toInt()
            "galaxy" -> 0xFF03050C.toInt()
            else -> 0xFFA0D2FB.toInt()
        })
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun importUri(uri: Uri) {
        val name = queryName(uri) ?: "book"
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        val cb = object : Books.ImportCallback {
            override fun onHeadReady(id: String) {}
            override fun onDone(id: String, ok: Boolean) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, if (ok) "导入完成" else "导入失败", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        }
        if (name.endsWith(".epub", true)) Books.importEpub(this, uri, name, cb)
        else Books.importTxt(this, uri, name, cb)
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        books = Books.listMeta(this).sortedBy { it.addedAt }.toMutableList()
        renderStyle()
    }

    fun openBook(m: Books.Meta) {
        if (!m.parsed) { Toast.makeText(this, "解析中，稍候…", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, m.id))
    }

    fun showActions(m: Books.Meta) {
        editingBook = m
        val items = arrayOf("自定义封面", "书籍详情", "移除书籍", "取消")
        val colors = intArrayOf(0xFF222222.toInt(), 0xFF222222.toInt(), 0xFFE5484D.toInt(), 0xFF888888.toInt())
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(6.dp(ctx), 6.dp(ctx), 6.dp(ctx), 6.dp(ctx))
        lay.background = GradientDrawable().apply { cornerRadius = 18.dp(ctx).toFloat(); setColor(0xF6FFFFFF.toInt()) }
        val wrap = FrameLayout(this)
        wrap.setBackgroundColor((0x88000000).toLong().toInt())
        val root = shelfRoot
        items.forEachIndexed { i, s ->
            val tv = TextView(this)
            tv.text = s; tv.textSize = 14.5f; tv.setTextColor(colors[i])
            tv.setPadding(24.dp(ctx), 15.dp(ctx), 24.dp(ctx), 15.dp(ctx))
            lay.addView(tv)
            tv.setOnClickListener {
                when (i) {
                    0 -> pickCover.launch("image/*")
                    1 -> Toast.makeText(this@MainActivity,
                        "《${m.title}》\n作者：${m.author}\n${m.kind.uppercase()} · ${m.chapterCount} 章\n约 ${m.totalChars / 10000} 万字", Toast.LENGTH_LONG).show()
                    2 -> { Books.removeMeta(this@MainActivity, m.id); refresh() }
                }
                root.removeView(wrap)
            }
        }
        root.addView(wrap, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        wrap.addView(lay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 12.dp(ctx) })
        wrap.setOnClickListener { root.removeView(wrap) }
    }

    private val popItems: MutableList<TextView> = mutableListOf()
    private fun rebuildThemePop() {
        ShelfTheme.refreshCustomShelves(this)
        themePop.removeAllViews()
        popItems.clear()

        // 1. 内置 5 种书架风格行
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("古朴" to "antique", "简约" to "minimal", "收藏馆" to "gallery", "云端" to "cloud", "星河" to "galaxy").forEach { (n, k) ->
            val b = TextView(this).apply {
                text = n; textSize = 11.5f; tag = k
                gravity = Gravity.CENTER
                setPadding(0, 8.dp(ctx), 0, 8.dp(ctx))
            }
            popItems.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 3.dp(ctx); leftMargin = 3.dp(ctx) })
            b.setOnClickListener {
                prefs.shelfTheme = k
                refreshThemePop()
                themePop.visibility = View.GONE
                renderStyle(true)
            }
        }
        themePop.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 2. 用户自定义安装的书架风格列表（支持横向滚动、点击切换、长按移除）
        val customSpecs = ShelfTheme.customSpecs().values.toList()
        if (customSpecs.isNotEmpty()) {
            val label = TextView(this).apply {
                text = "自定义书架风格（长按可移除）"
                textSize = 10.5f
                setTextColor(0xFF7A8599.toInt())
                setPadding(4.dp(ctx), 8.dp(ctx), 4.dp(ctx), 4.dp(ctx))
            }
            themePop.addView(label)
            val hScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val customRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            customSpecs.forEach { spec ->
                val chip = TextView(this).apply {
                    text = spec.name
                    textSize = 11.5f
                    tag = spec.key
                    gravity = Gravity.CENTER
                    setPadding(12.dp(ctx), 7.dp(ctx), 12.dp(ctx), 7.dp(ctx))
                }
                popItems.add(chip)
                customRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = 6.dp(ctx)
                })
                chip.setOnClickListener {
                    prefs.shelfTheme = spec.key
                    refreshThemePop()
                    themePop.visibility = View.GONE
                    renderStyle(true)
                }
                chip.setOnLongClickListener {
                    CustomThemeManager.removeCustomShelf(this@MainActivity, spec.key)
                    rebuildThemePop()
                    renderStyle(true)
                    Toast.makeText(this@MainActivity, "已移除书架风格「${spec.name}」", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            hScroll.addView(customRow)
            themePop.addView(hScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 3. 导入新书架风格压缩包 (.zip) 按钮
        val addShelfBtn = TextView(this).apply {
            text = "＋ 导入自定义书架风格（.zip）"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF3B6EF0.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 9.dp(ctx), 0, 9.dp(ctx))
            background = GradientDrawable().apply {
                cornerRadius = 12.dp(ctx).toFloat()
                setColor(0x143B6EF0)
                setStroke(1.dp(ctx), 0x4D3B6EF0)
            }
            setOnClickListener { pickShelfZip.launch("*/*") }
        }
        themePop.addView(addShelfBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dp(ctx)
        })
        refreshThemePop()
    }

    private fun refreshThemePop() {
        popItems.forEach { b ->
            val on = b.tag == prefs.shelfTheme
            b.setTextColor(if (on) Color.WHITE else 0xFF445066.toInt())
            b.background = GradientDrawable().apply {
                cornerRadius = 12.dp(ctx).toFloat()
                setColor(if (on) 0xFF3B6EF0.toInt() else 0x1422252B.toInt())
            }
        }
    }

    /* ---------------- 风格渲染 ---------------- */
    fun renderStyle(animateSwitch: Boolean = false) {
        ShelfTheme.refreshCustomShelves(this)
        val theme = prefs.shelfTheme
        val customSpec = ShelfTheme.customOf(theme)
        updateIslandTheme(theme)
        val maker: (MainActivity) -> View = when {
            customSpec != null -> { ctx: MainActivity -> CustomShelfView(ctx, customSpec) }
            theme == "antique" -> { ctx: MainActivity -> AntiqueShelfView(ctx) }
            theme == "minimal" -> { ctx: MainActivity -> MinimalShelfView(ctx) }
            theme == "gallery" -> { ctx: MainActivity -> GalleryShelfView(ctx) }
            theme == "galaxy" -> { ctx: MainActivity -> GalaxyShelfView(ctx) }
            else -> { ctx: MainActivity -> CloudShelfView(ctx) }
        }
        bookCount.text = "${books.size}本"
        siTitle.text = customSpec?.shortTitle
            ?: mapOf("antique" to "藏書閣", "minimal" to "简约", "gallery" to "收藏馆", "cloud" to "云端", "galaxy" to "星海")[theme]
            ?: "书架"
        val old = stage.getChildAt(0)
        old?.animate()?.cancel()
        val apply = {
            stage.removeAllViews()
            stage.addView(maker(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        if (animateSwitch && old != null) {
            old.animate().alpha(0f).scaleX(.95f).scaleY(.95f).setDuration(300).withEndAction { apply() }.start()
        } else apply()
    }

    fun coverBitmap(m: Books.Meta): android.graphics.Bitmap? = try {
        val f = File(Books.dirCovers(this), m.id + ".img")
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    } catch (e: Exception) { null }
}

/* ============================================================
 * 一 · 古朴 —— 木柜书房：木架匾额 + 封面竖排靠放 + 文房四宝案台 (PC 版 .cab)
 * ============================================================ */
class AntiqueShelfView(private val act: MainActivity) : LinearLayout(act), ShelfNavigator {
    private val ctx get() = context
    private var page = 0
    private val PER = 7
    private var pageWrap: FrameLayout? = null
    private var countView: TextView? = null
    private var isTurning = false

    private var touchDownX = 0f
    private var touchDownY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    init {
        orientation = VERTICAL
        // 背景条纹渐变
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF4A341F.toInt(), 0xFF3E2C18.toInt(), 0xFF33230F.toInt()))
        setPadding(16.dp(ctx), 66.dp(ctx), 16.dp(ctx), 0)
        build()
    }
    private val pages: Int get() = maxOf(1, (act.books.size + PER - 1) / PER)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - touchDownX
                val dy = ev.y - touchDownY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (abs(dx) > 42.dp(ctx) && abs(dx) > abs(dy) * 1.3f) {
                    turn(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        return true
    }

    private fun renderPage() {
        val pw = pageWrap ?: return
        pw.removeAllViews()
        val list = act.books.drop(page * PER).take(PER)
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        (0..1).forEach { shelfIdx ->
            val shelf = LinearLayout(ctx).apply {
                orientation = VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(6.dp(ctx), 4.dp(ctx), 6.dp(ctx), 0)
            }
            val rack = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            // 依北斗七曜容纳设计：上架 4 卷、下架 3 卷，严格对齐星座 7 星容纳
            val shelfBooks = if (shelfIdx == 0) list.take(4) else list.drop(4).take(3)
            val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val wDp = if (shelfIdx == 0) minOf(70, ((screenDp - 72) / 4 - 8).toInt()) else minOf(80, ((screenDp - 72) / 3 - 16).toInt())
            val hDp = (wDp * 1.52f).toInt()
            val marginDp = if (shelfIdx == 0) 4 else 8

            rack.minimumHeight = (hDp + 4).dp(ctx)
            shelfBooks.forEach { m ->
                rack.addView(coverView(m, wDp, hDp), LinearLayout.LayoutParams(wDp.dp(ctx), hDp.dp(ctx)).apply {
                    leftMargin = marginDp.dp(ctx); rightMargin = marginDp.dp(ctx)
                })
            }
            shelf.addView(rack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            // 搁板木条 (PC 版 .cab-board)
            val board = View(ctx)
            board.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF7A5A32.toInt(), 0xFF5E4525.toInt(), 0xFF45301A.toInt())).apply {
                cornerRadius = 2.dp(ctx).toFloat()
            }
            shelf.addView(board, LayoutParams(LayoutParams.MATCH_PARENT, 11.dp(ctx)).apply { topMargin = 2.dp(ctx) })
            col.addView(shelf, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        pw.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun updateCount() { countView?.text = "${page + 1} / $pages" }

    fun turn(d: Int) {
        if (isTurning) return
        showPage(((page + d) % pages + pages) % pages, d)
    }

    private fun showPage(target: Int, direction: Int, done: () -> Unit = {}) {
        val pw = pageWrap ?: return
        pw.animate().cancel()
        if (target == page || !motionEnabled(ctx)) {
            page = target; renderPage(); updateCount()
            pw.rotationY = 0f; pw.alpha = 1f; isTurning = false
            done(); return
        }
        isTurning = true
        pw.pivotX = if (direction > 0) 0f else pw.width.toFloat()
        pw.cameraDistance = 1800f.dp(ctx)
        pw.animate().rotationY(if (direction > 0) -66f else 66f).alpha(0f).setDuration(150)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                page = target; renderPage(); updateCount()
                pw.rotationY = if (direction > 0) 66f else -66f
                pw.animate().rotationY(0f).alpha(1f).setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.8f)).withEndAction {
                        isTurning = false; done()
                    }.start()
            }.start()
    }

    override fun focusBook(id: String) {
        val index = act.books.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = index / PER
        showPage(target, if (target >= page) 1 else -1) {
            pageWrap?.findViewWithTag<View>(id)?.let { book ->
                book.bringToFront()
                if (motionEnabled(ctx)) {
                    book.translationY = -14f.dp(ctx)
                    book.scaleX = 1.06f; book.scaleY = 1.06f
                    book.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(280).start()
                }
                book.announceForAccessibility(act.books[index].title)
            }
        }
    }

    override fun onDetachedFromWindow() {
        pageWrap?.animate()?.cancel()
        super.onDetachedFromWindow()
    }

    private fun build() {
        removeAllViews()
        // 外层木柜框架 (PC 版 .cab-view)
        val cabView = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF3A2915.toInt(), 0xFF453117.toInt(), 0xFF3A2915.toInt())).apply {
                cornerRadius = 8.dp(ctx).toFloat()
                setStroke(6.dp(ctx), 0xFF6B4E2A.toInt())
            }
            setPadding(4.dp(ctx), 8.dp(ctx), 4.dp(ctx), 4.dp(ctx))
        }
        val pw = FrameLayout(ctx)
        pageWrap = pw
        cabView.addView(pw, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(cabView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        renderPage()

        // 翻架导航脚 (PC 版 .cab-foot)
        val foot = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        countView = TextView(ctx).apply {
            textSize = 12f
            setTypeface(Typeface.SERIF)
            setTextColor(0xFFF5E6C8.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12.dp(ctx).toFloat()
                setColor(0x8C1E1408.toInt())
                setStroke(1.dp(ctx), 0x66B99C6B.toInt())
            }
            setPadding(14.dp(ctx), 5.dp(ctx), 14.dp(ctx), 5.dp(ctx))
        }
        updateCount()

        val prev = turnBtn("‹") { turn(-1) }
        val next = turnBtn("›") { turn(1) }
        foot.addView(prev)
        foot.addView(countView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 16.dp(ctx); rightMargin = 16.dp(ctx)
        })
        foot.addView(next)
        addView(foot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8.dp(ctx)
            bottomMargin = 4.dp(ctx)
        })

        // 案台与文房四宝 (PC 版 .cab-desk + ink-svg)
        val desk = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF59412A.toInt(), 0xFF48331B.toInt(), 0xFF3A2913.toInt()))
        }
        val inkView = DeskInkView(ctx)
        desk.addView(inkView, FrameLayout.LayoutParams(300.dp(ctx), 58.dp(ctx), Gravity.CENTER))
        addView(desk, LayoutParams(LayoutParams.MATCH_PARENT, 58.dp(ctx)))
    }

    private fun turnBtn(label: String, cb: () -> Unit): TextView {
        val b = TextView(ctx).apply {
            text = label; textSize = 19f; gravity = Gravity.CENTER; setTextColor(0xFFEBD7A8.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF45301A.toInt())
                setStroke(1.dp(ctx), 0xFF8A6A3C.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(48.dp(ctx), 48.dp(ctx))
            setOnClickListener { cb() }
        }
        return b
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun coverView(m: Books.Meta, wDp: Int = 80, hDp: Int = 120): View {
        val h = Books.hash(m.id + m.title)
        val w = wDp.dp(ctx); val hh = hDp.dp(ctx)
        val frame = FrameLayout(ctx)
        frame.tag = m.id
        frame.contentDescription = m.title
        frame.background = GradientDrawable().apply {
            cornerRadius = 3.dp(ctx).toFloat()
            setColor(0xFFF2E4C4.toInt())
            setStroke(1.dp(ctx), 0xFF8A6A3C.toInt())
        }
        frame.setPadding(3.dp(ctx), 3.dp(ctx), 3.dp(ctx), 3.dp(ctx))
        frame.elevation = 6f.dp(ctx)

        val inner = FrameLayout(ctx).apply {
            val c1 = Books.spineColor(h); val c2 = Books.spineColor(h ushr 5)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = 2.dp(ctx).toFloat() }
        }
        val bm = act.coverBitmap(m)
        if (bm != null) {
            inner.addView(ImageView(ctx).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } else {
            // PC 版: 竖排书名 (writing-mode: vertical-rl)，支持长标题
            val vertTitle = if (m.title.any { it in '\u4e00'..'\u9fff' })
                m.title.filterNot(Char::isWhitespace).take(if (wDp < 60) 5 else 7).map { "$it" }.joinToString("\n")
                else m.title
            inner.addView(TextView(ctx).apply {
                text = vertTitle
                textSize = if (wDp < 75) (if (m.title.length > 7) 10f else 11.5f) else (if (m.title.length > 7) 11.5f else 13f)
                setTypeface(Typeface.SERIF, Typeface.BOLD)
                setTextColor(0xFFF6F0DC.toInt())
                gravity = Gravity.CENTER
                androidx.core.widget.TextViewCompat.setLineHeight(this, ((if (wDp < 75) 14 else 17) * resources.displayMetrics.scaledDensity).toInt())
                setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        frame.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.rotation = (((h % 9) - 4) * 0.9f)
        frame.layoutParams = LinearLayout.LayoutParams(w, hh)
        frame.setOnClickListener { act.openBook(m) }
        frame.setOnLongClickListener { act.showActions(m); true }
        return frame
    }

    /** 绘制 PC 版案台文房四宝矢量剪影 (砚台、毛笔、墨锭、线装古籍) */
    class DeskInkView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val scale = width / 340f
            c.save()
            c.scale(scale, scale)

            // 1. 砚台 (inkstone)
            p.style = Paint.Style.FILL; p.color = 0xFF2E2318.toInt()
            c.drawOval(RectF(36f, 34f, 104f, 58f), p)
            p.color = 0xFF171310.toInt()
            c.drawOval(RectF(44f, 34.5f, 96f, 50.5f), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = 0xFF51402A.toInt()
            c.drawOval(RectF(36f, 34f, 104f, 58f), p)

            // 2. 毛笔与笔架 (brush & rest)
            val brush = Path().apply {
                moveTo(150f, 8f); lineTo(158f, 12f); lineTo(138f, 56f); lineTo(132f, 60f); lineTo(134f, 52f); close()
            }
            p.style = Paint.Style.FILL; p.color = 0xFF7A5A34.toInt()
            c.drawPath(brush, p)
            p.style = Paint.Style.STROKE; p.color = 0xFF4A3620.toInt()
            c.drawPath(brush, p)
            // 笔头毫毛
            p.style = Paint.Style.FILL; p.color = 0xFF14100C.toInt()
            c.drawCircle(132f, 60f, 4.5f, p)
            // 笔挂铜饰
            p.color = 0xFFB99C6B.toInt()
            c.drawRect(148f, 6f, 158f, 11f, p)

            // 3. 墨锭 (ink stick)
            p.color = 0xFF191512.toInt()
            c.drawRoundRect(RectF(205f, 40f, 235f, 52f), 2f, 2f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xFF4A423A.toInt()
            c.drawRoundRect(RectF(205f, 40f, 235f, 52f), 2f, 2f, p)

            // 4. 线装古籍 (thread-bound book)
            p.style = Paint.Style.FILL; p.color = 0xFFEDE2C8.toInt()
            c.drawRoundRect(RectF(255f, 34f, 315f, 56f), 3f, 3f, p)
            p.style = Paint.Style.STROKE; p.color = 0xFFB9A582.toInt()
            c.drawRoundRect(RectF(255f, 34f, 315f, 56f), 3f, 3f, p)
            // 订线
            p.color = 0xFFC9BA98.toInt()
            c.drawLine(262f, 34f, 262f, 56f, p)
            c.drawLine(312f, 34f, 312f, 56f, p)

            c.restore()
        }
    }
}

/* ============================================================
 * 二 · 简约 —— 底部倾斜收纳槽 + 居中对焦卡 (PC 版 .arc)
 * ============================================================ */
class MinimalShelfView(private val act: MainActivity) : FrameLayout(act), ShelfNavigator {
    private val ctx get() = context
    private var cur = 0
    private val cards = mutableListOf<FrameLayout>()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDragging = false
    private var dragX = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatDirection = 0
    private var repeatCount = 0
    private var held = false
    private lateinit var positionLabel: TextView
    private val repeatStep = object : Runnable {
        override fun run() {
            if (repeatDirection == 0 || !isAttachedToWindow || !isShown) return
            held = true
            goTo(cur + repeatDirection)
            repeatCount++
            repeatHandler.postDelayed(this, if (repeatCount > 6) 95 else 160)
        }
    }

    init { setup() }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                isDragging = false
                dragX = 0f
                velocityTracker?.clear()
                velocityTracker = velocityTracker ?: VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.x - touchDownX
                val dy = ev.y - touchDownY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    isDragging = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                isDragging = false
                dragX = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (!isDragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    isDragging = true
                }
                if (isDragging) {
                    dragX = dx
                    layout(false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val threshold = 70.dp(ctx)
                    if (abs(dragX) > threshold || abs(vx) > 800f) {
                        val dir = if (dragX != 0f) { if (dragX < 0) 1 else -1 } else { if (vx < 0) 1 else -1 }
                        goTo(cur + dir)
                    } else {
                        dragX = 0f
                        layout(true)
                    }
                }
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                dragX = 0f
                layout(true)
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return true
    }

    private fun setup() {
        setBackgroundColor(0xFFF4F5F7.toInt())
        if (act.books.isEmpty()) {
            addView(TextView(ctx).apply {
                text = "0本"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(0xFF888888.toInt())
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return
        }

        // 卡片容器
        act.books.forEachIndexed { i, m ->
            val c = buildCard(m, i)
            cards.add(c)
            addView(c, LayoutParams(minOf(244.dp(ctx), resources.displayMetrics.widthPixels - 120.dp(ctx)), 330.dp(ctx), Gravity.CENTER))
        }

        // 底部倾斜收纳槽 (PC 版 .arc-tray)
        val tray = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14.dp(ctx).toFloat()
                setColor(0x0C181C24.toInt())
                setStroke(1.dp(ctx), 0x14181C24.toInt())
            }
            elevation = 1f.dp(ctx)
        }
        addView(tray, LayoutParams(LayoutParams.MATCH_PARENT, 96.dp(ctx), Gravity.BOTTOM).apply {
            leftMargin = 14.dp(ctx); rightMargin = 14.dp(ctx); bottomMargin = 48.dp(ctx)
        })

        positionLabel = TextView(ctx).apply {
            textSize = 12f; setTextColor(0x8822252B.toInt()); gravity = Gravity.CENTER
            elevation = 100f.dp(ctx)
            setPadding(12.dp(ctx), 0, 12.dp(ctx), 0)
            background = GradientDrawable().apply { cornerRadius = 22f.dp(ctx); setColor(0xBFF4F5F7.toInt()) }
        }
        addView(positionLabel, LayoutParams(-2, 44.dp(ctx), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 8.dp(ctx)
        })
        addSwitchButton(-1)
        addSwitchButton(1)

        post { layout(false); postDelayed({ layout(true) }, 60) }
    }

    private fun buildCard(m: Books.Meta, i: Int): FrameLayout {
        val c = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 6.dp(ctx).toFloat()
                setColor(Color.WHITE)
                setStroke(1.dp(ctx), 0x0F181C24.toInt())
            }
            elevation = 14f.dp(ctx)
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(ctx), 18.dp(ctx), 18.dp(ctx), 18.dp(ctx))
        }
        val h = Books.hash(m.id + m.title)
        val chip = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 3.dp(ctx).toFloat()
                setColor(Books.spineColor(h))
            }
        }
        inner.addView(chip, LinearLayout.LayoutParams(44.dp(ctx), 4.dp(ctx)))
        inner.addView(TextView(ctx).apply {
            text = "%02d".format(i + 1); textSize = 10f; letterSpacing = 0.34f; setTextColor(0x8022252B.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp(ctx) })

        val cover = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Books.spineColor(h), Books.spineColor(h ushr 5))).apply { cornerRadius = 3.dp(ctx).toFloat() }
        }
        act.coverBitmap(m)?.let { bm ->
            cover.addView(ImageView(ctx).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } ?: cover.addView(TextView(ctx).apply {
            text = m.title.take(6); textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(0xF2FFFFFF.toInt()); gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setPadding(0, 0, 0, 8.dp(ctx))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        inner.addView(cover, LinearLayout.LayoutParams(96.dp(ctx), 144.dp(ctx)).apply { topMargin = 16.dp(ctx) })

        inner.addView(TextView(ctx).apply {
            text = m.title; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1A1D22.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 18.dp(ctx) })
        c.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        c.contentDescription = m.title
        c.tag = m.id
        c.setOnClickListener {
            val idx = cards.indexOf(c)
            if (idx != cur) goTo(idx) else act.openBook(m)
        }
        c.setOnLongClickListener { act.showActions(m); true }
        return c
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addSwitchButton(direction: Int) {
        val button = TextView(ctx).apply {
            text = if (direction < 0) "‹" else "›"
            textSize = 28f; gravity = Gravity.CENTER; setTextColor(0xB022252B.toInt())
            contentDescription = if (direction < 0) "上一本" else "下一本"
            elevation = 100f.dp(ctx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xC8FFFFFF.toInt()); setStroke(1.dp(ctx), 0x1822252B)
            }
            setOnClickListener { goTo(cur + direction) }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        stopRepeating(); held = false; repeatCount = 0; repeatDirection = direction
                        view.isPressed = true
                        repeatHandler.postDelayed(repeatStep, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_UP -> {
                        stopRepeating(); view.isPressed = false
                        if (!held) view.performClick()
                        held = false; parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stopRepeating(); held = false; view.isPressed = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_MOVE -> if (event.x < -slop || event.x > view.width + slop || event.y < -slop || event.y > view.height + slop) {
                        stopRepeating(); held = true; view.isPressed = false
                    }
                }
                true
            }
        }
        addView(button, LayoutParams(48.dp(ctx), 64.dp(ctx), Gravity.CENTER_VERTICAL or if (direction < 0) Gravity.START else Gravity.END).apply {
            leftMargin = 8.dp(ctx); rightMargin = 8.dp(ctx)
        })
    }

    private fun stopRepeating() { repeatDirection = 0; repeatHandler.removeCallbacks(repeatStep) }

    override fun onDetachedFromWindow() {
        stopRepeating()
        cards.forEach { it.animate().cancel() }
        velocityTracker?.recycle(); velocityTracker = null
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopRepeating()
    }

    override fun focusBook(id: String) {
        stopRepeating()
        val index = act.books.indexOfFirst { it.id == id }
        if (index >= 0) {
            val target = cards[index]
            if (target.visibility != View.VISIBLE) {
                target.translationY = height * 0.31f
                target.translationX = width * 0.18f
                target.rotation = 12f
            }
            goTo(index)
            target.announceForAccessibility(act.books[index].title)
        }
    }

    fun goTo(i: Int) {
        val n = act.books.size
        if (n == 0) return
        cur = ((i % n) + n) % n
        dragX = 0f
        layout(true)
    }

    private fun layout(anim: Boolean = true) {
        val n = cards.size
        if (::positionLabel.isInitialized) positionLabel.text = "${cur + 1} / $n"
        val sink = minOf(height * 0.31f, (height * 0.5f - 165f.dp(ctx) - 60f.dp(ctx)).coerceAtLeast(0f))
        cards.forEachIndexed { i, c ->
            c.animate().cancel()
            var o = (i - cur + n) % n
            if (o > n / 2) o -= n
            c.visibility = if (abs(o) <= 4) View.VISIBLE else View.INVISIBLE
            c.importantForAccessibility = if (o == 0) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (abs(o) > 4) return@forEachIndexed
            if (o == 0) {
                val targetX = dragX
                val targetRot = dragX * 0.03f
                if (anim && motionEnabled(ctx)) {
                    c.animate().translationX(0f).translationY(0f).rotation(0f).scaleX(1f).scaleY(1f)
                        .setDuration(if (held) 100 else 240).setInterpolator(DecelerateInterpolator(1.8f)).start()
                } else {
                    c.translationX = targetX
                    c.translationY = 0f
                    c.rotation = targetRot
                }
                c.translationZ = 60f
                c.alpha = 1f
            } else {
                val dir = if (o > 0) 1 else -1
                val ao = abs(o)
                val shift = (86 + min(ao - 1, 3) * 62).dp(ctx).toFloat()
                val targetX = dir * shift + dragX * 0.25f
                val tilt = dir * (8 + min(ao, 4) * 2.2f)
                if (anim && motionEnabled(ctx)) {
                    c.animate().translationX(targetX).translationY(sink)
                        .rotation(tilt).scaleX(1f).scaleY(1f)
                        .setDuration(if (held) 100 else 240).setInterpolator(DecelerateInterpolator(1.8f)).start()
                } else {
                    c.translationX = targetX
                    c.translationY = sink
                    c.rotation = tilt
                }
                c.translationZ = (40 - min(ao, 8)).toFloat()
                c.alpha = maxOf(0.4f, 1f - (ao - 1) * 0.2f)
            }
        }
    }
}

/* ============================================================
 * 三 · 收藏馆 —— 3D 主体视角 90° 旋转展厅（东南西北各一展柜 · 侧旁浅淡打光 · 左右到顶两端阻尼）
 * ============================================================ */
class GalleryShelfView(private val act: MainActivity) : FrameLayout(act), ShelfNavigator {
    private val ctx get() = context
    private var cur = 0
    private var cameraYaw = 0f
    private var elasticYaw = 0f
    private val displayYaw get() = cameraYaw + elasticYaw
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownYaw = 0f
    private var isDragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var animator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private lateinit var roomWorld: GalleryRoomView
    private val bayViews = mutableListOf<BayItem>()
    private lateinit var leftSpotlight: View
    private lateinit var rightSpotlight: View
    private lateinit var centerSpotlight: GallerySpotlightView
    private lateinit var nextSpotlight: GallerySpotlightView

    private class BayItem(val container: FrameLayout, val bookView: View, val meta: Books.Meta, val index: Int) {
        val projection = Matrix()
        val inverse = Matrix()
        var visible = false
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isDragging) return false
            hitBay(e.x, e.y)?.container?.performClick()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!isDragging) hitBay(e.x, e.y)?.container?.performLongClick()
        }
    })

    init { setup() }

    // Child hit testing must use the same perspective matrix as drawing.
    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    private fun hitBay(x: Float, y: Float): BayItem? = bayViews.firstOrNull { bay ->
        if (!bay.visible) false else {
            val point = floatArrayOf(x, y)
            bay.inverse.mapPoints(point)
            point[0] in 0f..bay.container.width.toFloat() && point[1] in 0f..bay.container.height.toFloat()
        }
    }

    private fun setDisplayYaw(yaw: Float) {
        cameraYaw = GalleryCamera.clampYaw(yaw, bayViews.size)
        elasticYaw = yaw - cameraYaw
        applyCameraYaw()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bayViews.isEmpty()) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animate().cancel()
            alpha = 1f
            animator?.cancel()
            animator = null
            activePointerId = event.getPointerId(0)
            touchDownX = event.x
            touchDownY = event.y
            touchDownYaw = GalleryCamera.undamp(displayYaw, bayViews.size)
            isDragging = false
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val pointer = event.findPointerIndex(activePointerId)
                if (pointer < 0) return true
                val dx = event.getX(pointer) - touchDownX
                val dy = event.getY(pointer) - touchDownY
                if (!isDragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDragging) {
                    val rawYaw = touchDownYaw - dx * 90f / (width * 0.78f).coerceAtLeast(1f)
                    setDisplayYaw(GalleryCamera.damp(rawYaw, bayViews.size))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val next = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(next)
                    touchDownX = event.getX(next)
                    touchDownY = event.getY(next)
                    touchDownYaw = GalleryCamera.undamp(displayYaw, bayViews.size)
                    velocityTracker?.clear()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.getXVelocity(activePointerId) ?: 0f
                    snapTo(GalleryCamera.releaseTarget(displayYaw, vx,
                        500f * resources.displayMetrics.density, bayViews.size))
                } else if (animator?.isRunning != true) {
                    snapTo(GalleryCamera.nearest(cameraYaw, bayViews.size))
                }
                endGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                snapTo(GalleryCamera.nearest(cameraYaw, bayViews.size))
                endGesture()
                return true
            }
        }
        return true
    }

    private fun endGesture() {
        isDragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        velocityTracker?.recycle()
        velocityTracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun setup() {
        setBackgroundColor(0xFF100B06.toInt())
        if (act.books.isEmpty()) {
            addView(TextView(ctx).apply {
                text = "0本"; gravity = Gravity.CENTER; textSize = 14f; setTypeface(Typeface.SERIF); setTextColor(0xFFC9A25E.toInt())
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return
        }

        // 1. 地坪与中心反光 (PC 版 .gal-floor)
        val floorH = (resources.displayMetrics.heightPixels * 0.28f).toInt()
        val floor = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF1D140C.toInt(), 0xFF0D0805.toInt()))
        }
        addView(floor, LayoutParams(LayoutParams.MATCH_PARENT, floorH, Gravity.BOTTOM))

        // 2. 侧旁浅淡打光与中央主聚光灯束 (PC 版 .gal-halo / .gal-beam)
        centerSpotlight = GallerySpotlightView(ctx)
        addView(centerSpotlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Two adjacent ceiling lights blend through a turn without jumping across the screen.
        nextSpotlight = GallerySpotlightView(ctx).apply { alpha = 0f }
        addView(nextSpotlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 左侧展柜浅淡侧打光
        leftSpotlight = GallerySideLightView(ctx, false).apply { alpha = 0f }
        addView(leftSpotlight, LayoutParams(90.dp(ctx), LayoutParams.MATCH_PARENT, Gravity.START))

        // 右侧展柜浅淡侧打光
        rightSpotlight = GallerySideLightView(ctx, true).apply { alpha = 0f }
        addView(rightSpotlight, LayoutParams(90.dp(ctx), LayoutParams.MATCH_PARENT, Gravity.END))

        // 3. 3D 展厅容器
        roomWorld = GalleryRoomView(ctx)
        addView(roomWorld, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 构建各展柜 (东南西北各有一个展柜，每个间隔 90°)
        bayViews.clear()
        act.books.forEachIndexed { i, m ->
            val h = Books.hash(m.id)
            val bay = FrameLayout(ctx).apply {
                layoutParams = LayoutParams(240.dp(ctx), 360.dp(ctx), Gravity.CENTER).apply {
                    topMargin = 12.dp(ctx)
                }
                clipChildren = false
            }

            // (1) 六边形大理石展台 (基座)
            val pedestal = View(ctx).apply {
                background = HexPedestalDrawable(ctx)
                elevation = 14f.dp(ctx)
            }
            bay.addView(pedestal, LayoutParams(220.dp(ctx), 70.dp(ctx), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = 30.dp(ctx)
            })

            // (2) 静置的典藏书籍，视角变化仅由摄像机产生
            val book = FrameLayout(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Books.spineColor(h), Books.spineColor(h ushr 5))).apply { cornerRadius = 4.dp(ctx).toFloat() }
                elevation = 16f.dp(ctx)
            }
            act.coverBitmap(m)?.let { bmp ->
                book.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } ?: run {
                book.addView(TextView(ctx).apply {
                    text = m.title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.SERIF, Typeface.BOLD)
                    setPadding(12.dp(ctx), 16.dp(ctx), 12.dp(ctx), 16.dp(ctx))
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            bay.addView(book, LayoutParams(114.dp(ctx), 164.dp(ctx), Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 96.dp(ctx)
            })

            // (3) 高透防尘水晶罩
            val glass = View(ctx).apply {
                background = GlassCaseDrawable(ctx)
                elevation = 20f.dp(ctx)
            }
            bay.addView(glass, LayoutParams(174.dp(ctx), 216.dp(ctx), Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 68.dp(ctx)
            })

            // (4) 黄铜典藏铭牌 (NO.00X / 珍藏)
            val plaque = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 2.dp(ctx).toFloat()
                    setColor(0xFF281C0E.toInt())
                    setStroke(1.dp(ctx), 0xFFC9A25E.toInt())
                }
                setPadding(12.dp(ctx), 4.dp(ctx), 12.dp(ctx), 4.dp(ctx))
                elevation = 18f.dp(ctx)
            }
            plaque.addView(TextView(ctx).apply {
                val num = String.format("%03d", i + 1)
                text = num
                textSize = 9.5f
                letterSpacing = 0.15f
                setTextColor(0xFFD6B575.toInt())
                gravity = Gravity.CENTER
            })
            plaque.addView(TextView(ctx).apply {
                text = m.title
                textSize = 12f
                setTypeface(Typeface.SERIF, Typeface.BOLD)
                setTextColor(0xFFF3E7CA.toInt())
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            bay.addView(plaque, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = 6.dp(ctx)
            })

            bay.setOnClickListener {
                if (abs(displayYaw - i * 90f) < 0.5f) act.openBook(m) else snapTo(i)
            }
            bay.setOnLongClickListener {
                if (abs(displayYaw - i * 90f) < 0.5f) { act.showActions(m); true } else false
            }
            bay.contentDescription = "${m.title}，第 ${i + 1} 件藏品，共 ${act.books.size} 件"

            roomWorld.addView(bay)
            bayViews.add(BayItem(bay, book, m, i))
        }

        // 书籍静态端庄展示，禁止恒定自转
        bayViews.forEach { b ->
            b.bookView.rotationY = 0f
        }

        cameraYaw = (cur * 90f)
        post { applyCameraYaw() }
    }

    override fun focusBook(id: String) {
        val index = bayViews.indexOfFirst { it.meta.id == id }
        if (index >= 0) {
            animate().cancel()
            animator?.cancel()
            val distance = index * 90f - cameraYaw
            if (abs(distance) > 180f && motionEnabled(ctx)) {
                // A distant search crosses a dark doorway, then turns onto the target wall.
                // Avoid racing through dozens of full rotations in a fraction of a second.
                animate().alpha(0.1f).setDuration(120).withEndAction {
                    setDisplayYaw(index * 90f - if (distance > 0) 90f else -90f)
                    snapTo(index)
                    animate().alpha(1f).setDuration(280).start()
                }.start()
            } else {
                alpha = 1f
                snapTo(index)
            }
            announceForAccessibility(bayViews[index].meta.title)
        }
    }

    private fun snapTo(targetIdx: Int) {
        val n = bayViews.size
        if (n == 0) return
        val target = targetIdx.coerceIn(0, n - 1)
        cur = target
        val targetYaw = target * 90f
        animator?.cancel()
        if (abs(displayYaw - targetYaw) < 0.01f ||
            android.provider.Settings.Global.getFloat(ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
            setDisplayYaw(targetYaw)
            return
        }
        animator = ValueAnimator.ofFloat(displayYaw, targetYaw).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                setDisplayYaw(it.animatedValue as Float)
            }
            start()
        }
    }

    /** Each bay is a fixed wall plane. Rotate the camera, then perspective-project its corners. */
    private fun applyCameraYaw() {
        if (!::roomWorld.isInitialized || width == 0 || height == 0) return
        val fit = minOf(1f, width / 280f.dp(ctx), height / 440f.dp(ctx))
        var leftLight = 0f
        var rightLight = 0f
        centerSpotlight.alpha = 0f
        nextSpotlight.alpha = 0f
        bayViews.forEach { bay ->
            val relAngle = bay.index * 90f - displayYaw
            leftLight = maxOf(leftLight, GalleryCamera.sideLight(relAngle, false))
            rightLight = maxOf(rightLight, GalleryCamera.sideLight(relAngle, true))
            val bw = bay.container.width.toFloat()
            val bh = bay.container.height.toFloat()
            val corners = GalleryCamera.project(relAngle, width.toFloat(), height.toFloat(), bw, bh, fit, 6f.dp(ctx))
            bay.visible = corners != null && bay.projection.setPolyToPoly(
                floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, corners, 0, 4) &&
                bay.projection.invert(bay.inverse)
            // INVISIBLE preserves measured dimensions for the next camera frame.
            bay.container.visibility = if (bay.visible) View.VISIBLE else View.INVISIBLE
            val aligned = abs(relAngle) < 0.5f
            bay.container.importantForAccessibility = if (aligned) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (bay.visible) {
                val intensity = (1f - abs(relAngle) / 55f).coerceIn(0f, 1f)
                val light = if (bay.index % 2 == 0) centerSpotlight else nextSpotlight
                light.alpha = intensity
                light.translationX = GalleryCamera.focalLength(width.toFloat()) * tan(Math.toRadians(relAngle.toDouble())).toFloat()
            }
        }
        leftSpotlight.alpha = leftLight
        rightSpotlight.alpha = rightLight
        roomWorld.invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val index = GalleryCamera.nearest(cameraYaw, bayViews.size)
        info.isScrollable = bayViews.size > 1
        if (index > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (index < bayViews.lastIndex) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val index = GalleryCamera.nearest(cameraYaw, bayViews.size)
        val target = when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> index + 1
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> index - 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (target !in bayViews.indices) return false
        snapTo(target)
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyCameraYaw()
    }

    /** Render existing native book/glass/plaque views through their full projective matrices. */
    private inner class GalleryRoomView(ctx: Context) : FrameLayout(ctx) {
        private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x24C9A25E
            strokeWidth = 1f.dp(ctx)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val checkpoint = canvas.save()
            canvas.clipRect(0, 0, width, height)
            // Room corners stay fixed in world space and travel with the same camera yaw.
            val nearestWall = GalleryCamera.nearest(cameraYaw, bayViews.size)
            val focal = GalleryCamera.focalLength(width.toFloat())
            for (wall in nearestWall - 1..nearestWall + 1) {
                val angle = wall * 90f + 45f - displayYaw
                if (abs(angle) < 80f) {
                    val radians = Math.toRadians(angle.toDouble())
                    val x = width * 0.5f + focal * tan(radians).toFloat()
                    if (x in 0f..width.toFloat()) {
                        val depthScale = (1f / (1.4142f * cos(radians).toFloat()))
                        val ceiling = height * 0.5f - height * 0.40f * depthScale
                        val floor = height * 0.5f + height * 0.28f * depthScale
                        canvas.drawLine(x, ceiling, x, floor, seamPaint)
                        canvas.drawLine(x, floor, width * 0.5f + (x - width * 0.5f) * 2.2f,
                            height.toFloat(), seamPaint)
                    }
                }
            }
            bayViews.filter { it.visible }.sortedByDescending { abs(it.index * 90f - displayYaw) }.forEach { bay ->
                val save = canvas.save()
                canvas.concat(bay.projection)
                bay.container.draw(canvas)
                canvas.restoreToCount(save)
            }
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animate().cancel()
        animator?.cancel()
        endGesture()
    }

    private class GallerySideLightView(ctx: Context, private val right: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w <= 0 || h <= 0) return
            paint.shader = RadialGradient(if (right) w * 1.1f else -w * 0.1f, w * 0.75f, w * 1.25f,
                intArrayOf(0x60FFD18A, 0x20E8AF55, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            if (width == 0) return
            val save = canvas.save()
            canvas.scale(1f, height / (width * 2f))
            canvas.drawRect(0f, 0f, width.toFloat(), width * 2f, paint)
            canvas.restoreToCount(save)
        }
    }

    /** 六边形大理石基座（PC 版 .gal-pedestal） */
    class HexPedestalDrawable(private val ctx: Context) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            val path = Path()
            path.moveTo(w * 0.09f, 0f)
            path.lineTo(w * 0.91f, 0f)
            path.lineTo(w, h * 0.40f)
            path.lineTo(w * 0.88f, h)
            path.lineTo(w * 0.12f, h)
            path.lineTo(0f, h * 0.40f)
            path.close()

            p.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xFF524024.toInt(), 0xFF362812.toInt(), 0xFF241908.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawPath(path, p)

            lineP.shader = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT, 0x6BFFE4A8.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            lineP.strokeWidth = 3f * ctx.resources.displayMetrics.density
            lineP.style = Paint.Style.STROKE
            canvas.drawLine(w * 0.09f, 0f, w * 0.91f, 0f, lineP)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 高透防尘水晶罩 + 9度斜向反射流动光 (PC 版 .gal-case) */
    class GlassCaseDrawable(private val ctx: Context) : Drawable() {
        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * ctx.resources.displayMetrics.density
            color = 0x55FFE4A8.toInt()
        }
        private val frameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * ctx.resources.displayMetrics.density
            color = 0x88D4AF37.toInt()
        }
        private val rimP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val streakP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val facetP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glintP = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val d = ctx.resources.displayMetrics.density

            val topH = 20f * d     // 顶面透视深度
            val baseH = 14f * d    // 底座金属包边高度
            val insetX = 14f * d   // 顶面透视收缩边距

            // 1. 顶面梯形水晶顶盖 (Top Glass Facet)
            val topPath = Path().apply {
                moveTo(insetX, 2f * d)
                lineTo(w - insetX, 2f * d)
                lineTo(w - 2f * d, topH)
                lineTo(2f * d, topH)
                close()
            }
            facetP.shader = LinearGradient(0f, 0f, 0f, topH,
                intArrayOf(0x35FFF9E6.toInt(), 0x18FFF0CC.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawPath(topPath, facetP)
            canvas.drawPath(topPath, strokeP)

            // 2. 左侧侧面玻璃折角 (Left Glass Facet)
            val leftSidePath = Path().apply {
                moveTo(insetX, 2f * d)
                lineTo(2f * d, topH)
                lineTo(2f * d, h - baseH)
                lineTo(insetX, h - baseH - 4f * d)
                close()
            }
            facetP.shader = LinearGradient(0f, 0f, insetX, 0f,
                intArrayOf(0x286E542C.toInt(), 0x12FFE0A0.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawPath(leftSidePath, facetP)
            canvas.drawPath(leftSidePath, strokeP)

            // 3. 右侧侧面玻璃折角 (Right Glass Facet)
            val rightSidePath = Path().apply {
                moveTo(w - insetX, 2f * d)
                lineTo(w - 2f * d, topH)
                lineTo(w - 2f * d, h - baseH)
                lineTo(w - insetX, h - baseH - 4f * d)
                close()
            }
            facetP.shader = LinearGradient(w - insetX, 0f, w, 0f,
                intArrayOf(0x12FFE0A0.toInt(), 0x286E542C.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawPath(rightSidePath, facetP)
            canvas.drawPath(rightSidePath, strokeP)

            // 4. 正面通透水晶主面 (Front Main Glass Panel)
            val frontRect = RectF(2f * d, topH, w - 2f * d, h - baseH)
            bgP.shader = LinearGradient(0f, topH, w, h - baseH,
                intArrayOf(0x1AFFF6DC.toInt(), 0x0AFFF0C8.toInt(), 0x128C6834.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(frontRect, bgP)
            canvas.drawRect(frontRect, strokeP)

            // 5. 底座金属包边卡槽 (Solid Brass/Bronze Mounting Base Collar)
            val baseRect = RectF(0f, h - baseH, w, h)
            rimP.shader = LinearGradient(0f, h - baseH, 0f, h,
                intArrayOf(0xFFC5A059.toInt(), 0xFF7A5424.toInt(), 0xFF422C12.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(baseRect, 3f * d, 3f * d, rimP)

            // 底座嵌槽高光亮线 (Metallic trim groove highlight)
            val trimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFBE4A0.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * d
            }
            canvas.drawLine(3f * d, h - baseH + 2f * d, w - 3f * d, h - baseH + 2f * d, trimP)
            canvas.drawLine(0f, h - baseH, w, h - baseH, frameP)

            // 6. 双道斜向水晶流光掠影 (Dual Diagonal Specular Streaks)
            canvas.save()
            canvas.clipRect(2f * d, 2f * d, w - 2f * d, h - baseH)
            canvas.rotate(14f, w * 0.35f, h * 0.45f)
            streakP.shader = LinearGradient(w * 0.15f, 0f, w * 0.42f, 0f,
                intArrayOf(Color.TRANSPARENT, 0x2EFFFDF0.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(w * 0.12f, -h * 0.6f, w * 0.45f, h * 1.6f, streakP)
            streakP.shader = LinearGradient(w * 0.52f, 0f, w * 0.64f, 0f,
                intArrayOf(Color.TRANSPARENT, 0x1CFFFDF0.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(w * 0.50f, -h * 0.6f, w * 0.66f, h * 1.6f, streakP)
            canvas.restore()

            // 7. 水晶顶角聚光星芒 (Corner Crystal Glints)
            glintP.color = 0xAAFFF7D9.toInt()
            glintP.style = Paint.Style.FILL
            canvas.drawCircle(2f * d, topH, 2.5f * d, glintP)
            canvas.drawCircle(w - 2f * d, topH, 2.5f * d, glintP)
            canvas.drawCircle(insetX, 2f * d, 2f * d, glintP)
            canvas.drawCircle(w - insetX, 2f * d, 2f * d, glintP)
        }
        override fun setAlpha(a: Int) { bgP.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 绘制聚光灯梯形光束与动态微光晕 (PC 版 .gal-beam / .gal-halo / .gal-dim) */
    class GallerySpotlightView(ctx: Context) : View(ctx) {
        private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val beamPath = Path()
        private var breathAlpha = 1.0f
        private val animator = ValueAnimator.ofFloat(0.80f, 1.0f).apply {
            duration = 5500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                breathAlpha = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) animator.start()
        }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // 顶部大光晕
            haloPaint.shader = RadialGradient(w * 0.5f, h * 0.20f, w * 0.55f,
                0x24FFE0A0.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h * 0.70f, haloPaint)

            // 下降梯形聚光束
            beamPath.reset()
            val topW = 90f.dp(context)
            val botW = 230f.dp(context)
            val beamH = h * 0.62f
            beamPath.moveTo(w * 0.5f - topW / 2, 0f)
            beamPath.lineTo(w * 0.5f + topW / 2, 0f)
            beamPath.lineTo(w * 0.5f + botW / 2, beamH)
            beamPath.lineTo(w * 0.5f - botW / 2, beamH)
            beamPath.close()

            val a1 = (0x56 * breathAlpha).toInt().coerceIn(0, 255)
            val a2 = (0x20 * breathAlpha).toInt().coerceIn(0, 255)
            val c1 = (a1 shl 24) or 0x00FFE6AC
            val c2 = (a2 shl 24) or 0x00FFE6AC
            beamPaint.shader = LinearGradient(0f, 0f, 0f, beamH,
                intArrayOf(c1, c2, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawPath(beamPath, beamPaint)
        }
    }
}

