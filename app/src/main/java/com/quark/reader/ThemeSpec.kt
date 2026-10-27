package com.quark.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 阅读主题（内置 9 种 + 用户 DIY 压缩包皮肤主题） */
data class ThemeSpec(
    val key: String,
    val bg: Int,
    val bgGradient: IntArray? = null,
    val fg: Int,
    val fgMeta: Int,
    val fgTitle: Int,
    val fgSub: Int,
    val bar: Int,
    val card: Int,
    val cardFg: Int,
    val pill: Int,
    val divider: Int,
    val accent: Int,
    val chipT: Int,
    val drawerBg: Int,
    val menuIco: Int,
    val name: String = key,
    val bgImagePath: String? = null,
    val bgScaleMode: String = "cover", // cover | tile | center | stretch
    val bgOverlayColor: Int = 0x00000000,
    val barImagePath: String? = null,
    val cardImagePath: String? = null,
    val cardRadiusDp: Float = 12f,
    val cardStrokeColor: Int = 0x00000000,
    val cardStrokeWidthDp: Float = 0f,
    val customFontPath: String? = null,
) {
    fun bgDrawable(ctx: Context? = null): Drawable {
        if (!bgImagePath.isNullOrEmpty()) {
            val file = File(bgImagePath)
            if (file.exists()) {
                val bmp = try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
                if (bmp != null) {
                    return SkinBackgroundDrawable(bmp, bg, bgGradient, bgScaleMode, bgOverlayColor)
                }
            }
        }
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            bgGradient ?: intArrayOf(bg, bg)
        )
    }

    fun barDrawable(): Drawable {
        if (!barImagePath.isNullOrEmpty()) {
            val file = File(barImagePath)
            if (file.exists()) {
                val bmp = try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
                if (bmp != null) {
                    return SkinBackgroundDrawable(bmp, bar, null, "cover", 0x00000000)
                }
            }
        }
        return GradientDrawable().apply { setColor(bar) }
    }

    companion object {
        @Volatile
        private var customCache: Map<String, ThemeSpec> = emptyMap()

        fun refreshCustomThemes(ctx: Context) {
            customCache = CustomThemeManager.loadInstalledThemes(ctx)
        }

        fun builtIn(): Map<String, ThemeSpec> = mapOf(
            "day" to ThemeSpec(
                "day", 0xFFF2F2F2.toInt(), null,
                0xFF262626.toInt(), 0xFF8C8C8C.toInt(), 0xFF1F1F1F.toInt(), 0xFF9A9A9A.toInt(),
                0xFFF7F7F7.toInt(), 0xFFFFFFFF.toInt(), 0xFF333333.toInt(), 0xFFE9E9E9.toInt(), 0xFFEFEFEF.toInt(),
                0xFF3B6EF0.toInt(), 0xFF666666.toInt(), 0xFFFFFFFF.toInt(), 0xFF555555.toInt(),
                name = "纸白"
            ),
            "cream" to ThemeSpec(
                "cream", 0xFFF7ECD2.toInt(), null,
                0xFF43392B.toInt(), 0xFFA08F6E.toInt(), 0xFF3A2F1F.toInt(), 0xFFA79A7C.toInt(),
                0xFFF3E7C9.toInt(), 0xFFFFFDF4.toInt(), 0xFF5A4C33.toInt(), 0xFFEBDDBB.toInt(), 0xFFEFE3C6.toInt(),
                0xFF3B6EF0.toInt(), 0xFF8A7A5C.toInt(), 0xFFFCF5E4.toInt(), 0xFF6B5D42.toInt(),
                name = "米黄"
            ),
            "green" to ThemeSpec(
                "green", 0xFFD9E8D2.toInt(), null,
                0xFF33402F.toInt(), 0xFF7E9078.toInt(), 0xFF2C3A28.toInt(), 0xFF85977F.toInt(),
                0xFFCFE2C7.toInt(), 0xFFF3FAEF.toInt(), 0xFF41513C.toInt(), 0xFFC4DABC.toInt(), 0xFFD2E3CB.toInt(),
                0xFF3B6EF0.toInt(), 0xFF6E8168.toInt(), 0xFFEAF3E5.toInt(), 0xFF5A6B54.toInt(),
                name = "青草绿"
            ),
            "pink" to ThemeSpec(
                "pink", 0xFFF6DFD8.toInt(), null,
                0xFF473632.toInt(), 0xFFAC8C83.toInt(), 0xFF3F2C27.toInt(), 0xFFB2988F.toInt(),
                0xFFF1D8CF.toInt(), 0xFFFDF3EF.toInt(), 0xFF6A4F48.toInt(), 0xFFEBCFC5.toInt(), 0xFFF0D9D0.toInt(),
                0xFF3B6EF0.toInt(), 0xFF9C7C73.toInt(), 0xFFFAECE6.toInt(), 0xFF7A5C54.toInt(),
                name = "樱花粉"
            ),
            "grey" to ThemeSpec(
                "grey", 0xFFDBDBDB.toInt(), null,
                0xFF2E2E2E.toInt(), 0xFF828282.toInt(), 0xFF262626.toInt(), 0xFF8A8A8A.toInt(),
                0xFFD4D4D4.toInt(), 0xFFF2F2F2.toInt(), 0xFF3A3A3A.toInt(), 0xFFC7C7C7.toInt(), 0xFFDCDCDC.toInt(),
                0xFF3B6EF0.toInt(), 0xFF6C6C6C.toInt(), 0xFFE7E7E7.toInt(), 0xFF555555.toInt(),
                name = "高级灰"
            ),
            "paper" to ThemeSpec(
                "paper", 0xFFEAE0C8.toInt(), null,
                0xFF3F3626.toInt(), 0xFF94896C.toInt(), 0xFF382F1E.toInt(), 0xFF9C9074.toInt(),
                0xFFE3D7BA.toInt(), 0xFFF6EFDC.toInt(), 0xFF5A4E36.toInt(), 0xFFDCCFA9.toInt(), 0xFFE4D8BC.toInt(),
                0xFF3B6EF0.toInt(), 0xFF83775C.toInt(), 0xFFF0E8D2.toInt(), 0xFF6A5F45.toInt(),
                name = "羊皮纸"
            ),
            "dune" to ThemeSpec(
                "dune", 0xFFEFD3A3.toInt(), intArrayOf(0xFFF6E3BC.toInt(), 0xFFE4BA82.toInt()),
                0xFF5A4632.toInt(), 0xFFA08358.toInt(), 0xFF4E3A26.toInt(), 0xFFA78C61.toInt(),
                0xFFF2E0BE.toInt(), 0xFFFFFBF2.toInt(), 0xFF6A5439.toInt(), 0xFFD6B787.toInt(), 0xFFDEC9A0.toInt(),
                0xFF3B6EF0.toInt(), 0xFF8C734E.toInt(), 0xFFF4E2C2.toInt(), 0xFF7A6244.toInt(),
                name = "沙丘"
            ),
            "nightimg" to ThemeSpec(
                "nightimg", 0xFF101A2E.toInt(), intArrayOf(0xFF0B1426.toInt(), 0xFF16233F.toInt()),
                0xFFC9D2E4.toInt(), 0xFF5E6C8A.toInt(), 0xFFD6DEEE.toInt(), 0xFF5E6C8A.toInt(),
                0xFF141D31.toInt(), 0x12FFFFFF, 0xFFC2CCE0.toInt(), 0x21FFFFFF, 0x14FFFFFF,
                0xFF6C8CFF.toInt(), 0xFF7484A5.toInt(), 0xFF141D31.toInt(), 0xFF8C9AB8.toInt(),
                name = "星空夜幕"
            ),
            "night" to ThemeSpec(
                "night", 0xFF000000.toInt(), null,
                0xFF8C8C8C.toInt(), 0xFF6E6E6E.toInt(), 0xFFB0B0B0.toInt(), 0xFF6E6E6E.toInt(),
                0xFF0D0D0D.toInt(), 0xFF1A1A1A.toInt(), 0xFFB5B5B5.toInt(), 0xFF242424.toInt(), 0xFF1C1C1C.toInt(),
                0xFF4C79F5.toInt(), 0xFF8C8C8C.toInt(), 0xFF0A0A0A.toInt(), 0xFF8A8A8A.toInt(),
                name = "夜间"
            ),
        )

        fun all(): Map<String, ThemeSpec> = builtIn() + customCache

        fun of(key: String): ThemeSpec = all()[key] ?: builtIn()["day"]!!

        fun of(night: Boolean, theme: String): ThemeSpec = if (night) builtIn()["night"]!! else of(theme)
    }
}

/**
 * 皮肤背景绘制器：支持输入法皮肤式背景图（cover 居中裁剪铺满 / tile 平铺纹理 / center 居中 / stretch 拉伸）+ 遮罩层
 */
class SkinBackgroundDrawable(
    private val bitmap: Bitmap,
    private val fallbackColor: Int,
    private val gradient: IntArray?,
    private val scaleMode: String,
    private val overlayColor: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        canvas.drawColor(fallbackColor)
        when (scaleMode.lowercase()) {
            "tile" -> {
                val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                paint.shader = shader
                canvas.drawRect(b, paint)
                paint.shader = null
            }
            "stretch" -> {
                canvas.drawBitmap(bitmap, null, b, paint)
            }
            "center" -> {
                val dx = b.left + (b.width() - bitmap.width) / 2f
                val dy = b.top + (b.height() - bitmap.height) / 2f
                canvas.drawBitmap(bitmap, dx, dy, paint)
            }
            else -> { // cover
                val bw = bitmap.width.toFloat().coerceAtLeast(1f)
                val bh = bitmap.height.toFloat().coerceAtLeast(1f)
                val vw = b.width().toFloat()
                val vh = b.height().toFloat()
                val scale = maxOf(vw / bw, vh / bh)
                val dx = b.left + (vw - bw * scale) * 0.5f
                val dy = b.top + (vh - bh * scale) * 0.5f
                matrix.reset()
                matrix.postScale(scale, scale)
                matrix.postTranslate(dx, dy)
                canvas.save()
                canvas.clipRect(b)
                canvas.drawBitmap(bitmap, matrix, paint)
                canvas.restore()
            }
        }
        if (overlayColor != 0) {
            overlayPaint.color = overlayColor
            canvas.drawRect(b, overlayPaint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 自定义书架风格配置（支持用户通过 `.zip` 皮肤包无限扩展新的书架风格）
 *
 * 支持 6 种基础空间架构 (`layoutMode`) + 完整自定义贴图/色板/粒子动效/封面排版：
 * - `"cabinet"`：多宝阁/书斋双层搁板陈列（支持自定义搁板贴图、书斋案台装饰、背景纹理与飘落粒子）
 * - `"carousel"`：全息/画卷中央聚焦抽卡（中央立体放大展示 + 底部缩略卡轨 + 左右切换）
 * - `"grid"`：艺术画廊/陈列墙网格（每页 6 本精致画框陈列 + 顶部聚光光晕 + 横滑翻架）
 * - `"gallery"`：第一人称 3D 展厅（复用收藏馆 90° 透视相机）
 * - `"cloud"`：天际漂浮云架（复用云端多架循环天色）
 * - `"galaxy"`：天球星座漫游（复用星海 30°–70° 环带）
 */
data class CustomShelfSpec(
    val key: String,
    val name: String,                  // 书架风格名称（顶部悬浮岛最多展示前 3 个字符）
    val author: String = "DIY 用户",
    val description: String = "",
    val layoutMode: String = "cabinet", // cabinet | carousel | grid | gallery | cloud | galaxy
    val bgColor: Int = 0xFF1E2430.toInt(),
    val bgGradient: IntArray = intArrayOf(0xFF1E2430.toInt(), 0xFF12161F.toInt()),
    val bgImagePath: String? = null,
    val bgScaleMode: String = "cover",
    val bgOverlayColor: Int = 0x00000000,
    val islandBgColor: Int = 0xCC1E2430.toInt(),
    val islandStrokeColor: Int = 0x4DA0B4FF,
    val islandTextColor: Int = 0xFFF0F4FF.toInt(),
    val accentColor: Int = 0xFF4C86CF.toInt(),
    val lightStatusBar: Boolean = false,
    val frameBgColor: Int = 0x4010141D,
    val frameStrokeColor: Int = 0x664C86CF,
    val boardColor: Int = 0xFF3A465E.toInt(),
    val boardHighlightColor: Int = 0xFF5A6B8C.toInt(),
    val boardImagePath: String? = null,
    val deskImagePath: String? = null,
    val coverFrameColor: Int = 0xFFF4EFE6.toInt(),
    val coverStrokeColor: Int = 0xFF8A7A5C.toInt(),
    val coverTextColor: Int = 0xFFFDFBF7.toInt(),
    val coverCornerDp: Float = 6f,
    val verticalTitle: Boolean = true,
    val serifTitle: Boolean = true,
    val spinePalette: IntArray = intArrayOf(
        0xFF3B5978.toInt(), 0xFF2D465E.toInt(), 0xFF4A6B82.toInt(), 0xFF5E4B66.toInt()
    ),
    val particleEffect: String = "none", // none | petals | fireflies | snow | stars | rain
    val particleColor: Int = 0x88FFFFFF.toInt(),
    val customFontPath: String? = null
) {
    val shortTitle: String
        get() = name.trim().let { if (it.length > 3) it.take(3) else it.ifEmpty { "书架" } }
}

/**
 * 自定义主题与自定义书架风格（ZIP 皮肤包）解析与管理器
 *
 * 支持三种压缩包模式：
 * 1. 纯阅读器主题包（含 `theme.json` / `skin.json`）
 * 2. 纯书架风格包（含 `shelf.json` 或 `theme.json` 中声明 `"type": "shelf"` / `"shelf": {...}`）
 * 3. 全套联名皮肤包（同一个 `.zip` 内同时包含阅读器主题与书架风格，一键同时安装到书架和阅读器）
 */
object CustomThemeManager {
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024L

    data class InstalledSkin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val spec: ThemeSpec,
        val dir: File,
        val shelfSpec: CustomShelfSpec? = null
    )

    data class InstalledShelfSkin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val spec: CustomShelfSpec,
        val dir: File,
        val readerSpec: ThemeSpec? = null
    )

    fun parseColorSafe(raw: String?, defaultColor: Int): Int {
        if (raw.isNullOrBlank()) return defaultColor
        val s = raw.trim()
        return try {
            when {
                s.startsWith("#") -> {
                    val hex = s.substring(1)
                    when (hex.length) {
                        6 -> (0xFF000000L or hex.toLong(16)).toInt()
                        8 -> hex.toLong(16).toInt()
                        3 -> {
                            val r = hex[0].digitToInt(16)
                            val g = hex[1].digitToInt(16)
                            val b = hex[2].digitToInt(16)
                            (0xFF000000L or ((r * 17L) shl 16) or ((g * 17L) shl 8) or (b * 17L)).toInt()
                        }
                        else -> defaultColor
                    }
                }
                s.startsWith("0x", ignoreCase = true) -> {
                    val hex = s.substring(2)
                    if (hex.length <= 6) (0xFF000000L or hex.toLong(16)).toInt() else hex.toLong(16).toInt()
                }
                else -> Color.parseColor(s)
            }
        } catch (e: Exception) {
            defaultColor
        }
    }

    /**
     * 解析 `theme.json` 字符串并结合解压目录构建 `ThemeSpec`
     */
    fun parseThemeSpecFromJson(key: String, jsonText: String, skinDir: File? = null): ThemeSpec {
        val root = JSONObject(jsonText)
        val readerObj = root.optJSONObject("reader") ?: root
        val baseKey = readerObj.optString("base", root.optString("base", "day"))
        val base = ThemeSpec.builtIn()[baseKey] ?: ThemeSpec.builtIn()["day"]!!
        val colors = readerObj.optJSONObject("colors") ?: root.optJSONObject("colors") ?: readerObj
        val skin = readerObj.optJSONObject("skin") ?: root.optJSONObject("skin") ?: readerObj
        val name = readerObj.optString("name", root.optString("name", "自定义皮肤")).ifBlank { "自定义皮肤" }

        val bg = parseColorSafe(colors.optString("bg"), base.bg)
        val fg = parseColorSafe(colors.optString("fg"), base.fg)
        val fgMeta = parseColorSafe(colors.optString("fgMeta"), base.fgMeta)
        val fgTitle = parseColorSafe(colors.optString("fgTitle"), base.fgTitle)
        val fgSub = parseColorSafe(colors.optString("fgSub"), base.fgSub)
        val bar = parseColorSafe(colors.optString("bar"), base.bar)
        val card = parseColorSafe(colors.optString("card"), base.card)
        val cardFg = parseColorSafe(colors.optString("cardFg"), base.cardFg)
        val pill = parseColorSafe(colors.optString("pill"), base.pill)
        val divider = parseColorSafe(colors.optString("divider"), base.divider)
        val accent = parseColorSafe(colors.optString("accent"), base.accent)
        val chipT = parseColorSafe(colors.optString("chipT"), base.chipT)
        val drawerBg = parseColorSafe(colors.optString("drawerBg"), base.drawerBg)
        val menuIco = parseColorSafe(colors.optString("menuIco"), base.menuIco)

        val gradArr = colors.optJSONArray("bgGradient") ?: readerObj.optJSONArray("bgGradient")
        val bgGradient = if (gradArr != null && gradArr.length() >= 2) {
            IntArray(gradArr.length()) { i -> parseColorSafe(gradArr.optString(i), bg) }
        } else null

        val bgScaleMode = skin.optString("bgScaleMode", "cover").ifBlank { "cover" }
        val bgOverlayColor = parseColorSafe(skin.optString("bgOverlayColor"), 0x00000000)
        val cardRadiusDp = skin.optDouble("cardRadiusDp", 12.0).toFloat().coerceIn(0f, 32f)
        val cardStrokeColor = parseColorSafe(skin.optString("cardStrokeColor"), 0x00000000)
        val cardStrokeWidthDp = skin.optDouble("cardStrokeWidthDp", 0.0).toFloat().coerceIn(0f, 6f)

        fun resolveAsset(fieldKey: String, fallbacks: List<String>): String? {
            val dir = skinDir ?: return null
            val explicit = skin.optString(fieldKey, "").trim()
            if (explicit.isNotEmpty()) {
                val f = File(dir, explicit)
                if (f.exists() && f.isFile && f.canonicalPath.startsWith(dir.canonicalPath)) return f.absolutePath
            }
            for (cand in fallbacks) {
                val f = File(dir, cand)
                if (f.exists() && f.isFile) return f.absolutePath
            }
            return null
        }

        val bgImagePath = resolveAsset("bgImage", listOf("bg.png", "bg.jpg", "bg.jpeg", "bg.webp", "background.png", "background.jpg"))
        val barImagePath = resolveAsset("barImage", listOf("bar.png", "bar.jpg", "toolbar.png"))
        val cardImagePath = resolveAsset("cardImage", listOf("card.png", "card.jpg", "panel.png"))
        val customFontPath = resolveAsset("font", listOf("font.ttf", "font.otf"))

        return ThemeSpec(
            key = key,
            bg = bg,
            bgGradient = bgGradient,
            fg = fg,
            fgMeta = fgMeta,
            fgTitle = fgTitle,
            fgSub = fgSub,
            bar = bar,
            card = card,
            cardFg = cardFg,
            pill = pill,
            divider = divider,
            accent = accent,
            chipT = chipT,
            drawerBg = drawerBg,
            menuIco = menuIco,
            name = name,
            bgImagePath = bgImagePath,
            bgScaleMode = bgScaleMode,
            bgOverlayColor = bgOverlayColor,
            barImagePath = barImagePath,
            cardImagePath = cardImagePath,
            cardRadiusDp = cardRadiusDp,
            cardStrokeColor = cardStrokeColor,
            cardStrokeWidthDp = cardStrokeWidthDp,
            customFontPath = customFontPath
        )
    }

    /**
     * 解析 `shelf.json`（或 `theme.json` 中的 `shelf` 节点）并结合解压目录构建 `CustomShelfSpec`
     */
    fun parseShelfSpecFromJson(key: String, jsonText: String, skinDir: File? = null): CustomShelfSpec {
        val root = JSONObject(jsonText)
        val shelfObj = root.optJSONObject("shelf") ?: root
        val colors = shelfObj.optJSONObject("colors") ?: root.optJSONObject("colors") ?: shelfObj
        val skin = shelfObj.optJSONObject("skin") ?: root.optJSONObject("skin") ?: shelfObj

        val name = shelfObj.optString("name", root.optString("name", "新书架")).ifBlank { "新书架" }
        val author = shelfObj.optString("author", root.optString("author", "DIY 用户"))
        val desc = shelfObj.optString("description", root.optString("description", ""))
        val layoutMode = shelfObj.optString("layoutMode", shelfObj.optString("layout", "cabinet"))
            .lowercase()
            .let { if (it in setOf("cabinet", "carousel", "grid", "gallery", "cloud", "galaxy")) it else "cabinet" }

        val bgColor = parseColorSafe(colors.optString("bg", colors.optString("bgColor")), 0xFF232B38.toInt())
        val gradArr = colors.optJSONArray("bgGradient") ?: shelfObj.optJSONArray("bgGradient")
        val bgGradient = if (gradArr != null && gradArr.length() >= 2) {
            IntArray(gradArr.length()) { i -> parseColorSafe(gradArr.optString(i), bgColor) }
        } else {
            intArrayOf(bgColor, bgColor)
        }

        val lum = (Color.red(bgColor) * 299 + Color.green(bgColor) * 587 + Color.blue(bgColor) * 114) / 1000
        val defaultLight = lum >= 150
        val lightStatusBar = shelfObj.optBoolean("lightStatusBar", defaultLight)

        val islandBgColor = parseColorSafe(
            colors.optString("islandBg", colors.optString("islandBgColor")),
            if (defaultLight) 0xD8FFFFFF.toInt() else 0xB81E2433.toInt()
        )
        val islandStrokeColor = parseColorSafe(
            colors.optString("islandStroke", colors.optString("islandStrokeColor")),
            if (defaultLight) 0x3322252B else 0x4DA0B4FF
        )
        val islandTextColor = parseColorSafe(
            colors.optString("islandText", colors.optString("fg")),
            if (defaultLight) 0xFF243042.toInt() else 0xFFF0F4FF.toInt()
        )
        val accentColor = parseColorSafe(colors.optString("accent"), 0xFF4C86CF.toInt())
        val frameBgColor = parseColorSafe(colors.optString("frameBg"), if (defaultLight) 0x66FFFFFF else 0x4D121824)
        val frameStrokeColor = parseColorSafe(colors.optString("frameStroke"), accentColor)
        val boardColor = parseColorSafe(colors.optString("boardColor", colors.optString("board")), 0xFF5A4632.toInt())
        val boardHighlightColor = parseColorSafe(colors.optString("boardHighlightColor"), accentColor)

        val coverFrameColor = parseColorSafe(colors.optString("coverFrameColor"), 0xFFF5EFE2.toInt())
        val coverStrokeColor = parseColorSafe(colors.optString("coverStrokeColor"), 0xFF8A7A5C.toInt())
        val coverTextColor = parseColorSafe(colors.optString("coverTextColor"), 0xFFFDFBF7.toInt())
        val coverCornerDp = skin.optDouble("coverCornerDp", 5.0).toFloat().coerceIn(0f, 24f)
        val verticalTitle = skin.optBoolean("verticalTitle", layoutMode == "cabinet")
        val serifTitle = skin.optBoolean("serifTitle", true)

        val spineArr = colors.optJSONArray("spinePalette") ?: shelfObj.optJSONArray("spinePalette")
        val spinePalette = if (spineArr != null && spineArr.length() > 0) {
            IntArray(spineArr.length()) { i -> parseColorSafe(spineArr.optString(i), accentColor) }
        } else {
            intArrayOf(0xFF3E5A78.toInt(), 0xFF6B4E3D.toInt(), 0xFF3B6654.toInt(), 0xFF624A70.toInt())
        }

        val particleEffect = skin.optString("particleEffect", shelfObj.optString("particleEffect", "none"))
            .lowercase()
            .let { if (it in setOf("none", "petals", "fireflies", "snow", "stars", "rain")) it else "none" }
        val particleColor = parseColorSafe(skin.optString("particleColor", colors.optString("particleColor")), 0x99FFFFFF.toInt())

        val bgScaleMode = skin.optString("bgScaleMode", "cover").ifBlank { "cover" }
        val bgOverlayColor = parseColorSafe(skin.optString("bgOverlayColor"), 0x00000000)

        fun resolveAsset(fieldKey: String, fallbacks: List<String>): String? {
            val dir = skinDir ?: return null
            val explicit = skin.optString(fieldKey, "").trim()
            if (explicit.isNotEmpty()) {
                val f = File(dir, explicit)
                if (f.exists() && f.isFile && f.canonicalPath.startsWith(dir.canonicalPath)) return f.absolutePath
            }
            for (cand in fallbacks) {
                val f = File(dir, cand)
                if (f.exists() && f.isFile) return f.absolutePath
            }
            return null
        }

        val bgImagePath = resolveAsset("shelfBgImage", listOf("shelf_bg.png", "shelf_bg.jpg", "bg.png", "bg.jpg"))
        val boardImagePath = resolveAsset("boardImage", listOf("board.png", "shelf_board.png"))
        val deskImagePath = resolveAsset("deskImage", listOf("desk.png", "ornament.png"))
        val customFontPath = resolveAsset("font", listOf("font.ttf", "font.otf"))

        return CustomShelfSpec(
            key = key,
            name = name,
            author = author,
            description = desc,
            layoutMode = layoutMode,
            bgColor = bgColor,
            bgGradient = bgGradient,
            bgImagePath = bgImagePath,
            bgScaleMode = bgScaleMode,
            bgOverlayColor = bgOverlayColor,
            islandBgColor = islandBgColor,
            islandStrokeColor = islandStrokeColor,
            islandTextColor = islandTextColor,
            accentColor = accentColor,
            lightStatusBar = lightStatusBar,
            frameBgColor = frameBgColor,
            frameStrokeColor = frameStrokeColor,
            boardColor = boardColor,
            boardHighlightColor = boardHighlightColor,
            boardImagePath = boardImagePath,
            deskImagePath = deskImagePath,
            coverFrameColor = coverFrameColor,
            coverStrokeColor = coverStrokeColor,
            coverTextColor = coverTextColor,
            coverCornerDp = coverCornerDp,
            verticalTitle = verticalTitle,
            serifTitle = serifTitle,
            spinePalette = spinePalette,
            particleEffect = particleEffect,
            particleColor = particleColor,
            customFontPath = customFontPath
        )
    }

    /**
     * 从输入流解压自定义主题/书架风格 ZIP 包到目标目录
     */
    fun extractThemeZipToDir(zipStream: InputStream, targetDir: File): JSONObject {
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalPath
        var foundThemeConfig: File? = null
        var foundShelfConfig: File? = null

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val rawName = entry.name.replace('\\', '/').trimStart('/')
                    val cleanName = if (rawName.count { it == '/' } == 1 &&
                        (rawName.endsWith(".json", true) || rawName.endsWith(".png", true) ||
                            rawName.endsWith(".jpg", true) || rawName.endsWith(".jpeg", true) ||
                            rawName.endsWith(".webp", true) || rawName.endsWith(".ttf", true) ||
                            rawName.endsWith(".otf", true))
                    ) {
                        rawName.substringAfter('/')
                    } else {
                        rawName
                    }
                    val outFile = File(targetDir, cleanName)
                    if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                        throw SecurityException("非法压缩包路径: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_ENTRY_BYTES) throw IllegalArgumentException("主题资源文件过大")
                            fos.write(buf, 0, n)
                        }
                    }
                    val lower = outFile.name.lowercase()
                    if (lower in setOf("theme.json", "skin.json", "manifest.json")) {
                        foundThemeConfig = outFile
                    } else if (lower == "shelf.json") {
                        foundShelfConfig = outFile
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val primaryFile = foundThemeConfig ?: foundShelfConfig
            ?: File(targetDir, "theme.json").takeIf { it.exists() }
            ?: File(targetDir, "shelf.json").takeIf { it.exists() }
            ?: throw IllegalArgumentException("压缩包内缺少 theme.json 或 shelf.json 配置文件")

        val jsonText = primaryFile.readText(Charsets.UTF_8)
        val obj = JSONObject(jsonText)
        if (foundThemeConfig != null && foundThemeConfig!!.name != "theme.json") {
            File(targetDir, "theme.json").writeText(foundThemeConfig!!.readText(Charsets.UTF_8), Charsets.UTF_8)
        }
        if (foundShelfConfig != null && !File(targetDir, "theme.json").exists()) {
            File(targetDir, "theme.json").writeText(jsonText, Charsets.UTF_8)
        }
        return obj
    }

    fun importThemeZip(ctx: Context, uri: Uri): InstalledSkin {
        val rawId = "custom_" + System.currentTimeMillis().toString(36)
        val targetDir = File(Books.dirThemes(ctx), rawId)
        val ins = ctx.contentResolver.openInputStream(uri) ?: error("无法读取主题压缩包")
        val metaObj = ins.use { extractThemeZipToDir(it, targetDir) }
        val baseId = metaObj.optString("id", "").trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .trim('_')
        val skinId = if (baseId.isNotEmpty()) "custom_$baseId" else rawId
        val finalDir = if (skinId != rawId) {
            val d = File(Books.dirThemes(ctx), skinId)
            if (d.exists()) d.deleteRecursively()
            targetDir.renameTo(d)
            d
        } else targetDir

        val jsonFile = File(finalDir, "theme.json")
        val jsonText = jsonFile.readText(Charsets.UTF_8)
        val spec = parseThemeSpecFromJson(skinId, jsonText, finalDir)
        val name = metaObj.optString("name", spec.name)
        val author = metaObj.optString("author", "DIY 用户")
        val desc = metaObj.optString("description", "")

        val list = Prefs.get(ctx).customThemeList()
        val updated = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("id") != skinId) updated.put(item)
        }
        updated.put(
            JSONObject()
                .put("id", skinId)
                .put("name", name)
                .put("author", author)
                .put("description", desc)
                .put("dir", finalDir.name)
        )
        Prefs.get(ctx).saveCustomThemeList(updated)
        ThemeSpec.refreshCustomThemes(ctx)

        // 若该压缩包同时包含 shelf 书架配置或 shelf.json，也同步注册到自定义书架列表
        val shelfFile = File(finalDir, "shelf.json")
        val shelfSpec = if (metaObj.has("shelf") || shelfFile.exists() || metaObj.optString("type") == "shelf") {
            val shelfJson = if (shelfFile.exists()) shelfFile.readText(Charsets.UTF_8) else jsonText
            val sKey = "custom_shelf_${skinId.removePrefix("custom_")}"
            val parsedShelf = parseShelfSpecFromJson(sKey, shelfJson, finalDir)
            registerShelfInPrefs(ctx, sKey, parsedShelf.name, parsedShelf.author, parsedShelf.description, finalDir.name)
            ShelfTheme.refreshCustomShelves(ctx)
            parsedShelf
        } else null

        return InstalledSkin(skinId, name, author, desc, spec, finalDir, shelfSpec)
    }

    fun importShelfStyleZip(ctx: Context, uri: Uri): InstalledShelfSkin {
        val rawId = "custom_shelf_" + System.currentTimeMillis().toString(36)
        val targetDir = File(Books.dirThemes(ctx), rawId)
        val ins = ctx.contentResolver.openInputStream(uri) ?: error("无法读取书架风格压缩包")
        val metaObj = ins.use { extractThemeZipToDir(it, targetDir) }
        val baseId = metaObj.optString("id", "").trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .trim('_')
        val shelfId = if (baseId.isNotEmpty()) "custom_shelf_${baseId.removePrefix("custom_shelf_").removePrefix("custom_")}" else rawId
        val finalDir = if (shelfId != rawId) {
            val d = File(Books.dirThemes(ctx), shelfId)
            if (d.exists()) d.deleteRecursively()
            targetDir.renameTo(d)
            d
        } else targetDir

        val shelfFile = File(finalDir, "shelf.json").takeIf { it.exists() } ?: File(finalDir, "theme.json")
        val shelfJsonText = shelfFile.readText(Charsets.UTF_8)
        val spec = parseShelfSpecFromJson(shelfId, shelfJsonText, finalDir)

        registerShelfInPrefs(ctx, shelfId, spec.name, spec.author, spec.description, finalDir.name)
        ShelfTheme.refreshCustomShelves(ctx)

        // 若包内同时含有阅读器 colors/reader 节点，也同步注册为阅读器皮肤
        val readerSpec = if (metaObj.has("reader") || (metaObj.has("colors") && metaObj.optString("type") != "shelf")) {
            val rKey = "custom_${shelfId.removePrefix("custom_shelf_")}"
            val rSpec = parseThemeSpecFromJson(rKey, shelfJsonText, finalDir)
            val list = Prefs.get(ctx).customThemeList()
            val updated = JSONArray()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (item.optString("id") != rKey) updated.put(item)
            }
            updated.put(
                JSONObject()
                    .put("id", rKey)
                    .put("name", rSpec.name)
                    .put("author", spec.author)
                    .put("description", spec.description)
                    .put("dir", finalDir.name)
            )
            Prefs.get(ctx).saveCustomThemeList(updated)
            ThemeSpec.refreshCustomThemes(ctx)
            rSpec
        } else null

        return InstalledShelfSkin(shelfId, spec.name, spec.author, spec.description, spec, finalDir, readerSpec)
    }

    private fun registerShelfInPrefs(ctx: Context, id: String, name: String, author: String, desc: String, dirName: String) {
        val list = Prefs.get(ctx).customShelfList()
        val updated = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("id") != id) updated.put(item)
        }
        updated.put(
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("author", author)
                .put("description", desc)
                .put("dir", dirName)
        )
        Prefs.get(ctx).saveCustomShelfList(updated)
    }

    fun removeCustomTheme(ctx: Context, skinId: String) {
        val prefs = Prefs.get(ctx)
        val list = prefs.customThemeList()
        val updated = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("id") == skinId) {
                val dirName = item.optString("dir", skinId)
                File(Books.dirThemes(ctx), dirName).deleteRecursively()
            } else {
                updated.put(item)
            }
        }
        prefs.saveCustomThemeList(updated)
        if (prefs.theme == skinId) {
            prefs.theme = "day"
        }
        ThemeSpec.refreshCustomThemes(ctx)
    }

    fun removeCustomShelf(ctx: Context, shelfId: String) {
        val prefs = Prefs.get(ctx)
        val list = prefs.customShelfList()
        val updated = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("id") == shelfId) {
                val dirName = item.optString("dir", shelfId)
                File(Books.dirThemes(ctx), dirName).deleteRecursively()
            } else {
                updated.put(item)
            }
        }
        prefs.saveCustomShelfList(updated)
        if (prefs.shelfTheme == shelfId) {
            prefs.shelfTheme = "cloud"
        }
        ShelfTheme.refreshCustomShelves(ctx)
    }

    fun loadInstalledThemes(ctx: Context): Map<String, ThemeSpec> {
        val out = LinkedHashMap<String, ThemeSpec>()
        val baseDir = Books.dirThemes(ctx)
        val list = Prefs.get(ctx).customThemeList()
        for (i in 0 until list.length()) {
            try {
                val item = list.getJSONObject(i)
                val id = item.optString("id")
                val dirName = item.optString("dir", id)
                val dir = File(baseDir, dirName)
                val jsonFile = File(dir, "theme.json")
                if (id.isNotEmpty() && jsonFile.exists()) {
                    out[id] = parseThemeSpecFromJson(id, jsonFile.readText(Charsets.UTF_8), dir)
                }
            } catch (e: Exception) { }
        }
        return out
    }

    fun loadInstalledShelves(ctx: Context): Map<String, CustomShelfSpec> {
        val out = LinkedHashMap<String, CustomShelfSpec>()
        val baseDir = Books.dirThemes(ctx)
        val list = Prefs.get(ctx).customShelfList()
        for (i in 0 until list.length()) {
            try {
                val item = list.getJSONObject(i)
                val id = item.optString("id")
                val dirName = item.optString("dir", id)
                val dir = File(baseDir, dirName)
                val jsonFile = File(dir, "shelf.json").takeIf { it.exists() } ?: File(dir, "theme.json")
                if (id.isNotEmpty() && jsonFile.exists()) {
                    out[id] = parseShelfSpecFromJson(id, jsonFile.readText(Charsets.UTF_8), dir)
                }
            } catch (e: Exception) { }
        }
        return out
    }
}

/** 书架主题（内置 5 种：古朴/简约/收藏馆/云端/星河 + 自定义扩展书架风格） */
data class ShelfTheme(
    val key: String, val name: String,
    val bg: IntArray?, val bgColor: Int, val fg: Int, val serif: Boolean,
    val cardBorder: Int?, val cardBorderWidth: Int, val coverColors: IntArray,
    val popBg: Int, val itemBg: Int, val cancelColor: Int,
) {
    companion object {
        @Volatile
        private var customShelves: Map<String, CustomShelfSpec> = emptyMap()

        fun refreshCustomShelves(ctx: Context) {
            customShelves = CustomThemeManager.loadInstalledShelves(ctx)
        }

        fun customSpecs(): Map<String, CustomShelfSpec> = customShelves

        fun customOf(key: String): CustomShelfSpec? = customShelves[key]

        fun builtIn(): List<ShelfTheme> = listOf(
            ShelfTheme("antique", "古朴",
                intArrayOf(0xFFE8D9BC.toInt(), 0xFFDECBA3.toInt(), 0xFFD3BC8E.toInt()),
                0xFFE8D9BC.toInt(), 0xFF4A3820.toInt(), true,
                0xFFB99C6B.toInt(), 1, intArrayOf(0xFF8C6A3F.toInt(), 0xFF6B4E2A.toInt(), 0xFF4E3820.toInt()),
                0xFFF7EFDD.toInt(), 0xFFE8DBBB.toInt(), 0xFF8C6A3F.toInt()),
            ShelfTheme("minimal", "简约", null,
                0xFFF5F6F8.toInt(), 0xFF26292E.toInt(), false,
                0xFFE6E8EC.toInt(), 1, intArrayOf(0xFF9AA5B1.toInt(), 0xFF6B7683.toInt(), 0xFF4A525C.toInt()),
                0xFFFFFFFF.toInt(), 0xFFF0F2F6.toInt(), 0xFF777777.toInt()),
            ShelfTheme("gallery", "收藏馆",
                intArrayOf(0xFF41231F.toInt(), 0xFF5A2E28.toInt(), 0xFF3A1E1A.toInt()),
                0xFF41231F.toInt(), 0xFFEFD9A7.toInt(), true,
                0xFFC9A25E.toInt(), 2, intArrayOf(0xFF7B5B33.toInt(), 0xFF54391E.toInt(), 0xFF382512.toInt()),
                0xFF4E2A24.toInt(), 0xFF6A3A32.toInt(), 0xFFB99C6B.toInt()),
            ShelfTheme("cloud", "云端",
                intArrayOf(0xFFBBD9F7.toInt(), 0xFFD6E9FA.toInt(), 0xFFEDF5FD.toInt()),
                0xFFBBD9F7.toInt(), 0xFF33506B.toInt(), false,
                null, 0, intArrayOf(0xFF7FB3EE.toInt(), 0xFF4C86CF.toInt(), 0xFF3568A8.toInt()),
                0xFFFFFFFF.toInt(), 0xFFE9F1F9.toInt(), 0xFF7FA3C4.toInt()),
            ShelfTheme("galaxy", "星河",
                intArrayOf(0xFF0B0E23.toInt(), 0xFF141A3C.toInt(), 0xFF22194A.toInt()),
                0xFF0B0E23.toInt(), 0xFFD9E0F5.toInt(), false,
                0x40A0B4FF.toInt(), 1, intArrayOf(0xFF5E6FB8.toInt(), 0xFF3A4488.toInt(), 0xFF28305E.toInt()),
                0xFF1A2144.toInt(), 0xFF2A3260.toInt(), 0xFF7C89C4.toInt()),
        )

        fun all(): List<ShelfTheme> = builtIn()
        fun of(key: String): ShelfTheme = builtIn().find { it.key == key } ?: builtIn()[3]
    }
}
