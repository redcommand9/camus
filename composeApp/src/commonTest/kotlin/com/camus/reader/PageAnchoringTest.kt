package com.camus.reader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A layout of a book whose text is numbered 0, 1, 2…: page n starts at [starts][n-1]. Two layouts
 * of the same text stand in for, say, normal and fullscreen.
 */
private class FakeLayout(private val starts: List<Int>) : PageAnchors {
    override fun anchorOf(page: Int): String? = starts.getOrNull(page - 1)?.toString()
    override fun pageOf(anchor: String): Int? {
        val position = anchor.toIntOrNull() ?: return null
        return starts.indexOfLast { it <= position }.coerceAtLeast(0) + 1
    }
}

class PageAnchoringTest {
    // 100 characters of text: 10 pages of 10 in the "normal" layout, 5 pages of 20 when zoomed out.
    private val normal = FakeLayout((0 until 100 step 10).toList())
    private val zoomedOut = FakeLayout((0 until 100 step 20).toList())
    // Uneven page breaks, so page numbers don't map neatly between the layouts.
    private val odd = FakeLayout(listOf(0, 7, 19, 33, 41, 58, 64, 77, 90))

    private fun opened(): ReaderController = ReaderController().apply {
        openBook(BookSelection("Book", "content://book", BookKind.EPUB))
        applyLayout(10, normal)
    }

    private fun ReaderController.relayout(layout: FakeLayout, pages: Int) = applyLayout(pages, layout)

    @Test
    fun goingBackAndForthBetweenLayoutsNeverDrifts() {
        val controller = opened()
        controller.jumpTo("page-7") // text 60..69

        repeat(5) {
            controller.relayout(odd, 9)
            assertEquals("page-6", controller.currentKey) // 58..63 holds position 60
            controller.relayout(zoomedOut, 5)
            assertEquals("page-4", controller.currentKey) // 60..79
            controller.relayout(normal, 10)
            assertEquals("page-7", controller.currentKey)
        }
    }

    @Test
    fun bookmarksFollowTheirWords() {
        val controller = opened()
        controller.jumpTo("page-4") // text 30..39
        controller.toggleBookmark()
        controller.jumpTo("page-9")

        controller.relayout(odd, 9)
        assertEquals(listOf("page-3"), controller.bookmarks) // 19..32 holds position 30
        controller.relayout(normal, 10)
        assertEquals(listOf("page-4"), controller.bookmarks)
    }

    @Test
    fun removingABookmarkRemovesEveryMarkThatNowSharesThePage() {
        val controller = opened()
        controller.jumpTo("page-1"); controller.toggleBookmark() // text 0
        controller.jumpTo("page-2"); controller.toggleBookmark() // text 10
        controller.relayout(zoomedOut, 5) // both on page 1 now
        assertEquals(listOf("page-1"), controller.bookmarks)

        controller.jumpTo("page-2")
        controller.jumpTo("page-1")
        controller.toggleBookmark()

        controller.relayout(normal, 10)
        assertEquals(emptyList(), controller.bookmarks)
    }

    @Test
    fun aLooseSheetStaysAfterTheSameText() {
        val controller = opened()
        controller.jumpTo("page-6") // text 50..59
        controller.insertSheet()
        val sheetKey = controller.currentKey

        controller.relayout(zoomedOut, 5)
        assertEquals(3, controller.sheets.single().afterPage) // 40..59
        assertEquals(sheetKey, controller.currentKey) // still reading the sheet
        controller.relayout(normal, 10)
        assertEquals(6, controller.sheets.single().afterPage)
    }

    @Test
    fun aSheetAddedBesideAnotherKeepsItsPlace() {
        val controller = opened()
        controller.jumpTo("page-6")
        controller.insertSheet()
        controller.insertSheet() // added while on the first sheet

        controller.relayout(odd, 9)
        assertEquals(listOf(5, 5), controller.sheets.map { it.afterPage }) // 41..57 holds position 50
    }

    @Test
    fun theSavedPlaceIsRestoredInWhateverLayoutTheBookOpensWith() {
        // Saved while reading page 7 of the normal layout.
        val saved = opened().apply { jumpTo("page-7") }
        val controller = ReaderController()
        controller.openBook(BookSelection("Book", "content://book", BookKind.EPUB))
        controller.restore(emptyList(), saved.currentKey, ReaderPageColor.PAPER, false, 1f, saved.bookmarkMarks, saved.cursorAnchor)

        controller.relayout(zoomedOut, 5)

        assertEquals("page-4", controller.currentKey)
    }

    @Test
    fun pageNumbersSavedByOlderVersionsArePinnedToTextOnce() {
        val controller = ReaderController()
        controller.openBook(BookSelection("Book", "content://book", BookKind.EPUB))
        controller.restore(listOf(TearSheet("3_1", afterPage = 3)), "page-5", ReaderPageColor.PAPER, false, 1f, listOf("page-2"))

        controller.relayout(normal, 10)
        assertEquals("page-5", controller.currentKey)
        assertEquals(listOf("page-2"), controller.bookmarks)

        controller.relayout(zoomedOut, 5)
        assertEquals("page-3", controller.currentKey) // text 40
        assertEquals(listOf("page-1"), controller.bookmarks) // text 10
        assertEquals(2, controller.sheets.single().afterPage) // text 20
    }

    @Test
    fun booksWithFixedPagesKeepPlainPageBookmarks() {
        val controller = ReaderController()
        controller.openBook(BookSelection("Doc", "content://doc", BookKind.PDF))
        controller.updatePageCount(12)
        controller.jumpTo("page-4")
        controller.toggleBookmark()

        assertEquals(listOf("page-4"), controller.bookmarkMarks)
        assertEquals(listOf("page-4"), controller.bookmarks)
    }
}
