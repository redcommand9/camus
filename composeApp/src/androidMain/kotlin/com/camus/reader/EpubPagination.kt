package com.camus.reader

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

internal data class EpubPageBlock(
    val kind: EpubBlockKind,
    val text: AnnotatedString? = null,
    val headingLevel: Int = 0,
    val image: Bitmap? = null,
    val imageHeightPx: Int = 0,
    /**
     * Space that belongs above this block. The paginator reserves exactly this much when it packs
     * the page, so the renderer must draw exactly this much (see EpubBlockView) - otherwise
     * paragraphs run together and the bottom of the page is left empty.
     */
    val gapBeforePx: Int = 0,
    /** True for the tail of a paragraph that started on the previous page (no first-line indent). */
    val isContinuation: Boolean = false,
    /**
     * Where this fragment comes from: the block's index within its chapter and the offset of the
     * fragment's first character inside that block's full text. A paragraph split over two pages
     * gives two fragments of the same block. This is what lets highlights and the reading position
     * survive laying the book out again.
     */
    val sourceBlock: Int = -1,
    val sourceStart: Int = 0,
)

internal data class EpubPage(val chapterIndex: Int, val blocks: List<EpubPageBlock>)

internal data class EpubPagination(
    val pages: List<EpubPage>,
    val chapterStartPage: List<Int>,
    /** blockStartPage[chapter][block] = index of the page on which that block begins. */
    val blockStartPage: List<IntArray>,
)

internal data class EpubMetrics(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val ink: Color,
    val baseFontSizeSp: Float,
    val paragraphGapPx: Int,
    val headingGapPx: Int,
    val paragraph: ParagraphSettings = ParagraphSettings(),
    val fontFamily: FontFamily = FontFamily.Serif,
) {
    val bodyStyle: TextStyle = TextStyle(
        fontSize = baseFontSizeSp.sp,
        lineHeight = (baseFontSizeSp * paragraph.lineSpacing).sp,
        color = ink,
        fontFamily = fontFamily,
        textAlign = if (paragraph.justify) TextAlign.Justify else TextAlign.Start,
        textIndent = if (paragraph.firstLineIndent > 0f) TextIndent(firstLine = (baseFontSizeSp * paragraph.firstLineIndent).sp) else null,
    )

    /** Body style for the tail of a paragraph that was split across a page break. */
    val continuationStyle: TextStyle = bodyStyle.copy(textIndent = null)

    fun headingStyle(level: Int): TextStyle {
        val clamped = level.coerceIn(1, 6)
        return TextStyle(
            fontSize = (baseFontSizeSp * (1.6f - (clamped - 1) * 0.12f).coerceAtLeast(1.05f)).sp,
            lineHeight = (baseFontSizeSp * 2f).sp,
            color = ink,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Packs a book's blocks into fixed-size pages for the given viewport, splitting paragraphs at
 * line boundaries the same way a real page break would. Recomputed only when size, zoom, ink
 * color or paragraph settings change - not on every recomposition - by the caller (see
 * AndroidEpubReader.kt).
 *
 * Each chapter starts on a fresh page. That is a deliberate simplification versus the website's
 * continuous reflow: it makes the "Chapters" jump list land exactly on a page boundary and keeps
 * this function easy to reason about, at the cost of sometimes leaving a shorter final page.
 *
 * Besides the pages it records, for every block of every chapter, the page the block starts on
 * ([EpubPagination.blockStartPage]) so table-of-contents entries that point into the middle of a
 * chapter file can jump to the right page.
 */
internal fun paginateEpub(book: NativeEpubBook, metrics: EpubMetrics, textMeasurer: TextMeasurer): EpubPagination {
    val pages = ArrayList<EpubPage>()
    val chapterStartPage = ArrayList<Int>()
    val blockStartPages = ArrayList<IntArray>()
    var currentBlocks = ArrayList<EpubPageBlock>()
    var remaining = metrics.contentHeightPx
    var firstOnPage = true
    var currentChapter = 0

    // Bookkeeping for blockStartPages: which block of the current chapter is being placed, and
    // whether its first fragment has been recorded yet.
    var starts = IntArray(0)
    var blockIndex = 0
    var started = false

    fun markStart() {
        if (!started && blockIndex in starts.indices) {
            starts[blockIndex] = pages.size
            started = true
        }
    }

    fun closePage() {
        if (currentBlocks.isNotEmpty()) pages.add(EpubPage(currentChapter, currentBlocks))
        currentBlocks = ArrayList()
        remaining = metrics.contentHeightPx
        firstOnPage = true
    }

    fun placeImage(bitmap: Bitmap) {
        val scale = metrics.contentWidthPx.toFloat() / bitmap.width.toFloat()
        val heightPx = (bitmap.height * scale).toInt().coerceIn(1, metrics.contentHeightPx)
        val gap = if (firstOnPage) 0 else metrics.paragraphGapPx
        if (!firstOnPage && heightPx + gap > remaining) closePage()
        val actualGap = if (firstOnPage) 0 else metrics.paragraphGapPx
        markStart()
        currentBlocks.add(EpubPageBlock(EpubBlockKind.IMAGE, image = bitmap, imageHeightPx = heightPx, gapBeforePx = actualGap, sourceBlock = blockIndex))
        remaining -= (actualGap + heightPx)
        firstOnPage = false
    }

    fun placeText(block: EpubBlock) {
        var text = block.text ?: return
        var isContinuation = false
        var consumed = 0 // characters of the block already placed on earlier pages
        var guard = 0
        while (text.text.isNotEmpty() && guard < 2000) {
            guard++
            val style = when {
                block.kind == EpubBlockKind.HEADING -> metrics.headingStyle(block.headingLevel)
                isContinuation -> metrics.continuationStyle
                else -> metrics.bodyStyle
            }
            val gap = if (isContinuation) 0 else if (block.kind == EpubBlockKind.HEADING) metrics.headingGapPx else metrics.paragraphGapPx
            val usedGap = if (firstOnPage) 0 else gap
            val available = remaining - usedGap
            if (available <= 0 && !firstOnPage) {
                closePage()
                continue
            }
            val layout = textMeasurer.measure(text, style, constraints = Constraints(maxWidth = metrics.contentWidthPx))
            if (layout.size.height <= available) {
                markStart()
                currentBlocks.add(EpubPageBlock(block.kind, text, block.headingLevel, gapBeforePx = usedGap, isContinuation = isContinuation, sourceBlock = blockIndex, sourceStart = consumed))
                remaining -= (usedGap + layout.size.height)
                firstOnPage = false
                text = AnnotatedString("")
            } else {
                var linesFit = 0
                for (i in 0 until layout.lineCount) {
                    if (layout.getLineBottom(i) <= available) linesFit = i + 1 else break
                }
                if (linesFit == 0) {
                    if (firstOnPage) {
                        // Pathological case: not even one line fits a fresh page. Place it whole
                        // rather than loop forever; it will simply overflow visually this once.
                        markStart()
                        currentBlocks.add(EpubPageBlock(block.kind, text, block.headingLevel, gapBeforePx = 0, isContinuation = isContinuation, sourceBlock = blockIndex, sourceStart = consumed))
                        remaining = 0
                        firstOnPage = false
                        text = AnnotatedString("")
                    } else {
                        closePage()
                    }
                } else {
                    val cut = layout.getLineEnd(linesFit - 1)
                    val cutHeight = layout.getLineBottom(linesFit - 1).toInt()
                    val head = text.subSequence(0, cut)
                    val tail = text.subSequence(cut, text.text.length)
                    markStart()
                    currentBlocks.add(EpubPageBlock(block.kind, head, block.headingLevel, gapBeforePx = usedGap, isContinuation = isContinuation, sourceBlock = blockIndex, sourceStart = consumed))
                    remaining -= (usedGap + cutHeight)
                    firstOnPage = false
                    consumed += cut
                    text = tail
                    isContinuation = true
                }
            }
        }
    }

    book.chapters.forEachIndexed { chapterIndex, chapter ->
        if (pages.isNotEmpty() || currentBlocks.isNotEmpty()) closePage()
        currentChapter = chapterIndex
        chapterStartPage.add(pages.size)
        starts = IntArray(chapter.blocks.size) { -1 }
        chapter.blocks.forEachIndexed { index, block ->
            blockIndex = index
            started = false
            if (block.kind == EpubBlockKind.IMAGE) block.image?.let(::placeImage) else placeText(block)
        }
        // A block that was never placed (an image that failed to decode) starts wherever the next
        // placed block starts.
        for (i in starts.indices.reversed()) {
            if (starts[i] < 0) starts[i] = if (i + 1 < starts.size) starts[i + 1] else pages.size
        }
        blockStartPages.add(starts)
    }
    closePage()
    return EpubPagination(pages, chapterStartPage, blockStartPages)
}
