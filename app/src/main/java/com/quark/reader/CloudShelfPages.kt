package com.quark.reader

/** Shelves grow with books; only the four sky palettes repeat. */
internal object CloudShelfPages {
    fun count(books: Int) = maxOf(1, (books + 6) / 7)
    fun phase(page: Int) = Math.floorMod(page, 4)
}
