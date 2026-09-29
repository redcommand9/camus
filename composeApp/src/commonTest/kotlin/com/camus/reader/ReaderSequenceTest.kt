package com.camus.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderSequenceTest {
    @Test
    fun tearSheetIsAStableLeafBetweenPages() {
        val sheet = TearSheet(id = "after-five", afterPage = 5)
        val sequence = buildReaderSequence(pageCount = 8, sheets = listOf(sheet))

        val pageFive = "page-5"
        val tear = "tear-after-five"
        val originalPageSix = "page-6"

        assertEquals(pageFive, sequence[4].key)
        assertEquals(tear, sequence[5].key)
        assertEquals(originalPageSix, sequence[6].key)
        assertEquals(tear, moveCursor(sequence, pageFive, 1))
        assertEquals(originalPageSix, moveCursor(sequence, tear, 1))
        assertEquals(tear, moveCursor(sequence, originalPageSix, -1))
        assertEquals(pageFive, moveCursor(sequence, tear, -1))
    }

    @Test
    fun missingCursorFallsBackToFirstLeafWithoutLooping() {
        val sequence = buildReaderSequence(pageCount = 3, sheets = emptyList())
        assertEquals("page-2", moveCursor(sequence, "deleted-tear", 1))
        assertEquals("page-1", moveCursor(sequence, "deleted-tear", -1))
    }

    @Test
    fun restoredSheetPreservesBothSidesAndNeighbouringPages() {
        val controller = ReaderController()
        controller.openBook(BookSelection("History.pdf", "content://history", BookKind.PDF))
        val sheet = TearSheet("5_1", 5, "Front", "First side", "Back", "Second side")
        controller.restore(listOf(sheet), "tear-5_1", ReaderPageColor.NIGHT, true, 1.5f)
        controller.updatePageCount(8)

        assertEquals(6, controller.displayPageNumber)
        assertEquals(sheet, (controller.currentLeaf as ReaderLeaf.TearPage).sheet)
        controller.move(-1)
        assertEquals("page-5", controller.currentKey)
        controller.move(1)
        assertEquals("tear-5_1", controller.currentKey)
        controller.flipSheet()
        assertEquals(true, controller.sheetFace)
        controller.move(1)
        assertEquals("page-6", controller.currentKey)
        assertEquals(7, controller.displayPageNumber)
    }
}
