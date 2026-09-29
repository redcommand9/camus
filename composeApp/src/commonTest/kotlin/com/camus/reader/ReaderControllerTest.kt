package com.camus.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderControllerTest {
    private val key = "page-1"

    @Test
    fun reselectingOverlappingWordsMergesInsteadOfStackingOpacity() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 5, end = 10)
        controller.addTextHighlight(key, block = 0, start = 8, end = 15)
        controller.addTextHighlight(key, block = 0, start = 5, end = 10)

        assertEquals(listOf(TextHighlight(0, 5, 15, HighlightColor.AMBER)), controller.textHighlightsFor(key))
    }

    @Test
    fun aDifferentColorTrimsWhatItCoversInsteadOfLayeringOnTop() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 0, end = 10)
        controller.highlightColor = HighlightColor.ROSE
        controller.addTextHighlight(key, block = 0, start = 4, end = 6)

        assertEquals(
            listOf(
                TextHighlight(0, 0, 4, HighlightColor.AMBER),
                TextHighlight(0, 6, 10, HighlightColor.AMBER),
                TextHighlight(0, 4, 6, HighlightColor.ROSE),
            ),
            controller.textHighlightsFor(key),
        )
    }

    @Test
    fun highlightsInDifferentBlocksAreIndependent() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 2, end = 9)
        controller.addTextHighlight(key, block = 1, start = 0, end = 4)

        assertEquals(2, controller.textHighlightsFor(key).size)
    }

    @Test
    fun highlightsSurviveTheBookBeingLaidOutAgain() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 0, end = 3)

        controller.updateParagraph(ParagraphSettings(lineSpacing = 1.8f, justify = true))
        controller.changeZoom(.5f)

        assertEquals(1, controller.textHighlightsFor(key).size)
    }

    @Test
    fun clearingThePageOnlyRemovesHighlightsThatTouchIt() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 0, end = 3)
        controller.addTextHighlight(key, block = 5, start = 0, end = 3)
        controller.pageTextSpans = listOf(TextSpan(key, block = 0, start = 0, end = 40))

        assertEquals(true, controller.hasHighlightsOnPage())
        controller.erasePageHighlights()

        assertEquals(listOf(5), controller.textHighlightsFor(key).map { it.block })
    }

    @Test
    fun paragraphSettingsAreClampedToTheSupportedRange() {
        val wild = ParagraphSettings(lineSpacing = 9f, paragraphSpacing = -5f, firstLineIndent = 12f).normalized()
        assertEquals(ParagraphSettings.MAX_LINE_SPACING, wild.lineSpacing, absoluteTolerance = 0.001f)
        assertEquals(0f, wild.paragraphSpacing, absoluteTolerance = 0.001f)
        assertEquals(ParagraphSettings.MAX_FIRST_LINE_INDENT, wild.firstLineIndent, absoluteTolerance = 0.001f)
    }

    @Test
    fun aNoteIsAttachedToItsHighlightAndSurvivesTheHighlightBeingExtended() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 5, end = 10)
        val ref = HighlightRef.Text(key, 0, 5, 10)
        assertEquals("", controller.noteFor(ref))

        controller.setNote(ref, "check this")
        assertEquals("check this", controller.noteFor(ref))

        // Selecting across the highlight merges it into a wider one, which keeps the note.
        controller.addTextHighlight(key, block = 0, start = 8, end = 15)
        assertNull(controller.noteFor(ref)) // the old range no longer exists
        assertEquals("check this", controller.noteFor(HighlightRef.Text(key, 0, 5, 15)))
    }

    @Test
    fun mergingTwoNotedHighlightsKeepsBothNotes() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 0, end = 4)
        controller.setNote(HighlightRef.Text(key, 0, 0, 4), "one")
        controller.addTextHighlight(key, block = 0, start = 6, end = 10)
        controller.setNote(HighlightRef.Text(key, 0, 6, 10), "two")

        controller.addTextHighlight(key, block = 0, start = 3, end = 7)

        assertEquals("one\n\ntwo", controller.noteFor(HighlightRef.Text(key, 0, 0, 10)))
    }

    @Test
    fun aHighlightSplitByAnotherColorKeepsItsNoteOnTheFirstHalfOnly() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 0, end = 10)
        controller.setNote(HighlightRef.Text(key, 0, 0, 10), "keep")
        controller.highlightColor = HighlightColor.ROSE
        controller.addTextHighlight(key, block = 0, start = 4, end = 6)

        assertEquals("keep", controller.noteFor(HighlightRef.Text(key, 0, 0, 4)))
        assertEquals("", controller.noteFor(HighlightRef.Text(key, 0, 6, 10)))
    }

    @Test
    fun aPdfNoteIsSharedByEveryRectangleOfItsHighlight() {
        val controller = ReaderController()
        val rect = PdfRect(.1f, .1f, .5f, .2f)
        controller.addPdfHighlight(key, listOf(rect, rect.copy(top = .3f, bottom = .4f)))
        val ref = HighlightRef.Pdf(key, group = 1)

        controller.setNote(ref, "on both lines")

        assertEquals(listOf("on both lines", "on both lines"), controller.pdfHighlightsFor(key).map { it.note })
        controller.removeHighlight(ref)
        assertNull(controller.noteFor(ref))
    }

    @Test
    fun openingTheDrawerTracksTheHighlightAndClosingClearsIt() {
        val controller = ReaderController()
        controller.addTextHighlight(key, block = 0, start = 0, end = 3)
        val ref = HighlightRef.Text(key, 0, 0, 3)

        controller.showNote(ref, "the")
        assertEquals(OpenNote(ref, "the"), controller.openNote)
        controller.closeNote()
        assertNull(controller.openNote)
    }
}
