package com.camus.reader

data class LibraryEntry(
    val book: BookSelection,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val position: Int = 0,
    val total: Int = 0,
    /** True when the book was brought in through Pull the Guten (PTG) rather than picked from the device. */
    val fromPtg: Boolean = false,
) {
    val completed: Boolean get() = total > 0 && position >= total
    val started: Boolean get() = total > 0 && position > 1
    val title: String get() = book.displayName.substringBeforeLast('.', book.displayName).replace('_', ' ').trim()
    val progress: Float get() = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
}
