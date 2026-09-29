package com.camus.reader

enum class BookKind { PDF, EPUB }

data class BookSelection(
    val displayName: String,
    val location: String,
    val kind: BookKind,
)

fun bookKindForName(name: String): BookKind? = when {
    name.endsWith(".pdf", ignoreCase = true) -> BookKind.PDF
    name.endsWith(".epub", ignoreCase = true) -> BookKind.EPUB
    else -> null
}
