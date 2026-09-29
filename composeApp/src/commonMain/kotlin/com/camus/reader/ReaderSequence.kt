package com.camus.reader

data class TearSheet(
    val id: String,
    val afterPage: Int,
    val title: String = "Loose leaf",
    val body: String = "",
    val backTitle: String = "",
    val backBody: String = "",
    /**
     * For books whose page breaks move (EPUB): the text position at the top of the page this sheet
     * follows. [afterPage] is worked out from it again whenever the book is laid out anew.
     */
    val anchor: String? = null,
)

sealed interface ReaderLeaf {
    val key: String

    data class BookPage(val number: Int) : ReaderLeaf {
        override val key: String = "page-$number"
    }

    data class TearPage(val sheet: TearSheet) : ReaderLeaf {
        override val key: String = "tear-${sheet.id}"
    }
}

fun buildReaderSequence(pageCount: Int, sheets: List<TearSheet>): List<ReaderLeaf> = buildList {
    for (page in 1..pageCount.coerceAtLeast(1)) {
        add(ReaderLeaf.BookPage(page))
        sheets.filter { it.afterPage == page }.forEach { add(ReaderLeaf.TearPage(it)) }
    }
}

fun moveCursor(sequence: List<ReaderLeaf>, currentKey: String, delta: Int): String {
    if (sequence.isEmpty()) return currentKey
    val currentIndex = sequence.indexOfFirst { it.key == currentKey }.let { if (it < 0) 0 else it }
    val nextIndex = (currentIndex + delta).coerceIn(0, sequence.lastIndex)
    return sequence[nextIndex].key
}
