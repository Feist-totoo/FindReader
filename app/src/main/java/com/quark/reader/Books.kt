package com.quark.reader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * 书籍仓库：TXT 流式导入 / EPUB 解析 / 章节索引缓存 / 按章读取 / 位置恢复
 *   files/books/<id>.txt   全书规范化章节 UTF-8
 *   files/cache/<id>.json  索引 {version,title,author,chapters:[{t,o,l}]}
 *   files/covers/<id>.img  自定义封面
 */
object Books {
    const val INDEX_VERSION = 2

    private val CH_RE = Regex(
        """^\s*(?:""" +
            """(?:正文\s*)?第\s*[0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,16}\s*[章节回卷集部篇季话幕讲折]""" +
            """|(?:卷|Chapter|CHAPTER|Part|PART|Section|SECTION)\s*[0-9零〇一二两三四五六七八九十百千万IVXLCDM]{1,12}""" +
            """|(?:序章|序言|序幕|楔子|引子|前言|尾声|终章|大结局|后记|番外|完本感言|上架感言|附录)""" +
            """|[0-9]{1,5}[\.、\s　]+[\u4e00-\u9fa5A-Za-z0-9《》（）()“”"'\-—_]{1,40}""" +
            """)(?:[\s 　:：\-─—·.、，_｜|/／(\[（【][^。！？；]{0,50})?\s*$"""
    )

    private val SUB_HEADING_RE = Regex(
        """^\s*(?:""" +
            """(?:[一二三四五六七八九十百千万]{1,6}[、.．\s　]+[\u4e00-\u9fa5A-Za-z0-9]{1,28})""" +
            """|(?:【[^】]{1,28}】)""" +
            """|(?:（[一二三四五六七八九十0-9]{1,4}）\s*[\u4e00-\u9fa5A-Za-z0-9]{0,24})""" +
            """)\s*$"""
    )

    private val RE_TITLE = Regex("""^\s*(?:书名|书\s*名)[：:]\s*(.+)$""")
    private val RE_AUTHOR = Regex("""^\s*作者[：:]\s*(.+)$""")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "books-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val SPINE_COLORS = intArrayOf(
        0xFF8C4A32.toInt(), 0xFF3E5A44.toInt(), 0xFF324A66.toInt(), 0xFF9C6B3C.toInt(),
        0xFF8A713C.toInt(), 0xFF5C4128.toInt(), 0xFF6B4A56.toInt(), 0xFF2F5550.toInt()
    )

    fun isChapterTitleLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return false
        return CH_RE.matches(trimmed)
    }

    fun isSubHeadingLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 42) return false
        return isChapterTitleLine(trimmed) || SUB_HEADING_RE.matches(trimmed)
    }

    class Chapter(val title: String, val offset: Long, val len: Int)

    class SearchHit(val chapterIndex: Int, val charOffsetInChapter: Int, val matchLength: Int, val snippet: String)

    class Meta(
        var id: String, var name: String, var title: String, var author: String,
        var kind: String, var totalChars: Long, var chapterCount: Int,
        var parsed: Boolean, var addedAt: Long,
    ) {
        var lastCi = 0
        var lastRatio = 0f
        var lastCharOffset = -1
        fun toJSON() = JSONObject()
            .put("id", id).put("name", name).put("title", title).put("author", author)
            .put("kind", kind).put("total", totalChars).put("chapters", chapterCount)
            .put("parsed", parsed).put("added", addedAt)
            .put("ci", lastCi).put("ratio", lastRatio.toDouble())
            .put("charOffset", lastCharOffset)

        companion object {
            fun fromJSON(o: JSONObject) = Meta(
                o.optString("id"), o.optString("name"), o.optString("title"),
                o.optString("author"), o.optString("kind", "txt"), o.optLong("total"),
                o.optInt("chapters"), o.optBoolean("parsed", true), o.optLong("added")
            ).apply {
                lastCi = o.optInt("ci", 0)
                lastRatio = o.optDouble("ratio", 0.0).toFloat()
                lastCharOffset = o.optInt("charOffset", -1)
            }
        }
    }

    class Handle(private val ctx: Context, val meta: Meta) {
        val file = File(dirBooks(ctx), meta.id + ".txt")
        private var raf: RandomAccessFile? = null
        var chapters: MutableList<Chapter> = mutableListOf()
            private set
        var prefix = longArrayOf(0)
            private set
        var totalChars = 0L
            private set
        val title get() = meta.title
        val author get() = meta.author

        init {
            reloadIndex()
            val prog = Prefs.get(ctx).readProgress(meta.id)
            if (prog != null) {
                meta.lastCi = prog.optInt("ci", meta.lastCi)
                meta.lastRatio = prog.optDouble("ratio", meta.lastRatio.toDouble()).toFloat()
                meta.lastCharOffset = prog.optInt("charOffset", meta.lastCharOffset)
            }
        }

        fun reloadIndex(): Int {
            val idx = File(dirCache(ctx), meta.id + ".json")
            if (!idx.exists()) return chapters.size
            try {
                val o = JSONObject(idx.readText())
                val ver = o.optInt("version", 1)
                if (ver < INDEX_VERSION && meta.kind == "txt" && file.exists()) {
                    rebuildTxtIndexInPlace(idx, meta.name)
                }
                val o2 = JSONObject(idx.readText())
                val arr = o2.optJSONArray("chapters") ?: JSONArray()
                val list = mutableListOf<Chapter>()
                var acc = 0L
                val pre = mutableListOf(0L)
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val ch = Chapter(c.getString("t"), c.getLong("o"), c.getInt("l"))
                    list.add(ch); acc += ch.len; pre.add(acc)
                }
                chapters = list; prefix = pre.toLongArray(); totalChars = acc
                meta.totalChars = acc; meta.chapterCount = list.size
            } catch (e: Exception) {
                Log.e("Books", "reloadIndex", e)
            }
            return chapters.size
        }

        private fun rebuildTxtIndexInPlace(idx: File, defaultName: String) {
            try {
                val rawText = file.readText(Charsets.UTF_8)
                val tmpOut = File(file.parentFile, file.name + ".rebuild")
                parseAndWriteNormalizedTxt(rawText.byteInputStream(Charsets.UTF_8), tmpOut, idx, defaultName)
                if (tmpOut.exists() && tmpOut.length() > 0) {
                    synchronized(this) { raf?.close(); raf = null }
                    tmpOut.copyTo(file, overwrite = true)
                    tmpOut.delete()
                }
            } catch (e: Exception) {
                Log.e("Books", "rebuildTxtIndexInPlace", e)
            }
        }

        fun chapterText(ci: Int): String {
            if (ci < 0 || ci >= chapters.size) return ""
            val ch = chapters[ci]
            if (ch.len <= 0) return ""
            val buf = ByteArray(ch.len)
            synchronized(this) {
                if (raf == null) raf = RandomAccessFile(file, "r")
                val safeOffset = ch.offset.coerceIn(0L, file.length().coerceAtLeast(0L))
                val avail = (file.length() - safeOffset).toInt().coerceAtLeast(0)
                val toRead = ch.len.coerceAtMost(avail)
                if (toRead <= 0) return ""
                raf!!.seek(safeOffset)
                raf!!.readFully(buf, 0, toRead)
                return String(buf, 0, toRead, Charsets.UTF_8)
            }
        }

        /**
         * 在全书中从 (startChapter, startCharOffset) 开始向后查找关键词（不区分大小写，支持循环到开头）
         */
        fun findNext(keyword: String, startChapter: Int, startCharOffset: Int, wrapAround: Boolean = true): SearchHit? {
            val q = keyword.trim()
            if (q.isEmpty() || chapters.isEmpty()) return null
            val totalCh = chapters.size
            val safeStartCh = startChapter.coerceIn(0, totalCh - 1)

            // 1. 搜索当前章节 startCharOffset 之后的内容，以及后续章节
            for (ci in safeStartCh until totalCh) {
                val text = chapterText(ci)
                val fromIdx = if (ci == safeStartCh) startCharOffset.coerceAtLeast(0) else 0
                if (fromIdx <= text.length) {
                    val idx = text.indexOf(q, fromIdx, ignoreCase = true)
                    if (idx >= 0) {
                        return buildHit(ci, text, idx, q.length)
                    }
                }
            }
            // 2. 如允许循环，从第 0 章搜到 safeStartCh 的 startCharOffset 之前
            if (wrapAround) {
                for (ci in 0..safeStartCh) {
                    val text = chapterText(ci)
                    val idx = text.indexOf(q, 0, ignoreCase = true)
                    if (idx >= 0 && (ci < safeStartCh || idx < startCharOffset || startCharOffset < 0)) {
                        return buildHit(ci, text, idx, q.length)
                    }
                }
            }
            return null
        }

        private fun buildHit(ci: Int, text: String, idx: Int, len: Int): SearchHit {
            val snipStart = (idx - 12).coerceAtLeast(0)
            val snipEnd = (idx + len + 18).coerceAtMost(text.length)
            val snippet = text.substring(snipStart, snipEnd).replace("\n", " ")
            return SearchHit(ci, idx, len, snippet)
        }

        fun close() { synchronized(this) { raf?.close(); raf = null } }

        fun saveProgress(ci: Int, ratio: Float, charOffset: Int = -1) {
            meta.lastCi = ci
            meta.lastRatio = ratio.coerceIn(0f, 1f)
            if (charOffset >= 0) meta.lastCharOffset = charOffset
            Prefs.get(ctx).writeProgress(meta.id, ci, 0, meta.lastRatio, meta.lastCharOffset)
            upsertMeta(ctx, meta)
        }

        fun progress(ci: Int, ratio: Float): Float {
            if (chapters.isEmpty() || totalChars <= 0L) return 0f
            val c = ci.coerceIn(0, chapters.size - 1)
            return ((prefix[c] + ratio.coerceIn(0f, 1f) * chapters[c].len) / totalChars).toFloat().coerceIn(0f, 1f)
        }
    }

    fun dirBooks(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }
    fun dirCache(ctx: Context) = File(ctx.filesDir, "cache").apply { mkdirs() }
    fun dirCovers(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun dirFonts(ctx: Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }
    fun dirThemes(ctx: Context) = File(ctx.filesDir, "themes").apply { mkdirs() }

    fun listMeta(ctx: Context): MutableList<Meta> {
        val arr = Prefs.get(ctx).bookList()
        val out = mutableListOf<Meta>()
        for (i in 0 until arr.length()) {
            val m = Meta.fromJSON(arr.getJSONObject(i))
            val prog = Prefs.get(ctx).readProgress(m.id)
            if (prog != null) {
                m.lastCi = prog.optInt("ci", m.lastCi)
                m.lastRatio = prog.optDouble("ratio", m.lastRatio.toDouble()).toFloat()
                m.lastCharOffset = prog.optInt("charOffset", m.lastCharOffset)
            }
            out.add(m)
        }
        return out
    }

    fun persistList(ctx: Context, list: List<Meta>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJSON()) }
        Prefs.get(ctx).saveBookList(arr)
    }

    fun upsertMeta(ctx: Context, m: Meta) {
        val list = listMeta(ctx).filter { it.id != m.id }.toMutableList()
        list.add(m); persistList(ctx, list)
    }

    fun removeMeta(ctx: Context, id: String) {
        persistList(ctx, listMeta(ctx).filter { it.id != id })
        File(dirBooks(ctx), "$id.txt").delete()
        File(dirCache(ctx), "$id.json").delete()
        File(dirCovers(ctx), "$id.img").delete()
        Prefs.get(ctx).clearProgress(id)
    }

    fun open(ctx: Context, id: String): Handle? =
        listMeta(ctx).firstOrNull { it.id == id }?.let { Handle(ctx, it) }

    /* ============ TXT 流式导入与章节精确偏移写出 ============ */
    interface ImportCallback { fun onHeadReady(id: String); fun onDone(id: String, ok: Boolean) }

    internal data class ParsedChapter(val title: String, val body: String)
    internal data class ParsedBookResult(val title: String, val author: String, val chapters: List<Chapter>)

    /**
     * 解析文本行流并生成章节列表（纯函数逻辑，便于单元测试与流式写入共用）
     */
    internal fun parseLinesToChapters(lines: Sequence<String>, defaultName: String): Triple<String, String, List<ParsedChapter>> {
        var curTitle: String? = null
        val sb = StringBuilder()
        var lineCount = 0
        var title = ""
        var author = ""
        val chapters = mutableListOf<ParsedChapter>()

        fun flush() {
            val t = curTitle ?: return
            val content = sb.toString().trim()
            if (content.isNotEmpty()) {
                chapters.add(ParsedChapter(t, content))
            } else if (t != "前言") {
                // 即使某章只有标题没有正文（或空章），也保留章节防止跳章丢失
                chapters.add(ParsedChapter(t, "（本章无正文）"))
            }
            sb.clear()
        }

        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            lineCount++
            if (lineCount <= 60) {
                if (title.isEmpty()) RE_TITLE.find(line)?.let { title = it.groupValues[1].trim() }
                if (author.isEmpty()) RE_AUTHOR.find(line)?.let { author = it.groupValues[1].trim() }
            }
            val s = line.trimEnd()
            val trimmed = s.trim()
            if (isChapterTitleLine(trimmed)) {
                flush()
                curTitle = trimmed
            } else if (curTitle != null || trimmed.isNotEmpty()) {
                if (curTitle == null) {
                    // 跳过开头紧跟的书名/作者元数据行，不重复塞入前言正文
                    if (lineCount <= 10 && (RE_TITLE.matches(trimmed) || RE_AUTHOR.matches(trimmed))) continue
                    curTitle = "前言"
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(trimmed.removePrefix("　　").trimStart())
            }
        }
        flush()
        if (chapters.isEmpty()) {
            chapters.add(ParsedChapter(title.ifEmpty { defaultName }, ""))
        }
        return Triple(title.ifEmpty { defaultName }, author.ifEmpty { "佚名" }, chapters)
    }

    internal fun parseAndWriteNormalizedTxt(ins: InputStream, out: File, idx: File, defaultName: String): Pair<String, String> {
        val rawBytes = ins.use { it.readBytes() }
        var charset = Charsets.UTF_8
        var skip = 0
        if (rawBytes.size >= 3 &&
            (rawBytes[0].toInt() and 0xFF) == 0xEF &&
            (rawBytes[1].toInt() and 0xFF) == 0xBB &&
            (rawBytes[2].toInt() and 0xFF) == 0xBF
        ) {
            skip = 3
        } else if (rawBytes.size >= 2 &&
            (rawBytes[0].toInt() and 0xFF) == 0xFF &&
            (rawBytes[1].toInt() and 0xFF) == 0xFE
        ) {
            charset = Charsets.UTF_16LE
            skip = 2
        } else if (rawBytes.size >= 2 &&
            (rawBytes[0].toInt() and 0xFF) == 0xFE &&
            (rawBytes[1].toInt() and 0xFF) == 0xFF
        ) {
            charset = Charsets.UTF_16BE
            skip = 2
        } else {
            val checkLen = rawBytes.size.coerceAtMost(1 shl 17)
            var bad = 0
            var i = 0
            while (i < checkLen) {
                val b = rawBytes[i].toInt() and 0xFF
                when {
                    b < 0x80 -> i++
                    b in 0xC2..0xDF -> {
                        if (i + 1 >= checkLen || (rawBytes[i + 1].toInt() and 0xC0) != 0x80) { bad++; i++ } else i += 2
                    }
                    b in 0xE0..0xEF -> {
                        if (i + 2 >= checkLen || (rawBytes[i + 1].toInt() and 0xC0) != 0x80 || (rawBytes[i + 2].toInt() and 0xC0) != 0x80) { bad++; i++ } else i += 3
                    }
                    b in 0xF0..0xF4 -> {
                        if (i + 3 >= checkLen || (rawBytes[i + 1].toInt() and 0xC0) != 0x80 || (rawBytes[i + 2].toInt() and 0xC0) != 0x80 || (rawBytes[i + 3].toInt() and 0xC0) != 0x80) { bad++; i++ } else i += 4
                    }
                    else -> { bad++; i++ }
                }
                if (bad > 8) break
            }
            if (bad > 8) {
                charset = try { Charset.forName("GB18030") } catch (e: Exception) {
                    try { Charset.forName("GBK") } catch (e2: Exception) { Charsets.UTF_8 }
                }
            }
        }

        val fullText = String(rawBytes, skip, rawBytes.size - skip, charset)
        val (bookTitle, bookAuthor, parsedChapters) = parseLinesToChapters(fullText.lineSequence(), defaultName)

        val arr = JSONArray()
        var offset = 0L
        DataOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { dos ->
            for (ch in parsedChapters) {
                val bytes = ch.body.toByteArray(Charsets.UTF_8)
                dos.write(bytes)
                arr.put(JSONObject().put("t", ch.title).put("o", offset).put("l", bytes.size))
                offset += bytes.size
            }
        }
        idx.writeText(
            JSONObject()
                .put("version", INDEX_VERSION)
                .put("title", bookTitle)
                .put("author", bookAuthor)
                .put("chapters", arr)
                .toString()
        )
        return Pair(bookTitle, bookAuthor)
    }

    fun importTxt(ctx: Context, uri: Uri, displayName: String, cb: ImportCallback?): Meta {
        val id = System.currentTimeMillis().toString(36)
        val base = displayName.substringBeforeLast('.')
        val meta = Meta(id, base, "", "", "txt", 0, 0, false, System.currentTimeMillis())
        upsertMeta(ctx, meta)
        io.execute {
            var ok = true
            try {
                val ins = ctx.contentResolver.openInputStream(uri) ?: error("open fail")
                val (t, a) = parseAndWriteNormalizedTxt(ins, File(dirBooks(ctx), "$id.txt"), File(dirCache(ctx), "$id.json"), base)
                meta.title = t.ifEmpty { base }; meta.author = a.ifEmpty { "佚名" }
                fillMetaFromIndex(ctx, meta)
                meta.parsed = true; upsertMeta(ctx, meta)
            } catch (e: Exception) { ok = false; Log.e("Books", "importTxt", e) }
            val finalOk = ok
            main.post { cb?.onDone(id, finalOk) }
        }
        return meta
    }

    /** 欢迎说明书（assets/welcome.txt，走同一导入管线，作为一本书展示） */
    fun importWelcomeIfNeeded(ctx: Context, cb: ImportCallback?) {
        if (listMeta(ctx).isNotEmpty()) return
        val id = "welcome"
        val meta = Meta(id, "欢迎使用", "", "", "txt", 0, 0, false, System.currentTimeMillis())
        upsertMeta(ctx, meta)
        io.execute {
            try {
                val (t, a) = parseAndWriteNormalizedTxt(
                    ctx.assets.open("welcome.txt"),
                    File(dirBooks(ctx), "$id.txt"),
                    File(dirCache(ctx), "$id.json"),
                    "欢迎使用·觅览阅读器"
                )
                meta.title = t.ifEmpty { "欢迎使用·觅览阅读器" }; meta.author = a.ifEmpty { "FindReader" }
                fillMetaFromIndex(ctx, meta)
                meta.parsed = true; upsertMeta(ctx, meta)
                main.post { cb?.onDone(id, true) }
            } catch (e: Exception) { Log.e("Books", "welcome", e) }
        }
    }

    private fun fillMetaFromIndex(ctx: Context, meta: Meta) {
        try {
            val o = JSONObject(File(dirCache(ctx), meta.id + ".json").readText())
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            meta.chapterCount = arr.length()
            var acc = 0L
            for (i in 0 until arr.length()) acc += arr.getJSONObject(i).getInt("l")
            meta.totalChars = acc
        } catch (e: Exception) { }
    }

    /* ============ EPUB 导入 ============ */
    fun importEpub(ctx: Context, uri: Uri, displayName: String, cb: ImportCallback?): Meta {
        val id = System.currentTimeMillis().toString(36)
        val base = displayName.substringBeforeLast('.')
        val meta = Meta(id, base, "", "", "epub", 0, 0, false, System.currentTimeMillis())
        upsertMeta(ctx, meta)
        io.execute {
            var ok = true
            try {
                val tmp = File(ctx.cacheDir, "$id.epub")
                ctx.contentResolver.openInputStream(uri)!!.use { ins -> FileOutputStream(tmp).use { ins.copyTo(it) } }
                val zip = java.util.zip.ZipFile(tmp)
                val db = javax.xml.parsers.DocumentBuilderFactory.newInstance().run { isNamespaceAware = true; newDocumentBuilder() }
                val cont = db.parse(zip.getInputStream(zip.getEntry("META-INF/container.xml")))
                var opfPath = ""
                for (i in 0 until cont.getElementsByTagName("*").length) {
                    val n = cont.getElementsByTagName("*").item(i)
                    if (n.nodeName.endsWith("rootfile")) { opfPath = n.attributes.getNamedItem("full-path").nodeValue; break }
                }
                val opf = db.parse(zip.getInputStream(zip.getEntry(opfPath)))
                val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""
                var title = ""; var author = ""
                val ndl = opf.getElementsByTagName("*")
                for (i in 0 until ndl.length) {
                    val n = ndl.item(i)
                    if (title.isEmpty() && n.nodeName.endsWith("title") && n.parentNode.nodeName.endsWith("metadata")) title = n.textContent.trim()
                    if (author.isEmpty() && n.nodeName.endsWith("creator")) author = n.textContent.trim()
                }
                val manifest = HashMap<String, Triple<String, String, String>>()
                for (i in 0 until ndl.length) {
                    val n = ndl.item(i)
                    if (n.nodeName.endsWith("item") && n.parentNode.nodeName.endsWith("manifest"))
                        manifest[n.attributes.getNamedItem("id").nodeValue] = Triple(
                            opfDir + n.attributes.getNamedItem("href").nodeValue.removePrefix("./"),
                            n.attributes.getNamedItem("media-type")?.nodeValue ?: "",
                            n.attributes.getNamedItem("properties")?.nodeValue ?: ""
                        )
                }
                val spine = mutableListOf<String>()
                for (i in 0 until ndl.length) {
                    val n = ndl.item(i)
                    if (n.nodeName.endsWith("itemref") && n.parentNode.nodeName.endsWith("spine"))
                        spine.add(n.attributes.getNamedItem("idref").nodeValue)
                }
                val titleByHref = HashMap<String, String>()
                manifest.values.firstOrNull { it.third.contains("nav") }?.let { nav ->
                    try {
                        val navDoc = db.parse(zip.getInputStream(zip.getEntry(nav.first)))
                        val ndl2 = navDoc.getElementsByTagName("*")
                        for (i in 0 until ndl2.length) {
                            val n = ndl2.item(i)
                            if (n.nodeName.endsWith("a") && n.attributes.getNamedItem("href") != null)
                                titleByHref[(opfDir + n.attributes.getNamedItem("href").nodeValue.substringBefore("#")).removePrefix("./")] = n.textContent.trim()
                        }
                    } catch (e: Exception) { }
                }
                val chapters = mutableListOf<Pair<String, String>>()
                for (ref in spine) {
                    val m = manifest[ref] ?: continue
                    if (!Regex("""x?html|xml""").containsMatchIn(m.second) && !Regex("""\.x?html?$""").containsMatchIn(m.first)) continue
                    val entry = zip.getEntry(m.first) ?: continue
                    val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    val body = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: html
                    val paras = Regex("<(h[1-6]|p|blockquote|div)[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
                        .findAll(body).map {
                            it.groupValues[2].replace(Regex("<[^>]+>"), "")
                                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                .replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()
                        }.filter { it.isNotEmpty() }.toList()
                    if (paras.isEmpty()) continue
                    val chTitle = titleByHref[m.first] ?: paras.firstOrNull { it.length <= 50 } ?: "第${chapters.size + 1}节"
                    val ps = if (paras.first() == chTitle) paras.drop(1) else paras
                    if (ps.isEmpty()) continue
                    chapters.add(Pair(chTitle, ps.joinToString("\n")))
                }
                zip.close(); tmp.delete()
                val out = File(dirBooks(ctx), "$id.txt")
                val arr = JSONArray(); var off = 0L
                DataOutputStream(BufferedOutputStream(FileOutputStream(out))).use { dos ->
                    for ((t, text) in chapters) {
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        dos.write(bytes)
                        arr.put(JSONObject().put("t", t).put("o", off).put("l", bytes.size))
                        off += bytes.size
                    }
                }
                meta.title = title.ifEmpty { base }; meta.author = author.ifEmpty { "佚名" }
                File(dirCache(ctx), "$id.json").writeText(
                    JSONObject()
                        .put("version", INDEX_VERSION)
                        .put("title", meta.title)
                        .put("author", meta.author)
                        .put("chapters", arr)
                        .toString()
                )
                meta.totalChars = off; meta.chapterCount = chapters.size; meta.parsed = true
                upsertMeta(ctx, meta)
            } catch (e: Exception) { ok = false; Log.e("Books", "importEpub", e) }
            val finalOk = ok
            main.post { cb?.onDone(id, finalOk) }
        }
        return meta
    }

    fun spineColor(h: Int): Int = SPINE_COLORS[Math.floorMod(h, SPINE_COLORS.size)]
    fun hash(s: String): Int { var h = 5381; for (c in s) h = ((h shl 5) + h + c.code); return h }
}
