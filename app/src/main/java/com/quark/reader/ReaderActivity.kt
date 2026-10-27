package com.quark.reader

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReaderActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private val FONT_VALUES = intArrayOf(42, 46, 50, 54, 58, 62, 66, 70, 74, 78, 82, 86)
    }

    private lateinit var prefs: Prefs
    private var handle: Books.Handle? = null
    private var metaChapter = 0
    private var curCi = 0

    // 视图
    private lateinit var root: FrameLayout
    private lateinit var scrollReader: ScrollReaderView
    private lateinit var pagedReader: PagedView
    private lateinit var viewport: FrameLayout
    private lateinit var topTitle: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvProgress: TextView
    private lateinit var batteryIcon: BatteryIcon
    private lateinit var bottomUi: LinearLayout
    private lateinit var chapNav: LinearLayout
    private lateinit var settingsPanelWrap: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var chapterList: RecyclerView
    private lateinit var moreSheet: LinearLayout
    private lateinit var moreMask: View
    private lateinit var subSheet: LinearLayout
    private lateinit var subMask: View
    private lateinit var subTitle: TextView
    private lateinit var subBody: LinearLayout
    private lateinit var autoHud: LinearLayout
    private lateinit var pill: PillBar
    private lateinit var drawerTitle: TextView
    private lateinit var cover: TextView
    private lateinit var nightIcon: ImageView
    private lateinit var nightLabel: TextView

    // 记事本式查找栏
    private lateinit var findBar: LinearLayout
    private lateinit var etFindKeyword: EditText
    private lateinit var btnFindNext: TextView
    private lateinit var btnFindClose: ImageButton
    private var findBarOpen = false
    private var lastSearchKeyword = ""
    private var lastSearchChapter = -1
    private var lastSearchOffset = -1

    private var menuOpen = false
    private var settingsOpen = false
    private var drawerOpen = false
    private var moreOpen = false
    private var subOpen = false
    private var drawerDesc = false
    private var chapterAdapter: ChapterAdapter? = null

    // 自动阅读
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoOn = false
    private var autoPaused = false
    private var autoSpeed = 1f
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoOn || autoPaused) return
            if (prefs.pageMode == "scroll") scrollReader.autoScrollStep(16, autoSpeed)
            mainHandler.postDelayed(this, 16)
        }
    }
    private val autoPaged = object : Runnable {
        override fun run() {
            if (autoOn && !autoPaused && prefs.pageMode != "scroll") pagedReader.turn(true)
            if (autoOn) mainHandler.postDelayed(this, (5200 / autoSpeed).toLong())
        }
    }

    // 时钟/电池
    private val clockTick = object : Runnable {
        override fun run() {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            tvClock.text = t
            mainHandler.postDelayed(this, 15000)
        }
    }
    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 93) ?: 93
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            batteryIcon.level = lvl.toFloat() / scale
        }
    }
    private var fontNumViews: MutableList<TextView> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        ThemeSpec.refreshCustomThemes(this)
        setContentView(R.layout.activity_reader)
        bindViews()
        SafeSystemBars.install(this, root)
        applyTheme()
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
            ?: intent.data?.let { uri -> importFromUri(uri) }
        if (bookId == null) { finish(); return }
        openBook(bookId)
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        mainHandler.post(clockTick)
        applyKeepScreen()
    }

    private fun importFromUri(uri: android.net.Uri): String? {
        val name = queryName(uri) ?: "book.txt"
        val meta = if (name.endsWith(".epub", true))
            Books.importEpub(this, uri, name, null)
        else Books.importTxt(this, uri, name, object : Books.ImportCallback {
            override fun onHeadReady(id: String) {}
            override fun onDone(id: String, ok: Boolean) { runOnUiThread { openBook(id) } }
        })
        return meta.id
    }

    private fun queryName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    private fun bindViews() {
        root = findViewById(R.id.root)
        viewport = findViewById(R.id.viewport)
        scrollReader = findViewById(R.id.scrollReader)
        pagedReader = findViewById(R.id.pagedReader)
        topTitle = findViewById(R.id.topTitle)
        tvClock = findViewById(R.id.tvClock)
        tvProgress = findViewById(R.id.tvProgress)
        batteryIcon = findViewById(R.id.batteryIcon)
        bottomUi = findViewById(R.id.bottomUi)
        chapNav = findViewById(R.id.chapNav)
        settingsPanelWrap = findViewById(R.id.settingsPanelWrap)
        drawer = findViewById(R.id.drawer)
        drawerMask = findViewById(R.id.drawerMask)
        chapterList = findViewById(R.id.chapterList)
        moreSheet = findViewById(R.id.moreSheet)
        moreMask = findViewById(R.id.moreMask)
        subSheet = findViewById(R.id.subSheet)
        subMask = findViewById(R.id.subMask)
        subTitle = findViewById(R.id.subTitle)
        subBody = findViewById(R.id.subBody)
        autoHud = findViewById(R.id.autoHud)
        pill = findViewById(R.id.pill)
        drawerTitle = findViewById(R.id.drawerTitle)
        cover = findViewById(R.id.cover)
        nightIcon = findViewById(R.id.nightIcon)
        nightLabel = findViewById(R.id.nightLabel)

        findBar = findViewById(R.id.findBar)
        etFindKeyword = findViewById(R.id.etFindKeyword)
        btnFindNext = findViewById(R.id.btnFindNext)
        btnFindClose = findViewById(R.id.btnFindClose)

        scrollReader.setTapHandler()
        scrollReader.onTapCenter = { setMenu(!menuOpen) }
        scrollReader.onSideTap = { next -> if (prefs.pageMode == "scroll") scrollReader.scrollPage(next) }
        pagedReader.onTapCenter = { setMenu(!menuOpen) }
        pagedReader.onSideTap = { next -> pagedReader.turn(next) }
        pagedReader.onChapterBoundary = { forward ->
            if (forward) gotoChapter(curCi + 1)
            else gotoChapter(curCi - 1, atEnd = true)
        }
        pagedReader.onPageChange = { _, _ -> updateBottomInfo() }
        scrollReader.onScrollRatio = { ci, _ ->
            curCi = ci
            updateBottomInfo()
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnSearchText).setOnClickListener {
            setMenu(false)
            setFindBar(true)
        }
        btnFindNext.setOnClickListener { performFindNext() }
        btnFindClose.setOnClickListener { setFindBar(false) }
        etFindKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                performFindNext()
                true
            } else false
        }

        findViewById<LinearLayout>(R.id.btnCatalog).setOnClickListener { setDrawer(true) }
        findViewById<LinearLayout>(R.id.btnNight).setOnClickListener {
            val anchor = captureCurrentAnchor()
            prefs.night = !prefs.night
            applyTheme()
            relayoutWithAnchor(anchor)
        }
        findViewById<LinearLayout>(R.id.btnSettings).setOnClickListener { setSettingsPanel(!settingsOpen) }
        findViewById<TextView>(R.id.btnPrevChap).setOnClickListener {
            val base = if (prefs.pageMode == "scroll") scrollReader.visibleChapter() else curCi
            gotoChapter(base - 1, atEnd = false)
        }
        findViewById<TextView>(R.id.btnNextChap).setOnClickListener {
            val base = if (prefs.pageMode == "scroll") scrollReader.visibleChapter() else curCi
            gotoChapter(base + 1, atEnd = false)
        }
        drawerMask.setOnClickListener { setDrawer(false) }
        findViewById<LinearLayout>(R.id.sortPill).setOnClickListener {
            drawerDesc = !drawerDesc
            chapterAdapter?.reverse(drawerDesc)
        }
        moreMask.setOnClickListener { setMore(false) }
        findViewById<ImageButton>(R.id.moreBack).setOnClickListener { setMore(false) }
        subMask.setOnClickListener { setSub(null, null) }
        findViewById<ImageButton>(R.id.subBack).setOnClickListener { setSub(null, null) }
        pill.onRelease = { p -> jumpProgress(p) }

        findViewById<TextView>(R.id.fontMinus).setOnClickListener { stepFont(-1) }
        findViewById<TextView>(R.id.fontPlus).setOnClickListener { stepFont(1) }
        findViewById<LinearLayout>(R.id.cardAuto).setOnClickListener { setAuto(!autoOn) }
        findViewById<LinearLayout>(R.id.cardMore).setOnClickListener { buildMoreSheet(); setMore(true) }
        findViewById<LinearLayout>(R.id.navFont).setOnClickListener { buildFontSheet() }
        findViewById<LinearLayout>(R.id.navBg).setOnClickListener { buildBgSheet() }
        findViewById<LinearLayout>(R.id.navSpacing).setOnClickListener { buildSpacingSheet() }

        // 自动 HUD
        findViewById<ImageButton>(R.id.autoPause).setOnClickListener {
            autoPaused = !autoPaused
            findViewById<ImageButton>(R.id.autoPause).setImageResource(if (autoPaused) R.drawable.ic_play else R.drawable.ic_pause)
            findViewById<ImageButton>(R.id.autoPause).setColorFilter(Color.WHITE)
        }
        findViewById<ImageButton>(R.id.autoUp).setOnClickListener { autoSpeed = (autoSpeed + 0.5f).coerceAtMost(5f); updateAutoLabel(); restartAuto() }
        findViewById<ImageButton>(R.id.autoDown).setOnClickListener { autoSpeed = (autoSpeed - 0.5f).coerceAtLeast(0.5f); updateAutoLabel(); restartAuto() }
        findViewById<ImageButton>(R.id.autoClose).setOnClickListener { setAuto(false) }
        findViewById<ImageButton>(R.id.autoPause).setColorFilter(Color.WHITE)
        findViewById<ImageButton>(R.id.autoUp).setColorFilter(Color.WHITE)
        findViewById<ImageButton>(R.id.autoDown).setColorFilter(Color.WHITE)
        findViewById<ImageButton>(R.id.autoClose).setColorFilter(Color.WHITE)

        setMenu(false)
    }

    /* ---------------- 记事本式关键词查找（查找 & 跳转下一个） ---------------- */
    private fun setFindBar(open: Boolean) {
        findBarOpen = open
        findBar.visibility = if (open) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (open) {
            applyTheme()
            etFindKeyword.requestFocus()
            etFindKeyword.selectAll()
            etFindKeyword.post { imm.showSoftInput(etFindKeyword, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            imm.hideSoftInputFromWindow(etFindKeyword.windowToken, 0)
            etFindKeyword.clearFocus()
            scrollReader.clearHighlight()
            pagedReader.clearHighlight()
        }
    }

    private fun performFindNext() {
        val h = handle ?: return
        val kw = etFindKeyword.text?.toString()?.trim().orEmpty()
        if (kw.isEmpty()) {
            Toast.makeText(this, "请输入要查找的关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val curAnchor = captureCurrentAnchor()
        val startCi: Int
        val startOffset: Int
        if (kw.equals(lastSearchKeyword, ignoreCase = true) && lastSearchChapter in 0 until h.chapters.size && lastSearchOffset >= 0) {
            startCi = lastSearchChapter
            startOffset = lastSearchOffset + kw.length
        } else {
            startCi = curAnchor.chapterIndex
            startOffset = curAnchor.primaryCharOffset.coerceAtLeast(0)
        }

        val hit = h.findNext(kw, startCi, startOffset, wrapAround = true)
        if (hit == null) {
            Toast.makeText(this, "找不到“$kw”", Toast.LENGTH_SHORT).show()
            return
        }

        lastSearchKeyword = kw
        lastSearchChapter = hit.chapterIndex
        lastSearchOffset = hit.charOffsetInChapter
        curCi = hit.chapterIndex

        val hlRange = hit.charOffsetInChapter until (hit.charOffsetInChapter + hit.matchLength)
        scrollReader.setHighlight(hit.chapterIndex, hlRange)
        pagedReader.setHighlight(hit.chapterIndex, hlRange)

        val targetAnchor = ReadingAnchor(hit.chapterIndex, hit.charOffsetInChapter, intArrayOf(hit.charOffsetInChapter))
        relayoutWithAnchor(targetAnchor)
        saveProgress()
        chapterAdapter?.highlight(hit.chapterIndex)

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etFindKeyword.windowToken, 0)
    }

    /* ---------------- 打开书籍 / 恢复位置 ---------------- */
    @SuppressLint("NotifyDataSetChanged")
    private fun openBook(bookId: String) {
        val h = Books.open(this, bookId)
        if (h == null || h.chapters.isEmpty()) {
            Toast.makeText(this, "书籍解析中，稍后重试…", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ tryOpenAgain(bookId) }, 1500)
            return
        }
        handle = h
        topTitle.text = h.meta.name
        drawerTitle.text = h.meta.name
        cover.text = h.title.take(8)
        curCi = h.meta.lastCi.coerceIn(0, (h.chapters.size - 1).coerceAtLeast(0))
        metaChapter = curCi
        applyReaderMode(initialOpen = true)
        chapterAdapter = ChapterAdapter(h) { ci -> gotoChapter(ci, atEnd = false); setDrawer(false) }
        chapterList.layoutManager = LinearLayoutManager(this)
        chapterList.adapter = chapterAdapter
        chapterAdapter?.reverse(drawerDesc)
    }

    private fun tryOpenAgain(bookId: String) {
        if (isFinishing) return
        val h = Books.open(this, bookId)
        if (h != null && h.chapters.isNotEmpty()) openBook(bookId)
        else if (handle == null) { Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show(); finish() }
    }

    /**
     * 获取当前画面最上面三行文字对应的基准锚点 `ReadingAnchor`
     */
    private fun captureCurrentAnchor(): ReadingAnchor {
        return if (prefs.pageMode == "scroll") {
            scrollReader.captureTopThreeLinesAnchor()
        } else {
            pagedReader.captureTopThreeLinesAnchor()
        }
    }

    private fun applyReaderMode(initialOpen: Boolean = false, anchor: ReadingAnchor? = null) {
        val h = handle ?: return
        val scroll = prefs.pageMode == "scroll"
        scrollReader.visibility = if (scroll) View.VISIBLE else View.GONE
        pagedReader.visibility = if (scroll) View.GONE else View.VISIBLE
        applyStyleToViews()

        val targetAnchor = anchor ?: if (initialOpen && h.meta.lastCharOffset >= 0) {
            ReadingAnchor(curCi, h.meta.lastCharOffset, intArrayOf(h.meta.lastCharOffset))
        } else null

        if (scroll) {
            scrollReader.handle = h
            scrollReader.tapSideEnabled = prefs.tapSidePage
            if (targetAnchor != null) {
                curCi = targetAnchor.chapterIndex
                scrollReader.openAt(targetAnchor.chapterIndex, h.meta.lastRatio, targetAnchor.primaryCharOffset)
            } else {
                scrollReader.openAt(curCi, h.meta.lastRatio)
            }
        } else {
            pagedReader.handle = h
            pagedReader.tapSideEnabled = prefs.tapSidePage
            pagedReader.animMode = prefs.pageMode // "ltr" 或 "rtl"
            if (targetAnchor != null) {
                curCi = targetAnchor.chapterIndex
                pagedReader.setChapter(targetAnchor.chapterIndex, targetAnchor.chapterIndex == metaChapter, anchor = targetAnchor)
            } else {
                pagedReader.setChapter(curCi, curCi == metaChapter)
                pagedReader.setRatio(h.meta.lastRatio)
            }
        }
    }

    /**
     * 重排阅读界面（切换字号、行间距、段间距、页边距、字体、背景颜色/自定义皮肤时调用），
     * 始终以画面中最上面三行文字 (`ReadingAnchor`) 作为基准定位，杜绝位置丢失或切换背景后内容不一致。
     */
    private fun relayout(keepPos: Boolean = true) {
        val anchor = if (keepPos) captureCurrentAnchor() else null
        relayoutWithAnchor(anchor)
    }

    private fun relayoutWithAnchor(anchor: ReadingAnchor?) {
        applyStyleToViews()
        if (prefs.pageMode == "scroll") {
            if (anchor != null) {
                curCi = anchor.chapterIndex
                scrollReader.relayout(anchor)
            } else {
                scrollReader.gotoChapter(curCi, atEnd = false)
            }
        } else {
            pagedReader.animMode = prefs.pageMode
            if (anchor != null) {
                curCi = anchor.chapterIndex
                pagedReader.setChapter(anchor.chapterIndex, anchor.chapterIndex == metaChapter, anchor = anchor)
            } else {
                pagedReader.setChapter(curCi, curCi == metaChapter)
            }
        }
        updateBottomInfo()
    }

    private fun applyStyleToViews() {
        val theme = ThemeSpec.of(prefs.night, prefs.theme)
        val tf = typefaceOf(prefs.bookFont, theme)
        val fs = FONT_VALUES[prefs.fontIdx.coerceIn(0, FONT_VALUES.size - 1)] * 0.295f * resources.displayMetrics.scaledDensity
        val st = ScrollReaderView.Style(fs, prefs.lineSp, prefs.paraSp, prefs.marginXdpi.dp(this@ReaderActivity), theme, tf)
        scrollReader.style = st
        val st2 = PagedView.Style(fs, prefs.lineSp, prefs.paraSp, prefs.marginXdpi.dp(this@ReaderActivity), theme, tf)
        pagedReader.style = st2
    }

    private fun typefaceOf(key: String, theme: ThemeSpec): Typeface? {
        if (key == "system" && !theme.customFontPath.isNullOrEmpty()) {
            val skinFont = File(theme.customFontPath)
            if (skinFont.exists()) {
                try { return Typeface.createFromFile(skinFont) } catch (e: Exception) { }
            }
        }
        return when {
            key == "system" -> null
            key == "song" -> Typeface.SERIF
            key == "hei" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            key == "kai" -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            key.startsWith("custom:") -> {
                val f = File(Books.dirFonts(this), key.substringAfter("custom:") + ".ttf")
                if (f.exists()) try { Typeface.createFromFile(f) } catch (e: Exception) { null } else null
            }
            else -> null
        }
    }

    private fun gotoChapter(ci: Int, atEnd: Boolean = false) {
        val h = handle ?: return
        if (ci !in 0 until h.chapters.size) return
        curCi = ci
        if (prefs.pageMode == "scroll") {
            scrollReader.gotoChapter(ci, atEnd = atEnd)
        } else {
            pagedReader.setChapter(ci, ci == metaChapter, atEnd = atEnd)
        }
        val ratio = if (atEnd) 1f else 0f
        h.saveProgress(ci, ratio, if (atEnd) -1 else 0)
        updateBottomInfo()
        chapterAdapter?.highlight(ci)
    }

    private fun saveProgress() {
        val h = handle ?: return
        val anchor = captureCurrentAnchor()
        val ci = if (prefs.pageMode == "scroll") scrollReader.visibleChapter() else curCi
        val ratio = if (prefs.pageMode == "scroll") scrollReader.ratioOf(ci) else pagedReader.ratio()
        h.saveProgress(ci, ratio, anchor.primaryCharOffset)
        updateBottomInfo()
    }

    private fun updateBottomInfo() {
        val h = handle ?: return
        val ci = if (prefs.pageMode == "scroll") scrollReader.visibleChapter() else curCi
        val ratio = if (prefs.pageMode == "scroll") scrollReader.ratioOf(ci) else pagedReader.ratio()
        tvProgress.text = if (prefs.progressScope == "book")
            String.format(Locale.US, "%.1f%%", h.progress(ci, ratio) * 100)
        else String.format(Locale.US, "%d%%", (ratio * 100).toInt())
        pill.progress = if (prefs.progressScope == "book") h.progress(ci, ratio) else ratio
    }

    private fun jumpProgress(p: Float) {
        val h = handle ?: return
        if (prefs.progressScope == "chapter") {
            if (prefs.pageMode == "scroll") scrollReader.scrollToChapter(scrollReader.visibleChapter(), p)
            else pagedReader.setPage((p * (pagedReader.pageCount() - 1).coerceAtLeast(0)).toInt().coerceAtLeast(0))
        } else {
            val target = (p * h.totalChars).toLong()
            var ci = 0
            while (ci < h.chapters.size - 1 && h.prefix[ci + 1] <= target) ci++
            val len = h.chapters[ci].len
            val within = if (len > 0) ((target - h.prefix[ci]).toFloat() / len).coerceIn(0f, 1f) else 0f
            curCi = ci
            if (prefs.pageMode == "scroll") {
                scrollReader.gotoChapter(ci, atEnd = false)
                scrollReader.scrollToChapter(ci, within)
            } else {
                pagedReader.setChapter(ci, ci == metaChapter)
                pagedReader.setRatio(within)
            }
        }
        saveProgress()
    }

    /* ---------------- 菜单 / 抽屉 / 面板 ---------------- */
    private fun setMenu(open: Boolean) {
        menuOpen = open
        val topBar = findViewById<View>(R.id.topBar)
        topBar.visibility = if (open) View.VISIBLE else View.GONE
        bottomUi.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            if (findBarOpen) setFindBar(false)
            topBar.alpha = 0f
            topBar.translationY = -40f.dp(this)
            topBar.animate().alpha(1f).translationY(0f).setDuration(220).start()
            bottomUi.alpha = 0f
            bottomUi.translationY = 80f.dp(this)
            bottomUi.animate().alpha(1f).translationY(0f).setDuration(220).start()
            updateBottomInfo()
        } else {
            setSettingsPanel(false)
        }
    }

    private fun setSettingsPanel(open: Boolean) {
        settingsOpen = open
        chapNav.visibility = if (open) View.GONE else View.VISIBLE
        settingsPanelWrap.visibility = if (open) View.VISIBLE else View.GONE
        if (open) applyTheme()
    }

    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        drawerMask.visibility = if (open) View.VISIBLE else View.GONE
        drawer.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            val activeCi = if (prefs.pageMode == "scroll") scrollReader.visibleChapter() else curCi
            chapterAdapter?.highlight(activeCi)
            drawer.post { chapterAdapter?.scrollToCurrent() }
        }
    }

    private fun setMore(open: Boolean) {
        moreOpen = open
        moreMask.visibility = if (open) View.VISIBLE else View.GONE
        moreSheet.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun setSub(title: String?, builder: (() -> Unit)?) {
        subOpen = title != null
        if (title != null) {
            subTitle.text = title
            subBody.removeAllViews()
            builder?.invoke()
        }
        subMask.visibility = if (subOpen) View.VISIBLE else View.GONE
        subSheet.visibility = if (subOpen) View.VISIBLE else View.GONE
    }

    /* ---------------- 主题着色 ---------------- */
    private fun applyTheme() {
        val t = ThemeSpec.of(prefs.night, prefs.theme)
        root.background = t.bgDrawable(this)
        findViewById<View>(R.id.topBar).background = t.barDrawable()
        findViewById<View>(R.id.bottomInfo).setBackgroundColor(t.bg)
        topTitle.setTextColor(t.fgMeta)
        findViewById<ImageButton>(R.id.btnBack).setColorFilter(t.menuIco)
        findViewById<ImageButton>(R.id.btnSearchText).setColorFilter(t.menuIco)

        // 查找关键词栏着色
        findBar.background = GradientDrawable().apply {
            setColor(t.bar)
            setStroke(1.dp(this@ReaderActivity), t.divider)
        }
        findViewById<ImageView>(R.id.findIcon).setColorFilter(t.fgSub)
        etFindKeyword.setTextColor(t.fg)
        etFindKeyword.setHintTextColor(t.fgSub)
        etFindKeyword.background = GradientDrawable().apply {
            cornerRadius = 18.dp(this@ReaderActivity).toFloat()
            setColor(t.pill)
        }
        btnFindNext.background = GradientDrawable().apply {
            cornerRadius = 17.dp(this@ReaderActivity).toFloat()
            setColor(t.accent)
        }
        btnFindNext.setTextColor(Color.WHITE)
        btnFindClose.setColorFilter(t.menuIco)

        tvClock.setTextColor(t.fgSub)
        tvProgress.setTextColor(t.fgSub)
        batteryIcon.level = batteryIcon.level

        // 底部菜单
        bottomUi.background = t.barDrawable()
        findViewById<TextView>(R.id.btnPrevChap).setTextColor(t.cardFg)
        findViewById<TextView>(R.id.btnNextChap).setTextColor(t.cardFg)
        pill.pillColor = t.pill
        pill.accent = t.accent
        pill.textColor = t.cardFg
        nightIcon.setColorFilter(t.menuIco)
        nightLabel.setTextColor(t.menuIco)
        nightLabel.text = if (prefs.night) "日间" else "夜间"
        nightIcon.setImageResource(if (prefs.night) R.drawable.ic_sun else R.drawable.ic_moon)
        listOf(R.id.btnCatalog, R.id.btnNight, R.id.btnSettings).forEach { id ->
            val g = findViewById<LinearLayout>(id)
            (0 until g.childCount).forEach {
                (g.getChildAt(it) as? TextView)?.setTextColor(t.menuIco)
                (g.getChildAt(it) as? ImageView)?.setColorFilter(t.menuIco)
            }
        }

        // 设置面板卡片
        tintSettingCards(t)
        renderFontNums()
        renderQuickSettings(t)

        // 抽屉
        drawer.setBackgroundColor(t.drawerBg)
        drawerTitle.setTextColor(t.fgTitle)
        drawer.background = GradientDrawable().apply { setColor(t.drawerBg) }

        // 更多/子面板
        moreSheet.background = GradientDrawable().apply { setColor(t.drawerBg) }
        subSheet.background = GradientDrawable().apply { setColor(t.drawerBg) }
        subTitle.setTextColor(t.fgTitle)
        applyStatusBar(t)
        applyStyleToViews()
    }

    private fun tintSettingCards(t: ThemeSpec) {
        tintCardsRec(settingsPanelWrap, t)
    }

    private fun renderQuickSettings(t: ThemeSpec) {
        val themes = findViewById<LinearLayout>(R.id.themeDots)
        themes.removeAllViews()
        listOf("day" to "纸白", "cream" to "米黄", "green" to "青草绿", "pink" to "樱花粉").forEach { (key, name) ->
            val slot = FrameLayout(this).apply {
                contentDescription = "主题$name"
                isSelected = !prefs.night && prefs.theme == key
                setOnClickListener {
                    val anchor = captureCurrentAnchor()
                    prefs.theme = key
                    prefs.night = false
                    applyTheme()
                    relayoutWithAnchor(anchor)
                }
            }
            slot.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ThemeSpec.of(key).bg)
                        setStroke(
                            if (slot.isSelected) 2.dp(this@ReaderActivity) else 1.dp(this@ReaderActivity),
                            if (slot.isSelected) t.accent else 0x55666666
                        )
                    }
                },
                FrameLayout.LayoutParams(24.dp(this), 24.dp(this), Gravity.CENTER)
            )
            themes.addView(slot, LinearLayout.LayoutParams(0, -1, 1f))
        }

        val modes = findViewById<LinearLayout>(R.id.pageModes)
        modes.removeAllViews()
        listOf("上下滚动" to "scroll", "从左往右" to "ltr", "从右往左" to "rtl").forEach { (name, key) ->
            modes.addView(
                TextView(this).apply {
                    text = name
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    contentDescription = "翻页$name"
                    isSelected = prefs.pageMode == key
                    setTextColor(if (isSelected) t.accent else t.cardFg)
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setOnClickListener {
                        if (prefs.pageMode != key) {
                            val anchor = captureCurrentAnchor()
                            curCi = anchor.chapterIndex
                            prefs.pageMode = key
                            applyReaderMode(initialOpen = false, anchor = anchor)
                            saveProgress()
                            if (autoOn) restartAuto()
                            renderQuickSettings(ThemeSpec.of(prefs.night, prefs.theme))
                        }
                    }
                },
                LinearLayout.LayoutParams(0, -1, 1f)
            )
        }
    }

    private fun tintCardsRec(v: View, t: ThemeSpec) {
        if (v is LinearLayout && v.id != R.id.themeDots && v.id != R.id.pageModes && v.id != R.id.fontNums) {
            val bg = v.background
            if (bg is GradientDrawable) {
                bg.cornerRadius = t.cardRadiusDp.dp(this)
                bg.setColor(t.card)
                if (t.cardStrokeWidthDp > 0f && t.cardStrokeColor != 0) {
                    bg.setStroke(t.cardStrokeWidthDp.dp(this).toInt().coerceAtLeast(1), t.cardStrokeColor)
                }
                v.background = bg
            }
        }
        if (v is TextView && v.id != R.id.fontMinus && v.id != R.id.fontPlus) v.setTextColor(t.cardFg)
        if (v is ImageView) v.setColorFilter(t.menuIco)
        if (v is ViewGroup) for (i in 0 until v.childCount) tintCardsRec(v.getChildAt(i), t)
    }

    private fun applyStatusBar(t: ThemeSpec) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark(t)
            isAppearanceLightNavigationBars = !isDark(t)
            if (prefs.showStatusBar) show(WindowInsetsCompat.Type.statusBars())
            else hide(WindowInsetsCompat.Type.statusBars())
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun isDark(t: ThemeSpec) =
        (Color.red(t.bg) * 299 + Color.green(t.bg) * 587 + Color.blue(t.bg) * 114) / 1000 < 128 || t.key in setOf("night", "nightimg")

    private fun applyKeepScreen() {
        if (prefs.screensaver == "always") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* ---------------- 字号（以画面最上面三行作为基准） ---------------- */
    private fun stepFont(d: Int) {
        val n = (prefs.fontIdx + d).coerceIn(0, FONT_VALUES.size - 1)
        if (n == prefs.fontIdx) return
        val anchor = captureCurrentAnchor()
        prefs.fontIdx = n
        renderFontNums()
        relayoutWithAnchor(anchor)
        saveProgress()
    }

    private fun renderFontNums() {
        val box = findViewById<LinearLayout>(R.id.fontNums)
        box.removeAllViews()
        fontNumViews.clear()
        for (i in (prefs.fontIdx - 1)..(prefs.fontIdx + 1)) {
            val tv = TextView(this)
            tv.text = if (i in FONT_VALUES.indices) FONT_VALUES[i].toString() else "·"
            tv.textSize = if (i == prefs.fontIdx) 16.5f else 14f
            tv.setTypeface(null, if (i == prefs.fontIdx) Typeface.BOLD else Typeface.NORMAL)
            val t = ThemeSpec.of(prefs.night, prefs.theme)
            tv.setTextColor(if (i == prefs.fontIdx) t.fgTitle else Color.GRAY)
            tv.setPadding(6.dp(this@ReaderActivity), 0, 6.dp(this@ReaderActivity), 0)
            tv.gravity = Gravity.CENTER
            tv.setOnClickListener {
                if (i in FONT_VALUES.indices && i != prefs.fontIdx) {
                    val anchor = captureCurrentAnchor()
                    prefs.fontIdx = i
                    renderFontNums()
                    relayoutWithAnchor(anchor)
                    saveProgress()
                }
            }
            box.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            fontNumViews.add(tv)
        }
        val t = ThemeSpec.of(prefs.night, prefs.theme)
        findViewById<TextView>(R.id.fontMinus).setTextColor(t.cardFg)
        findViewById<TextView>(R.id.fontPlus).setTextColor(t.cardFg)
    }

    /* ---------------- 更多设置 / 子面板 ---------------- */
    private fun buildMoreSheet() {
        val body = moreSheet.findViewById<LinearLayout>(R.id.moreBody)
        body.removeAllViews()
        val t = ThemeSpec.of(prefs.night, prefs.theme)
        fun row(label: String, sub: String?, bind: (FrameLayout) -> Unit): View {
            val lay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 15.dp(this@ReaderActivity), 0, 15.dp(this@ReaderActivity))
            }
            lay.addView(TextView(this).apply { text = label; textSize = 15.5f; setTextColor(t.fg) })
            sub?.let {
                lay.addView(TextView(this).apply {
                    text = it
                    textSize = 11.5f
                    setTextColor(t.fgSub)
                    setPadding(0, 3.dp(this@ReaderActivity), 0, 0)
                })
            }
            val wrap = FrameLayout(this)
            wrap.addView(lay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
            bind(wrap)
            val line = View(this)
            line.setBackgroundColor(t.divider)
            val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            outer.addView(wrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            outer.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            return outer
        }
        fun addSwitch(wrap: FrameLayout, init: Boolean, cb: (Boolean) -> Unit) {
            val sw = android.widget.Switch(this)
            sw.isChecked = init
            sw.setOnCheckedChangeListener { _, c -> cb(c) }
            wrap.addView(sw, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        body.addView(row("音量键翻页", null) { addSwitch(it, prefs.volumePage) { c -> prefs.volumePage = c } })
        body.addView(row("点击左右两侧翻页", null) {
            addSwitch(it, prefs.tapSidePage) { c ->
                prefs.tapSidePage = c
                scrollReader.tapSideEnabled = c
                pagedReader.tapSideEnabled = c
            }
        })
        body.addView(row("显示手机通知栏", null) {
            addSwitch(it, prefs.showStatusBar) { c ->
                prefs.showStatusBar = c
                applyStatusBar(ThemeSpec.of(prefs.night, prefs.theme))
            }
        })
        body.addView(chipsRow("阅读进度计算范围", listOf("全本" to "book", "按章" to "chapter"), prefs.progressScope) { v ->
            prefs.progressScope = v
            updateBottomInfo()
        })
        body.addView(row("展示进度、时间和电量", null) {
            addSwitch(it, prefs.showProgress) { c ->
                prefs.showProgress = c
                findViewById<View>(R.id.bottomInfo).visibility = if (c) View.VISIBLE else View.GONE
            }
        })
        body.addView(chipsRow("屏保", listOf("5分" to "5min", "10分" to "10min", "系统" to "system", "常亮" to "always"), prefs.screensaver) { v ->
            prefs.screensaver = v
            applyKeepScreen()
        })
    }

    private fun chipsRow(label: String, items: List<Pair<String, String>>, cur: String, cb: (String) -> Unit): View {
        val t = ThemeSpec.of(prefs.night, prefs.theme)
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 15.dp(this@ReaderActivity), 0, 15.dp(this@ReaderActivity))
        }
        lay.addView(TextView(this).apply { text = label; textSize = 15.5f; setTextColor(t.fg) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (txt, v) ->
            val b = TextView(this)
            b.text = txt
            b.textSize = 13f
            val on = v == cur
            b.setTextColor(if (on) t.accent else t.chipT)
            b.background = GradientDrawable().apply {
                cornerRadius = 18.dp(this@ReaderActivity).toFloat()
                setColor(if (on) (t.accent and 0x1EFFFFFF or 0x001860F0) else t.pill)
                if (on) setStroke(2.dp(this@ReaderActivity), t.accent)
            }
            b.setPadding(16.dp(this@ReaderActivity), 7.dp(this@ReaderActivity), 16.dp(this@ReaderActivity), 7.dp(this@ReaderActivity))
            chips.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 8.dp(this@ReaderActivity) })
            b.setOnClickListener { cb(v); buildMoreSheet() }
        }
        lay.addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp(this@ReaderActivity) })
        return lay
    }

    private fun buildSpacingSheet() {
        setSub("间距") {
            subBody.addView(
                chipsRow2(
                    "行间距",
                    listOf("紧凑" to 1.6f, "较紧" to 1.8f, "标准" to 2.02f, "较宽" to 2.24f, "宽松" to 2.5f),
                    prefs.lineSp
                ) { v ->
                    val anchor = captureCurrentAnchor()
                    prefs.lineSp = v
                    relayoutWithAnchor(anchor)
                    saveProgress()
                    buildSpacingSheet()
                }
            )
            subBody.addView(
                chipsRow2(
                    "段间距",
                    listOf("小" to 0.4f, "中" to 0.9f, "大" to 1.4f),
                    prefs.paraSp
                ) { v ->
                    val anchor = captureCurrentAnchor()
                    prefs.paraSp = v
                    relayoutWithAnchor(anchor)
                    saveProgress()
                    buildSpacingSheet()
                }
            )
            subBody.addView(
                chipsRow2(
                    "页边距",
                    listOf("窄" to 16, "标准" to 24, "宽" to 32),
                    prefs.marginXdpi
                ) { v ->
                    val anchor = captureCurrentAnchor()
                    prefs.marginXdpi = v
                    relayoutWithAnchor(anchor)
                    saveProgress()
                    buildSpacingSheet()
                }
            )
        }
    }

    private fun buildFontSheet() {
        setSub("系统字体") {
            subBody.addView(
                chipsRow2(
                    "内置字体",
                    listOf("默认" to "system", "宋体" to "song", "黑体" to "hei", "楷体" to "kai"),
                    prefs.bookFont
                ) { v ->
                    val anchor = captureCurrentAnchor()
                    prefs.bookFont = v
                    relayoutWithAnchor(anchor)
                    saveProgress()
                    buildFontSheet()
                }
            )
            val fonts = Books.dirFonts(this).listFiles()?.filter { it.extension in setOf("ttf", "otf") } ?: emptyList()
            if (fonts.isNotEmpty()) {
                subBody.addView(
                    chipsRow2(
                        "自定义字体",
                        fonts.map { it.nameWithoutExtension to "custom:" + it.nameWithoutExtension },
                        prefs.bookFont
                    ) { v ->
                        val anchor = captureCurrentAnchor()
                        prefs.bookFont = v
                        relayoutWithAnchor(anchor)
                        saveProgress()
                        buildFontSheet()
                    }
                )
            }
            val imp = Button(this)
            imp.text = "＋ 导入本地字体（ttf / otf）"
            imp.setTextColor(ThemeSpec.of(prefs.night, prefs.theme).accent)
            imp.background = GradientDrawable().apply {
                setStroke(2.dp(this@ReaderActivity), ThemeSpec.of(prefs.night, prefs.theme).accent)
                cornerRadius = 12.dp(this@ReaderActivity).toFloat()
            }
            imp.setOnClickListener { importFont() }
            subBody.addView(imp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp(this@ReaderActivity) })
        }
    }

    private fun buildBgSheet() {
        ThemeSpec.refreshCustomThemes(this)
        val t = ThemeSpec.of(prefs.night, prefs.theme)
        setSub("背景与自定义主题") {
            subBody.addView(
                chipsRow2(
                    "纯色主题",
                    listOf("纸白" to "day", "米黄" to "cream", "青草绿" to "green", "樱花粉" to "pink", "高级灰" to "grey", "羊皮纸" to "paper"),
                    if (prefs.night) "" else prefs.theme
                ) { v ->
                    val anchor = captureCurrentAnchor()
                    prefs.theme = v
                    prefs.night = false
                    applyTheme()
                    relayoutWithAnchor(anchor)
                    buildBgSheet()
                }
            )
            subBody.addView(
                chipsRow2(
                    "纹理与画境",
                    listOf("沙丘" to "dune", "星空夜幕" to "nightimg"),
                    if (prefs.night) "" else prefs.theme
                ) { v ->
                    val anchor = captureCurrentAnchor()
                    prefs.theme = v
                    prefs.night = false
                    applyTheme()
                    relayoutWithAnchor(anchor)
                    buildBgSheet()
                }
            )

            // 已安装的自定义皮肤包列表
            val customList = prefs.customThemeList()
            if (customList.length() > 0) {
                val items = mutableListOf<Pair<String, String>>()
                for (i in 0 until customList.length()) {
                    val obj = customList.getJSONObject(i)
                    val id = obj.optString("id")
                    val name = obj.optString("name", "自定义皮肤")
                    if (id.isNotEmpty()) items.add(name to id)
                }
                if (items.isNotEmpty()) {
                    subBody.addView(
                        chipsRow2(
                            "我的 DIY 专属主题（长按可移除）",
                            items,
                            if (prefs.night) "" else prefs.theme,
                            onLongPress = { skinId ->
                                CustomThemeManager.removeCustomTheme(this@ReaderActivity, skinId)
                                val anchor = captureCurrentAnchor()
                                applyTheme()
                                relayoutWithAnchor(anchor)
                                buildBgSheet()
                                Toast.makeText(this@ReaderActivity, "已移除该自定义主题", Toast.LENGTH_SHORT).show()
                            }
                        ) { v ->
                            val anchor = captureCurrentAnchor()
                            prefs.theme = v
                            prefs.night = false
                            applyTheme()
                            relayoutWithAnchor(anchor)
                            buildBgSheet()
                        }
                    )
                }
            }

            val impSkin = Button(this).apply {
                text = "＋ 导入自定义主题皮肤包（.zip）"
                setTextColor(t.accent)
                background = GradientDrawable().apply {
                    setStroke(2.dp(this@ReaderActivity), t.accent)
                    cornerRadius = 12.dp(this@ReaderActivity).toFloat()
                }
                setOnClickListener { importThemeZip() }
            }
            subBody.addView(impSkin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp(this@ReaderActivity) })

            val hint = TextView(this).apply {
                text = "支持导入包含 theme.json、背景图(bg.png/jpg)、顶底栏皮肤(bar.png)与字体(font.ttf)的 .zip 主题压缩包（可通过配套 MCP 工具一键生成）。"
                textSize = 11.5f
                setTextColor(t.fgSub)
                setPadding(2.dp(this@ReaderActivity), 8.dp(this@ReaderActivity), 2.dp(this@ReaderActivity), 0)
            }
            subBody.addView(hint)
        }
    }

    private fun <T> chipsRow2(
        label: String,
        items: List<Pair<String, T>>,
        cur: T,
        onLongPress: ((T) -> Unit)? = null,
        cb: (T) -> Unit
    ): View {
        val t = ThemeSpec.of(prefs.night, prefs.theme)
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dp(this@ReaderActivity), 0, 12.dp(this@ReaderActivity))
        }
        lay.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(t.fgSub) })
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (txt, v) ->
            val b = TextView(this)
            b.text = txt
            b.textSize = 13f
            val on = when {
                v is Float && cur is Float -> kotlin.math.abs(v - cur) < 0.01f
                else -> v == cur || v.toString() == cur.toString()
            }
            b.setTextColor(if (on) t.accent else t.chipT)
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            b.background = GradientDrawable().apply {
                cornerRadius = 18.dp(this@ReaderActivity).toFloat()
                setColor(if (on) 0x1A3B6EF0.toInt() else t.pill)
                if (on) setStroke(2.dp(this@ReaderActivity), t.accent)
            }
            b.setPadding(16.dp(this@ReaderActivity), 7.dp(this@ReaderActivity), 16.dp(this@ReaderActivity), 7.dp(this@ReaderActivity))
            chips.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 6.dp(this@ReaderActivity)
                rightMargin = 4.dp(this@ReaderActivity)
            })
            b.setOnClickListener { cb(v) }
            if (onLongPress != null) {
                b.setOnLongClickListener { onLongPress(v); true }
            }
        }
        scroll.addView(chips)
        lay.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 9.dp(this@ReaderActivity) })
        return lay
    }

    private val fontPicker = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    private val fontLauncher = registerForActivityResult(fontPicker) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val id = System.currentTimeMillis().toString(36)
            val f = File(Books.dirFonts(this), "$id.ttf")
            contentResolver.openInputStream(uri)!!.use { ins -> FileOutputStream(f).use { ins.copyTo(it) } }
            val tf = Typeface.createFromFile(f)
            if (tf == Typeface.DEFAULT) f.delete()
            else {
                val anchor = captureCurrentAnchor()
                prefs.bookFont = "custom:$id"
                relayoutWithAnchor(anchor)
                buildFontSheet()
            }
            Toast.makeText(this, "字体已导入", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "字体导入失败", Toast.LENGTH_SHORT).show()
        }
    }
    private fun importFont() { fontLauncher.launch("*/*") }

    private val themeZipPicker = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    private val themeZipLauncher = registerForActivityResult(themeZipPicker) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val anchor = captureCurrentAnchor()
            val skin = CustomThemeManager.importThemeZip(this, uri)
            prefs.theme = skin.id
            prefs.night = false
            applyTheme()
            relayoutWithAnchor(anchor)
            buildBgSheet()
            Toast.makeText(this, "已应用自定义主题「${skin.name}」", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "主题包解析失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun importThemeZip() { themeZipLauncher.launch("*/*") }

    /* ---------------- 自动阅读 ---------------- */
    private fun updateAutoLabel() { findViewById<TextView>(R.id.autoSpeed).text = String.format(Locale.US, "%.1fx", autoSpeed) }
    private fun restartAuto() { if (autoOn) { setAuto(false); setAuto(true) } }
    private fun setAuto(on: Boolean) {
        autoOn = on
        autoPaused = false
        autoHud.visibility = if (on) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(autoTick)
        mainHandler.removeCallbacks(autoPaged)
        if (on) {
            updateAutoLabel()
            if (prefs.pageMode == "scroll") mainHandler.postDelayed(autoTick, 16)
            else mainHandler.postDelayed(autoPaged, (5200 / autoSpeed).toLong())
            setMenu(false)
        }
    }

    /* ---------------- 音量键翻页 ---------------- */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (prefs.volumePage && e.action == android.view.KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (prefs.pageMode == "scroll") scrollReader.scrollPage(true) else pagedReader.turn(true)
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (prefs.pageMode == "scroll") scrollReader.scrollPage(false) else pagedReader.turn(false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onBackPressed() {
        when {
            findBarOpen -> setFindBar(false)
            subOpen -> setSub(null, null)
            moreOpen -> setMore(false)
            drawerOpen -> setDrawer(false)
            menuOpen -> setMenu(false)
            autoOn -> setAuto(false)
            else -> { saveProgress(); super.onBackPressed() }
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        handle?.close()
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryRx) } catch (e: Exception) { }
    }

    /* ---------------- 目录适配器 ---------------- */
    inner class ChapterAdapter(private val h: Books.Handle, private val onClick: (Int) -> Unit) :
        RecyclerView.Adapter<ChapterAdapter.VH>() {
        private var order = (0 until h.chapters.size).toMutableList()
        fun reverse(desc: Boolean) {
            order = if (desc) order.sortedDescending().toMutableList() else order.sorted().toMutableList()
            notifyDataSetChanged()
        }
        fun highlight(ci: Int) { curCi = ci; notifyDataSetChanged() }
        fun scrollToCurrent() {
            val idx = order.indexOf(curCi)
            if (idx >= 0) (chapterList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, chapterList.height / 3)
        }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tv: TextView = v.findViewById(R.id.chapterName) }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_chapter, p, false))
        override fun getItemCount() = order.size
        override fun onBindViewHolder(vh: VH, pos: Int) {
            val ci = order[pos]
            val t = ThemeSpec.of(prefs.night, prefs.theme)
            vh.tv.text = h.chapters[ci].title
            vh.tv.setTextColor(if (ci == curCi) t.accent else t.fg)
            vh.tv.paint.isFakeBoldText = ci == curCi
            vh.tv.setOnClickListener { onClick(ci) }
        }
    }
}
