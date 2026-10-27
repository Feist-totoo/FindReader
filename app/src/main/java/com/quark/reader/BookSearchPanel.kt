package com.quark.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal class BookSearchPanel(
    context: Context, private val books: List<Books.Meta>,
    private val onPick: (Books.Meta) -> Unit, private val onClose: () -> Unit
) : FrameLayout(context) {
    private val query = EditText(context)
    private val resultCount = TextView(context).apply {
        text = "${books.size}本"; textSize = 11f; setTextColor(0xFF858B96.toInt()); gravity = Gravity.CENTER
    }
    private var matches = books
    private val adapter = object : RecyclerView.Adapter<ResultHolder>() {
        override fun getItemCount() = matches.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): ResultHolder = ResultHolder(TextView(context).apply {
            textSize = 16f
            setTextColor(0xFF242B38.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp(context), 12.dp(context), 18.dp(context), 12.dp(context))
            minHeight = 56.dp(context)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        })
        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val book = matches[position]
            holder.text.text = book.title
            holder.text.contentDescription = "定位 ${book.title}"
            holder.text.setOnClickListener { hideKeyboard(); onPick(book) }
        }
    }
    private class ResultHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    init {
        elevation = 40f.dp(context)
        setBackgroundColor(0xA0080C16.toInt())
        setOnClickListener { hideKeyboard(); onClose() }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = 12f.dp(context)
            background = GradientDrawable().apply { cornerRadius = 22f.dp(context); setColor(0xFAF7F8FB.toInt()) }
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(context), 8.dp(context), 8.dp(context), 8.dp(context))
        }
        query.apply {
            hint = "搜索书名"
            contentDescription = "搜索书名"
            textSize = 17f
            setTextColor(0xFF242B38.toInt())
            setHintTextColor(0xFF858B96.toInt())
            setSingleLine(true)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ ->
                matches.firstOrNull()?.let { hideKeyboard(); onPick(it) }
                true
            }
        }
        row.addView(query, LinearLayout.LayoutParams(0, 48.dp(context), 1f))
        row.addView(resultCount, LinearLayout.LayoutParams(42.dp(context), 48.dp(context)))
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(0xFF4A5262.toInt())
            background = null
            contentDescription = "关闭搜索"
            setOnClickListener { hideKeyboard(); onClose() }
        }, LinearLayout.LayoutParams(48.dp(context), 48.dp(context)))
        panel.addView(row)
        panel.addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@BookSearchPanel.adapter
            clipToPadding = false
            setPadding(0, 0, 0, 10.dp(context))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(16.dp(context), 14.dp(context), 16.dp(context), 16.dp(context))
        })
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                matches = books.mapNotNull { book -> ShelfSearch.score(book.title, s.toString())?.let { book to it } }
                    .sortedByDescending { it.second }.map { it.first }
                adapter.notifyDataSetChanged()
                resultCount.text = "${matches.size}本"
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun showKeyboard() {
        query.requestFocus()
        query.post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        query.clearFocus()
    }
}
