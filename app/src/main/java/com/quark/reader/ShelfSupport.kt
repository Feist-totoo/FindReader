package com.quark.reader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.Normalizer
import java.util.Locale

internal interface ShelfNavigator {
    fun focusBook(id: String)
}

internal fun motionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** One inset owner per screen: every toolbar, sheet and reading control shares the safe area. */
internal object SafeSystemBars {
    fun install(activity: Activity, root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = if (Build.VERSION.SDK_INT >= 26) Color.TRANSPARENT else Color.BLACK
        if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = true
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                bottom + maxOf(bars.bottom, keyboard.bottom))
            // Keep children from applying the same system insets again.
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}

internal object ShelfSearch {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Title words can be entered in either order; abbreviated titles match ordered characters. */
    fun score(title: String, query: String): Int? {
        val titleKey = normalize(title)
        val key = normalize(query)
        if (key.isEmpty()) return 0
        if (titleKey == key) return 1000
        if (titleKey.contains(key)) return 800 - titleKey.indexOf(key)
        val words = query.trim().split(Regex("[\\s，,、·/]+")) .map(::normalize).filter { it.isNotEmpty() }
        if (words.size > 1 && words.all(titleKey::contains)) return 600
        if (key.length < 2) return null
        var cursor = 0
        for (character in titleKey) {
            if (cursor < key.length && key[cursor] == character) cursor++
        }
        return if (cursor == key.length) 300 - (titleKey.length - key.length) else null
    }
}
