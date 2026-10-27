package com.quark.reader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 阅读偏好设置（镜像 PC 版 DEFAULTS） */
class Prefs private constructor(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontIdx: Int get() = sp.getInt("fontIdx", 5); set(v) { sp.edit().putInt("fontIdx", v).apply() }
    var lineSp: Float get() = sp.getFloat("lineSp", 2.02f); set(v) { sp.edit().putFloat("lineSp", v).apply() }
    var paraSp: Float get() = sp.getFloat("paraSp", 0.9f); set(v) { sp.edit().putFloat("paraSp", v).apply() }
    var marginXdpi: Int get() = sp.getInt("marginXdpi", 24); set(v) { sp.edit().putInt("marginXdpi", v).apply() }
    var theme: String get() = sp.getString("theme", "day") ?: "day"; set(v) { sp.edit().putString("theme", v).apply() }
    var night: Boolean get() = sp.getBoolean("night", false); set(v) { sp.edit().putBoolean("night", v).apply() }

    /**
     * 翻页模式：
     * - "scroll": 上下滚动
     * - "ltr": 从左往右
     * - "rtl": 从右往左
     * 兼容旧版存量值 ("cover"/"sim"/"none") 自动映射为 "ltr"
     */
    var pageMode: String
        get() = normalizePageMode(sp.getString("pageMode", "scroll"))
        set(v) { sp.edit().putString("pageMode", normalizePageMode(v)).apply() }

    var bookFont: String get() = sp.getString("bookFont", "system") ?: "system"; set(v) { sp.edit().putString("bookFont", v).apply() }
    var volumePage: Boolean get() = sp.getBoolean("volumePage", true); set(v) { sp.edit().putBoolean("volumePage", v).apply() }
    var tapSidePage: Boolean get() = sp.getBoolean("tapSidePage", false); set(v) { sp.edit().putBoolean("tapSidePage", v).apply() }
    var showStatusBar: Boolean get() = sp.getBoolean("showStatusBar", true); set(v) { sp.edit().putBoolean("showStatusBar", v).apply() }
    var progressScope: String get() = sp.getString("progressScope", "book") ?: "book"; set(v) { sp.edit().putString("progressScope", v).apply() }
    var showProgress: Boolean get() = sp.getBoolean("showProgress", true); set(v) { sp.edit().putBoolean("showProgress", v).apply() }
    var screensaver: String get() = sp.getString("screensaver", "system") ?: "system"; set(v) { sp.edit().putString("screensaver", v).apply() }
    var autoSpeed: Float get() = sp.getFloat("autoSpeed", 1f); set(v) { sp.edit().putFloat("autoSpeed", v).apply() }
    var shelfTheme: String get() = sp.getString("shelfTheme", "cloud") ?: "cloud"; set(v) { sp.edit().putString("shelfTheme", v).apply() }

    /** 每本书的阅读进度 */
    fun readProgress(bookId: String): JSONObject? = sp.getString("prog_$bookId", null)?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }
    fun writeProgress(bookId: String, ci: Int, page: Int, ratio: Float, charOffset: Int = -1) {
        sp.edit().putString(
            "prog_$bookId",
            JSONObject()
                .put("ci", ci)
                .put("page", page)
                .put("ratio", ratio.toDouble())
                .put("charOffset", charOffset)
                .toString()
        ).apply()
    }
    fun clearProgress(bookId: String) { sp.edit().remove("prog_$bookId").apply() }

    /** 书籍元信息列表（书架数据源，含索引缓存路径校验） */
    fun bookList(): JSONArray = try { JSONArray(sp.getString("bookList", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveBookList(list: JSONArray) { sp.edit().putString("bookList", list.toString()).apply() }

    /** 自定义字体列表 */
    fun fontList(): JSONArray = try { JSONArray(sp.getString("fontList", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveFontList(list: JSONArray) { sp.edit().putString("fontList", list.toString()).apply() }

    /** 自定义阅读主题（ZIP 皮肤包）列表 */
    fun customThemeList(): JSONArray = try { JSONArray(sp.getString("customThemeList", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveCustomThemeList(list: JSONArray) { sp.edit().putString("customThemeList", list.toString()).apply() }

    /** 自定义书架风格（ZIP 皮肤包）列表 */
    fun customShelfList(): JSONArray = try { JSONArray(sp.getString("customShelfList", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveCustomShelfList(list: JSONArray) { sp.edit().putString("customShelfList", list.toString()).apply() }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }

        fun normalizePageMode(raw: String?): String = when (raw) {
            "scroll" -> "scroll"
            "rtl" -> "rtl"
            "ltr", "cover", "sim", "none" -> "ltr"
            else -> "scroll"
        }
    }
}
