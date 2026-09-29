package com.camus.reader

import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** A parsed book plus the page layouts already computed for it. */
internal class LoadedEpub(val book: NativeEpubBook) {
    /** Most recently used last. Only touched from the main thread. */
    val layouts = LinkedHashMap<EpubMetrics, EpubPagination>(4, .75f, true)
}

/**
 * The last couple of books opened, kept in memory (softly, so Android can take the memory back when
 * it needs it). Reopening one skips parsing the EPUB, and if the screen is the same size as last
 * time it skips laying the book out too.
 */
private object LoadedEpubs {
    private val recent = LinkedHashMap<String, java.lang.ref.SoftReference<LoadedEpub>>()

    @Synchronized fun get(location: String): LoadedEpub? = recent[location]?.get()

    @Synchronized fun put(location: String, epub: LoadedEpub) {
        recent.remove(location)
        recent[location] = java.lang.ref.SoftReference(epub)
        while (recent.size > 2) recent.remove(recent.keys.first())
    }
}

/** A finished layout and the metrics it was made for. */
private class LaidOut(val metrics: EpubMetrics, val pagination: EpubPagination)

/** A position in the book's text, whatever the layout: chapter, block within it, offset within the block. */
private data class TextPosition(val chapter: Int, val block: Int, val offset: Int) : Comparable<TextPosition> {
    override fun compareTo(other: TextPosition): Int = compareValuesBy(this, other, { it.chapter }, { it.block }, { it.offset })
    fun encode(): String = "$chapter.$block.$offset"

    companion object {
        fun decode(text: String): TextPosition? {
            val parts = text.split('.').mapNotNull { it.toIntOrNull() }
            return if (parts.size == 3) TextPosition(parts[0], parts[1], parts[2]) else null
        }
    }
}

/** Page <-> text map of one layout (see [PageAnchors]): each page is known by the text position at its top. */
private class EpubPageAnchors(pagination: EpubPagination) : PageAnchors {
    private val starts: List<TextPosition> = run {
        var last = TextPosition(0, 0, 0)
        pagination.pages.map { page ->
            val first = page.blocks.firstOrNull { it.sourceBlock >= 0 }
            // A page with nothing traceable on it (never expected) sits where the one before it ends.
            (if (first != null) TextPosition(page.chapterIndex, first.sourceBlock, first.sourceStart) else last).also { last = it }
        }
    }

    override fun anchorOf(page: Int): String? = starts.getOrNull(page - 1)?.encode()

    /** The last page starting at or before [anchor], i.e. the page the text is on. */
    override fun pageOf(anchor: String): Int? {
        val target = TextPosition.decode(anchor) ?: return null
        var low = 0
        var high = starts.lastIndex
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= target) { found = mid; low = mid + 1 } else high = mid - 1
        }
        return found + 1
    }
}

/** A highlight as it falls on one page: [local] in page-block coordinates for drawing, [source] as stored. */
private class PageHighlight(val local: TextHighlight, val source: TextHighlight)

private fun highlightGroup(page: EpubPage) = "ch-${page.chapterIndex}"

private fun localHighlights(page: EpubPage?, stored: List<TextHighlight>): List<PageHighlight> {
    if (page == null || stored.isEmpty()) return emptyList()
    val out = ArrayList<PageHighlight>()
    page.blocks.forEachIndexed { index, block ->
        val length = block.text?.text?.length ?: return@forEachIndexed
        val from = block.sourceStart
        val to = from + length
        stored.forEach { h ->
            if (h.block == block.sourceBlock && h.end > from && h.start < to) {
                out.add(PageHighlight(TextHighlight(index, maxOf(h.start, from) - from, minOf(h.end, to) - from, h.color, h.note), h))
            }
        }
    }
    return out
}

private fun EpubPage.textSpans(): List<TextSpan> = blocks.mapNotNull { block ->
    val length = block.text?.text?.length ?: return@mapNotNull null
    TextSpan(highlightGroup(this), block.sourceBlock, block.sourceStart, block.sourceStart + length)
}

/** The words a highlight covers, for the note drawer. */
private fun NativeEpubBook.quoteOf(chapter: Int, h: TextHighlight): String? {
    val text = chapters.getOrNull(chapter)?.blocks?.getOrNull(h.block)?.text?.text ?: return null
    return text.substring(h.start.coerceIn(0, text.length), h.end.coerceIn(0, text.length))
}

/**
 * Fully native EPUB rendering. [NativeEpubBook] parses the book and [paginateEpub] lays it out
 * for the current viewport; this composable draws the current page with plain Compose Text and
 * Image. There is no WebView and no epub.js anywhere in this path.
 */
@Composable
internal fun AndroidEpubBookPage(
    selection: BookSelection,
    controller: ReaderController,
    leaf: ReaderLeaf.BookPage,
    background: Color,
    ink: Color,
) {
    val context = LocalContext.current
    val bookResult by produceState<Result<LoadedEpub>?>(null, selection.location) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                LoadedEpubs.get(selection.location)
                    ?: LoadedEpub(NativeEpubBook.load(context.contentResolver, Uri.parse(selection.location))).also { LoadedEpubs.put(selection.location, it) }
            }
        }
    }

    val result = bookResult
    when {
        result == null -> Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        result.isFailure -> Box(Modifier.fillMaxSize().background(background).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(result.exceptionOrNull()?.message ?: "This EPUB could not be read.", color = ink)
        }
        else -> EpubPageContent(result.getOrThrow(), controller, leaf, background, ink)
    }
}

@Composable
private fun EpubPageContent(
    loaded: LoadedEpub,
    controller: ReaderController,
    leaf: ReaderLeaf.BookPage,
    background: Color,
    ink: Color,
) {
    val book = loaded.book
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val horizontalPadding = 22.dp
    val verticalPadding = 20.dp
    val paragraph = controller.paragraph

    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val widthPx = with(density) { (maxWidth - horizontalPadding * 2).roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { (maxHeight - verticalPadding * 2).roundToPx() }.coerceAtLeast(1)
        val baseFontSizeSp = 17f * controller.zoom
        val paragraphGapPx = with(density) { (paragraph.paragraphSpacing * controller.zoom).dp.roundToPx() }
        val headingGapPx = maxOf(paragraphGapPx * 2, with(density) { (20f * controller.zoom).dp.roundToPx() })
        val fontFamily = controller.readingFont ?: FontFamily.Serif
        // The text colour does not change where lines break, so it is not part of the layout. A theme
        // or page-colour change recolours the pages that are already laid out instead of redoing them.
        val layoutMetrics = remember(widthPx, heightPx, baseFontSizeSp, paragraphGapPx, headingGapPx, paragraph, fontFamily) {
            EpubMetrics(widthPx, heightPx, Color.Black, baseFontSizeSp, paragraphGapPx, headingGapPx, paragraph, fontFamily)
        }

        // Laying out a long book takes a moment. The pages already on screen stay until the new
        // layout is ready (no blanking, no spinner), and the reader is moved to the same words on the
        // new pages. TextMeasurer is documented as safe off the main thread.
        var laidOut by remember(loaded) { mutableStateOf<LaidOut?>(null) }
        LaunchedEffect(loaded, layoutMetrics) {
            val previous = laidOut
            val cached = loaded.layouts[layoutMetrics]
            // A size that is only passing through (an animating keyboard) is not worth laying out for.
            if (cached == null && previous != null) delay(150)
            val next = cached ?: withContext(Dispatchers.Default) { paginateEpub(book, layoutMetrics, textMeasurer) }
            loaded.layouts[layoutMetrics] = next
            while (loaded.layouts.size > 4) loaded.layouts.remove(loaded.layouts.keys.first())
            // In the same frame as the new pages appear: the reader, sheets and bookmarks move to the
            // pages that now hold their words.
            controller.applyLayout(next.pages.size, EpubPageAnchors(next))
            laidOut = LaidOut(layoutMetrics, next)
        }

        val current = laidOut
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val pagination = current.pagination
            val metrics = remember(current.metrics, ink) { current.metrics.copy(ink = ink) }
            LaunchedEffect(current) {
                val lastPage = (pagination.pages.size - 1).coerceAtLeast(0)
                // The sidebar lists the book's own table of contents. Each entry can point into the
                // middle of a chapter file, so the page comes from where that block was placed.
                controller.updateChapters(
                    book.toc.map { entry ->
                        val page = pagination.blockStartPage.getOrNull(entry.chapterIndex)?.getOrNull(entry.blockIndex)
                            ?: pagination.chapterStartPage.getOrElse(entry.chapterIndex) { 0 }
                        entry.title to (page.coerceIn(0, lastPage) + 1)
                    },
                )
            }
            val pageIndex = (leaf.number - 1).coerceIn(0, (pagination.pages.size - 1).coerceAtLeast(0))
            val page = pagination.pages.getOrNull(pageIndex)
            LaunchedEffect(page) { controller.pageTextSpans = page?.textSpans() ?: emptyList() }

            if (controller.flow == ReaderFlow.SCROLL) {
                // A new layout gets a fresh list, opened on the (already moved) reading place. Carrying
                // the old list over kept the old item under the finger, which was a different page.
                key(pagination) {
                    ScrollPages(book, pagination, controller, metrics, horizontalPadding, verticalPadding, background, ink)
                }
            } else {
                val stored = page?.let { controller.textHighlightsFor(highlightGroup(it)) } ?: emptyList()
                val pageHighlights = remember(page, stored) { localHighlights(page, stored) }
                val latestHighlights by rememberUpdatedState(pageHighlights)
                val latestPage by rememberUpdatedState(page)
                val highlightMode = controller.highlightMode
                val eraseMode = controller.eraseMode
                val scrollState = rememberScrollState()
                LaunchedEffect(leaf.key) { scrollState.scrollTo(0) }

                // Where each text block of this page currently sits on screen. Highlighter mode uses it
                // to turn one continuous finger drag - even across several paragraphs - into a range.
                val registry = remember(leaf.key) { HashMap<Int, BlockGeometry>() }
                var preview by remember(leaf.key) { mutableStateOf<Map<Int, TextRange>>(emptyMap()) }
                var layerOrigin by remember { mutableStateOf(Offset.Zero) }

                val pageColumn: @Composable () -> Unit = {
                    Column(
                        Modifier.fillMaxSize()
                            .onGloballyPositioned { layerOrigin = it.positionInRoot() }
                            .highlightGesture(
                                enabled = highlightMode || eraseMode,
                                erase = eraseMode,
                                registry = registry,
                                layerOrigin = { layerOrigin },
                                highlights = { latestHighlights },
                                onPreview = { preview = it },
                                onCommit = { ranges ->
                                    val onPage = latestPage ?: return@highlightGesture
                                    ranges.forEach { (blockIndex, range) ->
                                        val block = onPage.blocks.getOrNull(blockIndex) ?: return@forEach
                                        controller.addTextHighlight(
                                            highlightGroup(onPage), block.sourceBlock,
                                            block.sourceStart + range.start, block.sourceStart + range.end,
                                        )
                                    }
                                },
                                onRemove = { hit -> latestPage?.let { controller.removeTextHighlight(highlightGroup(it), hit.source) } },
                            )
                            .noteTapGesture(
                                enabled = !highlightMode && !eraseMode,
                                registry = registry,
                                layerOrigin = { layerOrigin },
                                highlights = { latestHighlights },
                                onOpen = { hit ->
                                    latestPage?.let { controller.showNote(hit.source.ref(highlightGroup(it)), book.quoteOf(it.chapterIndex, hit.source)) }
                                },
                            )
                            .verticalScroll(scrollState, enabled = !highlightMode)
                            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    ) {
                        if (page == null) {
                            BasicText("This page is empty.", style = metrics.bodyStyle)
                        } else {
                            page.blocks.forEachIndexed { blockIndex, block ->
                                EpubBlockView(
                                    block = block,
                                    blockIndex = blockIndex,
                                    metrics = metrics,
                                    highlights = pageHighlights.filter { it.local.block == blockIndex }.map { it.local },
                                    preview = preview[blockIndex],
                                    registry = registry,
                                )
                            }
                        }
                    }
                }
                // Normal reading: long-press to select text, drag the handles, copy. Highlighter on:
                // press and drag across words - across paragraphs too - and they are highlighted when
                // you lift; long-press a word to highlight just that word; tap a highlight to remove it.
                // Outside both modes, tapping a highlight opens its note.
                // The two are separate modes because both want the same touch gestures.
                if (highlightMode) pageColumn() else SelectionContainer { pageColumn() }
            }
            // A thin bar while a new layout is being made; the page underneath stays readable.
            if (current.metrics != layoutMetrics) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
            }
        }
    }
}

/**
 * Continuous scroll mode: every page and every loose sheet laid end to end, so a sheet can be
 * scrolled past like any other page. The current page follows what is on screen.
 */
@Composable
private fun ScrollPages(
    book: NativeEpubBook,
    pagination: EpubPagination,
    controller: ReaderController,
    metrics: EpubMetrics,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
    background: Color,
    ink: Color,
) {
    val sequence = controller.sequence
    val latestSequence by rememberUpdatedState(sequence)
    val startIndex = remember { sequence.indexOfFirst { it.key == controller.currentKey }.coerceAtLeast(0) }
    // Start on the current item. Starting at the top and scrolling afterwards flashed page 1.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex.coerceIn(0, (sequence.size - 1).coerceAtLeast(0)))
    val flipped = remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(controller.currentKey) {
        val target = latestSequence.indexOfFirst { it.key == controller.currentKey }
        if (target >= 0 && target != listState.firstVisibleItemIndex) listState.scrollToItem(target)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val leaf = latestSequence.getOrNull(index) ?: return@collect
            if (leaf.key != controller.currentKey) controller.jumpTo(leaf.key)
        }
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(sequence, key = { _, leaf -> leaf.key }) { index, leaf ->
                when (leaf) {
                    is ReaderLeaf.BookPage -> {
                        val page = pagination.pages.getOrNull(leaf.number - 1)
                        val stored = page?.let { controller.textHighlightsFor(highlightGroup(it)) } ?: emptyList()
                        val pageHighlights = remember(page, stored) { localHighlights(page, stored) }
                        val latestHighlights by rememberUpdatedState(pageHighlights)
                        val latestPage by rememberUpdatedState(page)
                        // Every page lays its blocks out from index 0, so each needs its own registry.
                        val registry = remember(leaf.key) { HashMap<Int, BlockGeometry>() }
                        var layerOrigin by remember { mutableStateOf(Offset.Zero) }
                        Column(
                            Modifier.fillMaxWidth()
                                .onGloballyPositioned { layerOrigin = it.positionInRoot() }
                                .noteTapGesture(
                                    enabled = !controller.highlightMode && !controller.eraseMode,
                                    registry = registry,
                                    layerOrigin = { layerOrigin },
                                    highlights = { latestHighlights },
                                    onOpen = { hit ->
                                        latestPage?.let { controller.showNote(hit.source.ref(highlightGroup(it)), book.quoteOf(it.chapterIndex, hit.source)) }
                                    },
                                )
                                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                        ) {
                            page?.blocks?.forEachIndexed { blockIndex, block ->
                                EpubBlockView(
                                    block = block,
                                    blockIndex = blockIndex,
                                    metrics = metrics,
                                    highlights = pageHighlights.filter { it.local.block == blockIndex }.map { it.local },
                                    preview = null,
                                    registry = registry,
                                )
                            }
                        }
                    }
                    is ReaderLeaf.TearPage -> TearPagePreview(
                        sheet = leaf.sheet,
                        pageNumber = index + 1,
                        back = leaf.sheet.id in flipped.value,
                        onFlip = { flipped.value = if (leaf.sheet.id in flipped.value) flipped.value - leaf.sheet.id else flipped.value + leaf.sheet.id },
                        onRemove = { controller.removeSheet(leaf.sheet) },
                        onChange = controller::updateSheet,
                        background = background,
                        ink = ink,
                        modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Screen position and text layout of one text block on the current page (highlighter hit-testing). */
private class BlockGeometry {
    var origin: Offset = Offset.Zero
    var layout: TextLayoutResult? = null
}

private data class TextHit(val block: Int, val offset: Int)

private const val PHASE_CANCEL = 0
private const val PHASE_TAP = 1
private const val PHASE_DRAG = 2

/**
 * Highlighter-mode touch handling for the whole page (rather than per paragraph, which is why a
 * drag could never cross a paragraph boundary before):
 *  - press and move: selects word-snapped text from where the finger went down to where it is now,
 *    with a live preview, and commits the highlight on release;
 *  - long-press: selects the word under the finger (and keeps extending if the finger then moves);
 *  - tap on an existing highlight: removes it, but only in erase mode.
 * A gesture that starts on the page margin is left alone. Scrolling is disabled while this is
 * active (see the Column above) - pages are paginated to fit the screen, so nothing is lost.
 */
private fun Modifier.highlightGesture(
    enabled: Boolean,
    erase: Boolean,
    registry: Map<Int, BlockGeometry>,
    layerOrigin: () -> Offset,
    highlights: () -> List<PageHighlight>,
    onPreview: (Map<Int, TextRange>) -> Unit,
    onCommit: (Map<Int, TextRange>) -> Unit,
    onRemove: (PageHighlight) -> Unit,
): Modifier = pointerInput(enabled, erase, registry) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val startRoot = layerOrigin() + down.position

        // Phase 1: is this a tap, a drag, or a long press?
        val phase = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = PHASE_CANCEL
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed) {
                    result = PHASE_CANCEL
                    break
                }
                if (!change.pressed) {
                    result = PHASE_TAP
                    break
                }
                if ((change.position - down.position).getDistance() > slop) {
                    result = PHASE_DRAG
                    break
                }
            }
            result
        }

        if (phase == PHASE_CANCEL) return@awaitEachGesture
        if (erase && phase != PHASE_TAP) return@awaitEachGesture // erasing only reacts to taps; drags scroll/select
        if (phase == PHASE_TAP) {
            val hit = locate(registry, startRoot, clamp = false) ?: return@awaitEachGesture
            if (erase) {
                highlights().firstOrNull { it.local.block == hit.block && hit.offset >= it.local.start && hit.offset < it.local.end }?.let(onRemove)
            }
            return@awaitEachGesture
        }

        // Phase 2 (drag, or long press when phase == null): extend the selection until release.
        val anchor = locate(registry, startRoot, clamp = false) ?: return@awaitEachGesture
        var focus = anchor
        var shown = selectionRanges(registry, anchor, focus)
        onPreview(shown)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            locate(registry, layerOrigin() + change.position, clamp = true)?.let { focus = it }
            val next = selectionRanges(registry, anchor, focus)
            if (next != shown) {
                shown = next
                onPreview(next)
            }
        }
        onPreview(emptyMap())
        onCommit(shown)
    }
}

/**
 * Normal-reading tap handling: a quick tap on a highlight opens its note. It only watches - it never
 * consumes - so long-press selection, scrolling and the selection handles keep working; a press
 * that turns into a scroll or a long press is not a tap and is ignored.
 */
private fun Modifier.noteTapGesture(
    enabled: Boolean,
    registry: Map<Int, BlockGeometry>,
    layerOrigin: () -> Offset,
    highlights: () -> List<PageHighlight>,
    onOpen: (PageHighlight) -> Unit,
): Modifier = pointerInput(enabled, registry) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) break
                if (!change.pressed) {
                    result = true
                    break
                }
            }
            result
        } ?: false
        if (!tapped) return@awaitEachGesture
        val hit = locate(registry, layerOrigin() + down.position, clamp = false) ?: return@awaitEachGesture
        highlights().firstOrNull { it.local.block == hit.block && hit.offset >= it.local.start && hit.offset < it.local.end }?.let(onOpen)
    }
}

/**
 * Finds the text position under [root]. With [clamp] the nearest block is used even when the
 * finger is in a gap or margin (so a drag can pass over spacing); without it the point must be
 * inside a block's text area.
 */
private fun locate(registry: Map<Int, BlockGeometry>, root: Offset, clamp: Boolean): TextHit? {
    var best = -1
    var bestDistance = Float.MAX_VALUE
    for ((index, geometry) in registry) {
        val layout = geometry.layout ?: continue
        if (layout.layoutInput.text.length == 0) continue
        val top = geometry.origin.y
        val bottom = top + layout.size.height
        val distance = when {
            root.y < top -> top - root.y
            root.y > bottom -> root.y - bottom
            else -> 0f
        }
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    if (best < 0) return null
    val geometry = registry[best] ?: return null
    val layout = geometry.layout ?: return null
    val localX = root.x - geometry.origin.x
    if (!clamp && (bestDistance > 0f || localX < 0f || localX > layout.size.width)) return null
    val local = Offset(localX, (root.y - geometry.origin.y).coerceIn(0f, layout.size.height.toFloat()))
    val length = layout.layoutInput.text.length
    return TextHit(best, layout.getOffsetForPosition(local).coerceIn(0, length))
}

/** Word-snapped selection between two hits, split into one range per block it touches. */
private fun selectionRanges(registry: Map<Int, BlockGeometry>, anchor: TextHit, focus: TextHit): Map<Int, TextRange> {
    fun word(hit: TextHit): TextRange? {
        val layout = registry[hit.block]?.layout ?: return null
        val length = layout.layoutInput.text.length
        if (length == 0) return null
        return layout.getWordBoundary(hit.offset.coerceIn(0, length - 1))
    }
    val anchorWord = word(anchor) ?: return emptyMap()
    val focusWord = word(focus) ?: return emptyMap()
    val forward = focus.block > anchor.block || (focus.block == anchor.block && focus.offset >= anchor.offset)
    val startBlock = if (forward) anchor.block else focus.block
    val endBlock = if (forward) focus.block else anchor.block
    val startOffset = if (forward) anchorWord.start else focusWord.start
    val endOffset = if (forward) focusWord.end else anchorWord.end
    val result = LinkedHashMap<Int, TextRange>()
    for (block in startBlock..endBlock) {
        val layout = registry[block]?.layout ?: continue
        val length = layout.layoutInput.text.length
        val start = if (block == startBlock) startOffset else 0
        val end = if (block == endBlock) endOffset else length
        if (end > start) result[block] = TextRange(start, end)
    }
    return result
}

@Composable
private fun EpubBlockView(
    block: EpubPageBlock,
    blockIndex: Int,
    metrics: EpubMetrics,
    highlights: List<TextHighlight>,
    preview: TextRange?,
    registry: MutableMap<Int, BlockGeometry>,
) {
    // The paginator reserved this space when it packed the page; draw it, or paragraphs touch.
    if (block.gapBeforePx > 0) {
        Spacer(Modifier.height(with(LocalDensity.current) { block.gapBeforePx.toDp() }))
    }
    when (block.kind) {
        EpubBlockKind.IMAGE -> block.image?.let { bitmap ->
            val heightDp = with(LocalDensity.current) { block.imageHeightPx.toFloat().toDp() }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().height(heightDp),
            )
        }
        EpubBlockKind.HEADING -> block.text?.let {
            EpubTextBlock(it, metrics.headingStyle(block.headingLevel), metrics.ink, highlights, preview, blockIndex, registry)
        }
        EpubBlockKind.PARAGRAPH -> block.text?.let {
            val style = if (block.isContinuation) metrics.continuationStyle else metrics.bodyStyle
            EpubTextBlock(it, style, metrics.ink, highlights, preview, blockIndex, registry)
        }
    }
}

@Composable
private fun EpubTextBlock(
    text: AnnotatedString,
    style: TextStyle,
    ink: Color,
    highlights: List<TextHighlight>,
    preview: TextRange?,
    blockIndex: Int,
    registry: MutableMap<Int, BlockGeometry>,
) {
    val geometry = remember(registry, blockIndex) { BlockGeometry() }
    DisposableEffect(registry, blockIndex, geometry) {
        registry[blockIndex] = geometry
        onDispose { if (registry[blockIndex] === geometry) registry.remove(blockIndex) }
    }

    // Highlights are drawn as background spans on the text itself, so they wrap lines exactly
    // like the words do (a block-level background would paint the whole paragraph).
    val shown = remember(text, highlights, preview, ink) {
        buildAnnotatedString {
            append(text)
            val length = text.text.length
            highlights.forEach { h ->
                val start = h.start.coerceIn(0, length)
                val end = h.end.coerceIn(0, length)
                // A highlight that carries a note is underlined, so it can be told apart at a glance.
                if (end > start) addStyle(
                    SpanStyle(background = highlightTint(h.color), textDecoration = if (h.note.isNotBlank()) TextDecoration.Underline else null),
                    start, end,
                )
            }
            preview?.let { range ->
                val start = range.start.coerceIn(0, length)
                val end = range.end.coerceIn(0, length)
                if (end > start) addStyle(SpanStyle(background = ink.copy(alpha = .16f)), start, end)
            }
        }
    }

    BasicText(
        shown,
        style = style,
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { geometry.origin = it.positionInRoot() },
        onTextLayout = { geometry.layout = it },
    )
}

private fun highlightTint(color: HighlightColor): Color = when (color) {
    HighlightColor.AMBER -> Color(0x66F6C84F)
    HighlightColor.ROSE -> Color(0x66F27D98)
    HighlightColor.BLUE -> Color(0x666FB7FF)
}
