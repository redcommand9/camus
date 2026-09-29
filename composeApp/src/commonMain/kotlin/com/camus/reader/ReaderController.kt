package com.camus.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.round

enum class ReaderPageColor(val label: String) { PAPER("Paper"), SEPIA("Sepia"), WHITE("White"), NIGHT("Night") }
enum class ReaderFlow { PAGE, SCROLL }
enum class AppTheme(val label: String, val dark: Boolean) {
    LIGHT("Light", false), GRAY("Gray", true), BLACK("Black", true), AMOLED("AMOLED", true),
}

/** App (not book) font: the device's system font with Camus Reader's serif titles, or sans-serif throughout. */
enum class AppFont(val label: String) { SYSTEM("System"), SANS("Sans") }

enum class HighlightColor(val label: String) { AMBER("Amber"), ROSE("Rose"), BLUE("Blue") }

/** A rectangle on a PDF page, as fractions (0..1) of the page width and height, so it holds at any zoom. */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One painted rectangle of a PDF highlight. Rectangles sharing a [group] are one highlight (e.g. the lines of a selection). */
data class PdfHighlight(val rect: PdfRect, val color: HighlightColor, val group: Int, val note: String = "")

/** How PDF highlights are made: draw a free rectangle, or drag across text and snap to it. */
enum class PdfHighlightStyle(val label: String) { DRAW("Draw"), TEXT("Text") }

/** A highlighted character range [start, end) inside one text block of a page. */
data class TextHighlight(val block: Int, val start: Int, val end: Int, val color: HighlightColor, val note: String = "")

/** Names one highlight for the notes drawer. A text range has no id of its own, so its position is its id. */
sealed interface HighlightRef {
    val key: String
    data class Text(override val key: String, val block: Int, val start: Int, val end: Int) : HighlightRef
    data class Pdf(override val key: String, val group: Int) : HighlightRef
}

fun TextHighlight.ref(key: String): HighlightRef = HighlightRef.Text(key, block, start, end)

/** A stretch of source text on the page being read: characters [start, end) of [block] in the highlight group [key]. */
data class TextSpan(val key: String, val block: Int, val start: Int, val end: Int)

/** The highlight whose notes drawer is open. [quote] is the highlighted words, when the reader can supply them (EPUB). */
data class OpenNote(val ref: HighlightRef, val quote: String?)

/**
 * Maps between the pages of the current layout and positions in the book's text, for books whose
 * page breaks move (EPUB: zoom, font, spacing, fullscreen). A position is an opaque string made by
 * the renderer. Bookmarks, loose sheets and the reading position are kept as positions and turned
 * back into page numbers for each layout, so they stay on the same words however often it changes.
 */
interface PageAnchors {
    /** The text position at the top of [page] (1-based), or null if it has none. */
    fun anchorOf(page: Int): String?
    /** The page (1-based) on which [anchor] falls in this layout, or null if it can't be read. */
    fun pageOf(anchor: String): Int?
}

/**
 * Reader-wide paragraph typography for EPUB text. Applies to every book (it is a reading
 * preference, not book data) and is persisted by the platform shell.
 */
data class ParagraphSettings(
    /** Line height as a multiple of the font size. */
    val lineSpacing: Float = 1.5f,
    /** Space between paragraphs in dp at 100% zoom. */
    val paragraphSpacing: Float = 14f,
    /** First-line indent in em; 0 = none. */
    val firstLineIndent: Float = 0f,
    val justify: Boolean = false,
) {
    /** Clamps to the supported range and snaps to the step sizes the settings UI uses. */
    fun normalized(): ParagraphSettings = copy(
        lineSpacing = snap(lineSpacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING), .1f),
        paragraphSpacing = snap(paragraphSpacing.coerceIn(0f, MAX_PARAGRAPH_SPACING), 2f),
        firstLineIndent = snap(firstLineIndent.coerceIn(0f, MAX_FIRST_LINE_INDENT), .5f),
    )

    companion object {
        const val MIN_LINE_SPACING = 1.2f
        const val MAX_LINE_SPACING = 2.2f
        const val MAX_PARAGRAPH_SPACING = 40f
        const val MAX_FIRST_LINE_INDENT = 3f
        private fun snap(value: Float, step: Float): Float = round(value / step) * step
    }
}

class ReaderController {
    var book by mutableStateOf<BookSelection?>(null)
        private set
    var pageCount by mutableStateOf(1)
        private set
    var currentKey by mutableStateOf("page-1")
        private set
    var sheets by mutableStateOf(emptyList<TearSheet>())
        private set
    /**
     * Bookmarks as saved: a leaf key ("page-3", "tear-…"), or "at:" + a text position for a page of a
     * book laid out by [applyLayout]. See [bookmarks] for the keys they point at now.
     */
    var bookmarkMarks by mutableStateOf(emptyList<String>())
        private set
    /** Keys of the bookmarked leaves in the current layout. */
    val bookmarks: List<String>
        get() = bookmarkMarks.mapNotNull(::resolveMark).distinct()
    /** The current layout's page <-> text map; null for books whose pages never move (PDF). */
    private var anchors by mutableStateOf<PageAnchors?>(null)
    /** Text position of the reading place (EPUB). Moves when the reader moves, not when the layout does. Saved. */
    var cursorAnchor by mutableStateOf<String?>(null)
        private set
    /** EPUB table of contents as (title, 1-based page number). Empty for PDFs. */
    var chapters by mutableStateOf(emptyList<Pair<String, Int>>())
        private set
    var appTheme by mutableStateOf(AppTheme.LIGHT)
    var appFont by mutableStateOf(AppFont.SYSTEM)

    /** The quick light/dark toggles flip between Light and whichever dark theme was used last. */
    private var lastDarkTheme = AppTheme.BLACK
    var darkMode: Boolean
        get() = appTheme.dark
        set(value) {
            if (appTheme.dark) lastDarkTheme = appTheme
            appTheme = if (value) lastDarkTheme else AppTheme.LIGHT
        }

    /** Fonts the user imported for reading, by file name, and the one in use (null = the book's serif). */
    var readingFonts by mutableStateOf(emptyMap<String, FontFamily>())
    var readingFontName by mutableStateOf<String?>(null)
    val readingFont: FontFamily? get() = readingFontName?.let { readingFonts[it] }
    private var highlightOn by mutableStateOf(false)
    private var eraseOn by mutableStateOf(false)

    var highlightMode: Boolean
        get() = highlightOn
        set(value) {
            highlightOn = value
            if (value) eraseOn = false
        }

    /** While on, tapping a highlight removes it; the highlighter itself is off. */
    var eraseMode: Boolean
        get() = eraseOn
        set(value) {
            eraseOn = value
            if (value) highlightOn = false
        }
    var highlightColor by mutableStateOf(HighlightColor.AMBER)
    var pdfHighlightStyle by mutableStateOf(PdfHighlightStyle.DRAW)
    /** Set by the platform: whether it can read text out of a PDF page (needed for [PdfHighlightStyle.TEXT]). */
    var pdfTextSelectionSupported by mutableStateOf(false)
    /** PDF highlights: leaf key -> painted rectangles. Saved with the book. */
    var pdfHighlights by mutableStateOf(emptyMap<String, List<PdfHighlight>>())
    private var nextPdfGroup = 1
    /** Map of leaf key → set of highlighted block indices on that page. */
    var highlights by mutableStateOf(emptyMap<String, Map<Int, HighlightColor>>())
        private set
    /**
     * Exact-range highlights (EPUB): group key (a chapter) -> highlighted ranges. Blocks and offsets
     * point into the book's own text, not into a page, so they hold when the book is laid out again
     * (zoom, fullscreen, font) and can be saved.
     */
    var textHighlights by mutableStateOf(emptyMap<String, List<TextHighlight>>())
        private set
    /** Set by the EPUB renderer: which stretches of source text make up the page on screen. */
    var pageTextSpans by mutableStateOf(emptyList<TextSpan>())
    /** The highlight whose notes drawer is showing, if any. */
    var openNote by mutableStateOf<OpenNote?>(null)
        private set
    var paragraph by mutableStateOf(ParagraphSettings())
        private set
    var pageColor by mutableStateOf(ReaderPageColor.PAPER)
    var flow by mutableStateOf(ReaderFlow.PAGE)
    var zoom by mutableStateOf(1f)
        private set
    var sheetFace by mutableStateOf(false)
        private set
    var readyForPersistence by mutableStateOf(false)
    /** False until the renderer has reported how many pages the book has; before that position/total are placeholders. */
    var pageCountKnown by mutableStateOf(false)
        private set

    private var tearNumber = 0

    val sequence: List<ReaderLeaf>
        get() = buildReaderSequence(pageCount, sheets)

    val currentLeaf: ReaderLeaf
        get() = sequence.firstOrNull { it.key == currentKey } ?: sequence.first()

    val positionLabel: String
        get() = "${sequence.indexOfFirst { it.key == currentKey }.coerceAtLeast(0) + 1} / ${sequence.size}"

    val displayPageNumber: Int
        get() = sequence.indexOfFirst { it.key == currentKey }.coerceAtLeast(0) + 1

    fun openBook(selection: BookSelection) {
        if (book?.location == selection.location) return
        book = selection
        pageCount = 1
        currentKey = "page-1"
        sheets = emptyList()
        bookmarkMarks = emptyList()
        anchors = null
        cursorAnchor = null
        chapters = emptyList()
        highlights = emptyMap()
        textHighlights = emptyMap()
        pdfHighlights = emptyMap()
        pageTextSpans = emptyList()
        openNote = null
        tearNumber = 0
        sheetFace = false
        zoom = 1f
        readyForPersistence = false
        pageCountKnown = false
    }

    fun closeBook() {
        book = null
        pageCount = 1
        currentKey = "page-1"
        sheets = emptyList()
        bookmarkMarks = emptyList()
        anchors = null
        cursorAnchor = null
        chapters = emptyList()
        highlights = emptyMap()
        textHighlights = emptyMap()
        pdfHighlights = emptyMap()
        pageTextSpans = emptyList()
        openNote = null
        sheetFace = false
        readyForPersistence = false
        pageCountKnown = false
    }

    /** Called by a native EPUB renderer once it has paginated the book for the current viewport. */
    fun updateChapters(entries: List<Pair<String, Int>>) {
        chapters = entries
    }

    fun restore(
        sheets: List<TearSheet>, cursor: String, color: ReaderPageColor, dark: Boolean, savedZoom: Float,
        bookmarks: List<String> = emptyList(), cursorAnchor: String? = null,
    ) {
        this.sheets = sheets
        this.bookmarkMarks = bookmarks
        this.cursorAnchor = cursorAnchor
        currentKey = cursor
        pageColor = color
        // The theme is app-wide (persisted separately), not per book, so `dark` is ignored here.
        zoom = savedZoom.coerceIn(.5f, 3f)
        tearNumber = sheets.mapNotNull { it.id.substringAfterLast('_').toIntOrNull() }.maxOrNull() ?: sheets.size
    }

    fun markRestored() { readyForPersistence = true }

    /** For books with fixed pages (PDF). A book whose pages move uses [applyLayout] instead. */
    fun updatePageCount(count: Int) {
        pageCount = count.coerceAtLeast(1)
        pageCountKnown = true
        if (currentKey !in sequence.map { it.key }) currentKey = "page-1"
    }

    /**
     * Called by a renderer whose page breaks move (EPUB) each time it lays the book out. Page numbers
     * of the reading place, loose sheets and bookmarks are worked out afresh from their text
     * positions - never from the previous page numbers, so going back and forth between two layouts
     * always lands on the same pages.
     */
    fun applyLayout(count: Int, layout: PageAnchors) {
        anchors = layout
        pageCount = count.coerceAtLeast(1)
        pageCountKnown = true
        fun pageFor(anchor: String?, fallback: Int): Int =
            (anchor?.let(layout::pageOf) ?: fallback).coerceIn(1, pageCount)

        // Records saved before positions were kept as text (or made before any layout) carry page
        // numbers; they are pinned to the text those pages show now, once.
        sheets = sheets.map { sheet ->
            val anchor = sheet.anchor ?: layout.anchorOf(sheet.afterPage.coerceIn(1, pageCount))
            sheet.copy(anchor = anchor, afterPage = pageFor(anchor, sheet.afterPage))
        }
        bookmarkMarks = bookmarkMarks.map { mark ->
            val page = mark.removePrefix("page-").takeIf { mark.startsWith("page-") }?.toIntOrNull()
            page?.let { layout.anchorOf(it.coerceIn(1, pageCount)) }?.let { "at:$it" } ?: mark
        }.distinct()

        // A loose sheet is its own place; anywhere else the reader goes back to their words.
        val onSheet = currentKey.startsWith("tear-") && sequence.any { it.key == currentKey }
        if (!onSheet) {
            val saved = cursorAnchor
            val page = pageFor(saved, currentKey.removePrefix("page-").toIntOrNull() ?: 1)
            currentKey = "page-$page"
            if (saved == null) cursorAnchor = layout.anchorOf(page)
        }
        if (sequence.none { it.key == currentKey }) currentKey = "page-1"
    }

    /** Remembers where the reader now is, in text, after they moved (not after a re-layout moved them). */
    private fun noteCursor() {
        val page = (currentLeaf as? ReaderLeaf.BookPage)?.number ?: return
        anchors?.anchorOf(page)?.let { cursorAnchor = it }
    }

    private fun resolveMark(mark: String): String? =
        if (mark.startsWith("at:")) anchors?.pageOf(mark.removePrefix("at:"))?.let { "page-$it" } else mark

    /** How a bookmark on [key] is saved: by its text position when the book has moving pages. */
    private fun markFor(key: String): String {
        val page = (sequence.firstOrNull { it.key == key } as? ReaderLeaf.BookPage)?.number
        return page?.let { anchors?.anchorOf(it) }?.let { "at:$it" } ?: key
    }

    /** Loads saved settings at startup. */
    fun restoreParagraph(settings: ParagraphSettings) {
        paragraph = settings.normalized()
    }

    fun updateParagraph(next: ParagraphSettings) {
        paragraph = next.normalized()
    }

    fun changeZoom(delta: Float) {
        zoom = (zoom + delta).coerceIn(0.5f, 3f)
    }

    fun flipSheet() { sheetFace = !sheetFace }

    fun toggleBookmark() {
        val key = currentKey
        bookmarkMarks = if (key in bookmarks) bookmarkMarks.filterNot { resolveMark(it) == key } else bookmarkMarks + markFor(key)
    }

    fun toggleHighlightBlock(key: String = currentKey, blockIndex: Int) {
        val pageHighlights = highlights[key] ?: emptyMap()
        val newPage = if (blockIndex in pageHighlights) pageHighlights - blockIndex else pageHighlights + (blockIndex to highlightColor)
        highlights = if (newPage.isEmpty()) highlights - key else highlights + (key to newPage)
    }

    /** Removes the highlights on [key]'s page, and any text highlight that touches the page being read. */
    fun erasePageHighlights(key: String = currentKey) {
        highlights = highlights - key
        pdfHighlights = pdfHighlights - key
        val next = textHighlights.mapValues { (group, list) -> list.filterNot { touchesPage(group, it) } }.filterValues { it.isNotEmpty() }
        if (next != textHighlights) textHighlights = next
    }

    private fun touchesPage(group: String, h: TextHighlight): Boolean =
        pageTextSpans.any { it.key == group && it.block == h.block && h.start < it.end && h.end > it.start }

    fun clearAllHighlights() {
        highlights = emptyMap()
        textHighlights = emptyMap()
        pdfHighlights = emptyMap()
    }

    fun highlightsFor(key: String = currentKey): Map<Int, HighlightColor> = highlights[key] ?: emptyMap()

    // --- Text-range highlights (EPUB drag-to-highlight) ---

    fun textHighlightsFor(key: String = currentKey): List<TextHighlight> = textHighlights[key] ?: emptyList()

    /**
     * Adds a highlight without ever stacking: overlapping or touching ranges of the same color are
     * merged into one, and a different color replaces (trims) whatever it overlaps. Without this,
     * re-selecting the same words painted the translucent tint on top of itself and got darker.
     */
    fun addTextHighlight(key: String, block: Int, start: Int, end: Int) {
        if (end <= start) return
        val existing = textHighlights[key] ?: emptyList()
        var s = start
        var e = end
        var changed = true
        while (changed) {
            changed = false
            existing.forEach { h ->
                if (h.block == block && h.color == highlightColor && h.start <= e && h.end >= s && (h.start < s || h.end > e)) {
                    s = minOf(s, h.start)
                    e = maxOf(e, h.end)
                    changed = true
                }
            }
        }
        val next = ArrayList<TextHighlight>()
        val notes = ArrayList<String>()
        existing.forEach { h ->
            if (h.block != block || h.end <= s || h.start >= e) {
                next.add(h)
                return@forEach
            }
            if (h.color == highlightColor) { // swallowed by the merged range, which keeps its note
                if (h.note.isNotBlank()) notes.add(h.note)
                return@forEach
            }
            if (h.start < s) next.add(h.copy(end = s))
            // A highlight split in two keeps its note on the first half only.
            if (h.end > e) next.add(h.copy(start = e, note = if (h.start < s) "" else h.note))
        }
        next.add(TextHighlight(block, s, e, highlightColor, notes.distinct().joinToString("\n\n")))
        textHighlights = textHighlights + (key to next)
    }

    fun removeTextHighlight(key: String, target: TextHighlight) {
        val list = textHighlights[key] ?: return
        val next = list - target
        textHighlights = if (next.isEmpty()) textHighlights - key else textHighlights + (key to next)
    }

    // --- Notes on highlights ---

    fun showNote(ref: HighlightRef, quote: String? = null) { openNote = OpenNote(ref, quote) }

    fun closeNote() { openNote = null }

    private fun textHighlightFor(ref: HighlightRef.Text): TextHighlight? =
        textHighlightsFor(ref.key).firstOrNull { it.block == ref.block && it.start == ref.start && it.end == ref.end }

    /** The note on [ref] (empty when it has none), or null when that highlight no longer exists. */
    fun noteFor(ref: HighlightRef): String? = when (ref) {
        is HighlightRef.Text -> textHighlightFor(ref)?.note
        is HighlightRef.Pdf -> pdfHighlightsFor(ref.key).firstOrNull { it.group == ref.group }?.note
    }

    fun colorFor(ref: HighlightRef): HighlightColor? = when (ref) {
        is HighlightRef.Text -> textHighlightFor(ref)?.color
        is HighlightRef.Pdf -> pdfHighlightsFor(ref.key).firstOrNull { it.group == ref.group }?.color
    }

    fun setNote(ref: HighlightRef, note: String) {
        when (ref) {
            is HighlightRef.Text -> {
                val list = textHighlights[ref.key] ?: return
                textHighlights = textHighlights + (ref.key to list.map {
                    if (it.block == ref.block && it.start == ref.start && it.end == ref.end) it.copy(note = note) else it
                })
            }
            // A PDF highlight is one rectangle per line, so every rectangle of the group carries the note.
            is HighlightRef.Pdf -> {
                val list = pdfHighlights[ref.key] ?: return
                pdfHighlights = pdfHighlights + (ref.key to list.map { if (it.group == ref.group) it.copy(note = note) else it })
            }
        }
    }

    fun removeHighlight(ref: HighlightRef) {
        when (ref) {
            is HighlightRef.Text -> textHighlightFor(ref)?.let { removeTextHighlight(ref.key, it) }
            is HighlightRef.Pdf -> removePdfHighlight(ref.key, ref.group)
        }
    }

    fun hasHighlightsOnPage(key: String = currentKey): Boolean =
        (highlights[key]?.isNotEmpty() == true) || (pdfHighlights[key]?.isNotEmpty() == true) ||
            textHighlights.any { (group, list) -> list.any { touchesPage(group, it) } }

    /** Called when a book's saved text highlights are loaded. */
    fun restoreTextHighlights(saved: Map<String, List<TextHighlight>>) {
        textHighlights = saved
    }

    fun hasAnyHighlights(): Boolean = highlights.isNotEmpty() || textHighlights.isNotEmpty() || pdfHighlights.isNotEmpty()

    // --- PDF highlights ---

    fun pdfHighlightsFor(key: String): List<PdfHighlight> = pdfHighlights[key] ?: emptyList()

    /** Adds the rectangles as one highlight in the current colour. */
    fun addPdfHighlight(key: String, rects: List<PdfRect>) {
        if (rects.isEmpty()) return
        val group = nextPdfGroup++
        pdfHighlights = pdfHighlights + (key to (pdfHighlightsFor(key) + rects.map { PdfHighlight(it, highlightColor, group) }))
    }

    fun removePdfHighlight(key: String, group: Int) {
        val next = pdfHighlightsFor(key).filterNot { it.group == group }
        pdfHighlights = if (next.isEmpty()) pdfHighlights - key else pdfHighlights + (key to next)
    }

    /** Called when a book's saved highlights are loaded; keeps new group ids clear of the saved ones. */
    fun restorePdfHighlights(saved: Map<String, List<PdfHighlight>>) {
        pdfHighlights = saved
        nextPdfGroup = (saved.values.flatten().maxOfOrNull { it.group } ?: 0) + 1
    }

    fun jumpTo(key: String) {
        // Already there: nothing moves, and the saved place keeps its exact words.
        if (key == currentKey) return
        if (sequence.any { it.key == key }) {
            currentKey = key
            sheetFace = false
            noteCursor()
        }
    }

    fun move(delta: Int) {
        val next = moveCursor(sequence, currentKey, delta)
        sheetFace = false
        if (next == currentKey) return
        currentKey = next
        noteCursor()
    }

    fun insertSheet() {
        val leaf = currentLeaf
        val afterPage = when (leaf) {
            is ReaderLeaf.BookPage -> leaf.number
            is ReaderLeaf.TearPage -> leaf.sheet.afterPage
        }
        // A sheet added next to another shares its place, so the two always stay together.
        val textAnchor = when (leaf) {
            is ReaderLeaf.BookPage -> anchors?.anchorOf(afterPage)
            is ReaderLeaf.TearPage -> leaf.sheet.anchor
        }
        tearNumber += 1
        val sheet = TearSheet(id = "${afterPage}_$tearNumber", afterPage = afterPage, anchor = textAnchor)
        val currentSheetId = (currentLeaf as? ReaderLeaf.TearPage)?.sheet?.id
        val currentSheetIndex = sheets.indexOfFirst { it.id == currentSheetId }
        val firstAfterPageIndex = sheets.indexOfFirst { it.afterPage == afterPage }
        sheets = when {
            currentSheetIndex >= 0 -> sheets.toMutableList().apply { add(currentSheetIndex + 1, sheet) }
            firstAfterPageIndex >= 0 -> sheets.toMutableList().apply { add(firstAfterPageIndex, sheet) }
            else -> sheets + sheet
        }
        currentKey = ReaderLeaf.TearPage(sheet).key
        sheetFace = false
    }

    fun updateSheet(sheet: TearSheet) {
        sheets = sheets.map { if (it.id == sheet.id) sheet else it }
    }

    fun openSheet(sheet: TearSheet) {
        if (sheets.any { it.id == sheet.id }) {
            currentKey = ReaderLeaf.TearPage(sheet).key
            sheetFace = false
        }
    }

    fun removeSheet(sheet: TearSheet) {
        sheets = sheets.filterNot { it.id == sheet.id }
        bookmarkMarks = bookmarkMarks - ReaderLeaf.TearPage(sheet).key
        currentKey = "page-${sheet.afterPage}"
        sheetFace = false
        noteCursor()
    }
}
