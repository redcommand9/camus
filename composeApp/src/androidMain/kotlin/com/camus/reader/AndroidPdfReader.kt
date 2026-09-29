package com.camus.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.graphics.pdf.models.selection.SelectionBoundary
import android.graphics.Point
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import android.util.LruCache
import java.io.Closeable

/**
 * Fully native PDF rendering via Android's own [PdfRenderer] - rasterizing each page to a bitmap
 * on a background thread. No WebView, no pdf.js. This class is unchanged from before; what's new
 * is that [AndroidPdfBookPage] below is actually wired into the app (see MainActivity.kt).
 *
 * A single renderer is shared across the book's pages; [PdfRenderer] permits only one open page
 * at a time, hence the `@Synchronized` render call.
 */
internal class AndroidPdfDocument(resolver: ContentResolver, uri: Uri) : Closeable {
    private val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
        ?: throw IllegalArgumentException("The selected PDF cannot be opened")
    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        throw error
    }

    init {
        if (renderer.pageCount == 0) {
            renderer.close()
            descriptor.close()
            throw IllegalArgumentException("This PDF has no pages")
        }
    }

    val pageCount: Int get() = renderer.pageCount

    private val aspects = HashMap<Int, Float>()

    /** Page height / width, so a page's slot can be sized before it is rendered. */
    @Synchronized
    fun aspect(pageNumber: Int): Float = aspects.getOrPut(pageNumber) {
        val page = renderer.openPage(pageNumber.coerceIn(1, pageCount) - 1)
        try { page.height.toFloat() / page.width.coerceAtLeast(1) } finally { page.close() }
    }

    /**
     * Rectangles (as page fractions) covering the text between two points on a page, one per line
     * of text. Empty when there is no text there, e.g. on a scanned page.
     */
    @Synchronized
    fun selectText(pageNumber: Int, from: Offset, to: Offset): List<PdfRect> {
        val page = renderer.openPage(pageNumber.coerceIn(1, pageCount) - 1)
        try {
            val w = page.width.toFloat()
            val h = page.height.toFloat()
            val selection = page.selectContent(
                SelectionBoundary(Point((from.x * w).toInt(), (from.y * h).toInt())),
                SelectionBoundary(Point((to.x * w).toInt(), (to.y * h).toInt())),
            ) ?: return emptyList()
            val raw = selection.selectedTextContents.flatMap { it.bounds }.map {
                PdfRect((it.left / w).coerceIn(0f, 1f), (it.top / h).coerceIn(0f, 1f), (it.right / w).coerceIn(0f, 1f), (it.bottom / h).coerceIn(0f, 1f))
            }
            return mergeIntoLines(raw)
        } finally {
            page.close()
        }
    }

    /**
     * Pages already rendered, by page and width, bounded by memory. Turning back a page, scrolling a
     * page back into view, or returning to a zoom level shows it at once instead of rendering it
     * again behind a spinner. Nothing is recycled by hand: a page on screen may still be drawing it.
     */
    private val rendered = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private fun cacheKey(pageNumber: Int, requestedWidth: Int): Long = (pageNumber.toLong() shl 32) or requestedWidth.toLong()

    /** The page as already rendered at this width, or null. Cheap: safe to call while composing. */
    fun cached(pageNumber: Int, requestedWidth: Int): Bitmap? = rendered.get(cacheKey(pageNumber, requestedWidth))

    fun render(pageNumber: Int, requestedWidth: Int): Bitmap {
        val key = cacheKey(pageNumber, requestedWidth)
        rendered.get(key)?.let { return it }
        return renderPage(pageNumber, requestedWidth).also { rendered.put(key, it) }
    }

    @Synchronized
    private fun renderPage(pageNumber: Int, requestedWidth: Int): Bitmap {
        val page = renderer.openPage(pageNumber.coerceIn(1, pageCount) - 1)
        try {
            val ratio = page.height.toFloat() / page.width
            val width = requestedWidth.coerceIn(320, 2048)
                .coerceAtMost((4096 / ratio).toInt().coerceAtLeast(1))
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(AndroidColor.WHITE)
            val scale = width.toFloat() / page.width
            val matrix = Matrix().apply { setScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

/** A rendered page, remembered with its page number so it is never shown in place of another page. */
private class RenderedPdfPage(val page: Int, val bitmap: Bitmap?, val failed: Boolean = false)

/**
 * Best-effort invert+darken filter approximating the website's
 * `.pdf-page.night canvas { filter: invert(.88) hue-rotate(180deg) brightness(.72) contrast(.94); }`.
 * A hue-rotate has no direct color-matrix equivalent, so this is a close visual approximation
 * (inverted, warm-dimmed) rather than a pixel-exact port - check it against a real night-mode PDF
 * page and adjust the coefficients below to taste.
 */
private val NightPdfColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.82f, 0f, 0f, 0f, 224f,
            0f, -0.82f, 0f, 0f, 224f,
            0f, 0f, -0.82f, 0f, 224f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Joins the per-word boxes of a selection into one box per line, so translucent tints don't overlap and darken. */
private fun mergeIntoLines(rects: List<PdfRect>): List<PdfRect> {
    val lines = ArrayList<PdfRect>()
    rects.sortedWith(compareBy({ it.top }, { it.left })).forEach { r ->
        val last = lines.lastOrNull()
        val sameLine = last != null &&
            minOf(last.bottom, r.bottom) - maxOf(last.top, r.top) > .5f * minOf(last.bottom - last.top, r.bottom - r.top)
        if (last != null && sameLine) {
            lines[lines.lastIndex] = PdfRect(minOf(last.left, r.left), minOf(last.top, r.top), maxOf(last.right, r.right), maxOf(last.bottom, r.bottom))
        } else {
            lines.add(r)
        }
    }
    return lines
}

private fun highlightTint(color: HighlightColor): Color = when (color) {
    HighlightColor.AMBER -> Color(0x66F6C84F)
    HighlightColor.ROSE -> Color(0x66F27D98)
    HighlightColor.BLUE -> Color(0x666FB7FF)
}

/**
 * Sits over one rendered PDF page: paints its saved highlights and, while the highlighter or
 * eraser is on, handles the touches.
 *  - Draw style: drag a box.
 *  - Text style: drag across text; the selection snaps to lines of words (Android's PdfRenderer text API).
 *  - Eraser: tap a highlight to remove it.
 */
@Composable
private fun PdfHighlightLayer(
    document: AndroidPdfDocument,
    controller: ReaderController,
    pageKey: String,
    pageNumber: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val saved = controller.pdfHighlightsFor(pageKey)
    var preview by remember(pageKey) { mutableStateOf<List<PdfRect>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val highlightMode = controller.highlightMode
    val eraseMode = controller.eraseMode
    val style = controller.pdfHighlightStyle
    val color = controller.highlightColor
    val latestSaved by rememberUpdatedState(saved)

    Box(
        modifier
            .onSizeChanged { size = it }
            .drawBehind {
                saved.forEach { h ->
                    drawRect(highlightTint(h.color), Offset(h.rect.left * this.size.width, h.rect.top * this.size.height),
                        Size((h.rect.right - h.rect.left) * this.size.width, (h.rect.bottom - h.rect.top) * this.size.height))
                    // A highlight that carries a note gets a line under it, so it can be told apart at a glance.
                    if (h.note.isNotBlank()) {
                        val y = h.rect.bottom * this.size.height
                        drawLine(noteTint(h.color), Offset(h.rect.left * this.size.width, y), Offset(h.rect.right * this.size.width, y), strokeWidth = 2.dp.toPx())
                    }
                }
                preview.forEach { r ->
                    drawRect(highlightTint(color), Offset(r.left * this.size.width, r.top * this.size.height),
                        Size((r.right - r.left) * this.size.width, (r.bottom - r.top) * this.size.height))
                }
            }
            .pointerInput(highlightMode, eraseMode, style, pageKey) {
                if (!highlightMode && !eraseMode) {
                    // Normal reading: a quick tap on a highlight opens its note. This only watches - it
                    // never consumes - so panning, zooming and turning pages are unaffected.
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
                        val at = Offset(down.position.x / size.width.coerceAtLeast(1), down.position.y / size.height.coerceAtLeast(1))
                        latestSaved.lastOrNull { at.x in it.rect.left..it.rect.right && at.y in it.rect.top..it.rect.bottom }
                            ?.let { controller.showNote(HighlightRef.Pdf(pageKey, it.group)) }
                    }
                    return@pointerInput
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    fun fraction(p: Offset) = Offset((p.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f), (p.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                    if (eraseMode) {
                        // Only a tap erases; a drag is left alone so the page can still be panned.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
                            if (!change.pressed) break
                        }
                        val at = fraction(down.position)
                        latestSaved.firstOrNull { at.x in it.rect.left..it.rect.right && at.y in it.rect.top..it.rect.bottom }
                            ?.let { controller.removePdfHighlight(pageKey, it.group) }
                        return@awaitEachGesture
                    }
                    down.consume()
                    val start = fraction(down.position)
                    var end = start
                    var job: Job? = null
                    fun update() {
                        if (style == PdfHighlightStyle.DRAW) {
                            preview = listOf(PdfRect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y)))
                        } else {
                            val a = start
                            val b = end
                            job?.cancel()
                            job = scope.launch {
                                preview = withContext(Dispatchers.IO) { runCatching { document.selectText(pageNumber, a, b) }.getOrDefault(emptyList()) }
                            }
                        }
                    }
                    update()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        end = fraction(change.position)
                        update()
                    }
                    job?.cancel()
                    val a = start
                    val b = end
                    scope.launch {
                        val rects = if (style == PdfHighlightStyle.DRAW) {
                            listOf(PdfRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)))
                                .filter { it.right - it.left > .01f && it.bottom - it.top > .005f }
                        } else {
                            withContext(Dispatchers.IO) { runCatching { document.selectText(pageNumber, a, b) }.getOrDefault(emptyList()) }
                        }
                        preview = emptyList()
                        controller.addPdfHighlight(pageKey, rects)
                    }
                }
            },
    )
}

/** Entry point used as CamusReaderApp's `renderBookPage` for [BookKind.PDF] (see AndroidBookPage.kt). */
@Composable
internal fun AndroidPdfBookPage(
    selection: BookSelection,
    controller: ReaderController,
    leaf: ReaderLeaf.BookPage,
    background: Color,
    ink: Color,
) {
    val context = LocalContext.current
    val documentResult by produceState<Result<AndroidPdfDocument>?>(null, selection.location) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidPdfDocument(context.contentResolver, Uri.parse(selection.location)) }
        }
    }
    // Read the state once: onDispose runs after `documentResult` has already moved on to the new
    // value, so reading the delegate inside it closed the freshly opened document.
    val result = documentResult
    DisposableEffect(result) {
        onDispose { result?.getOrNull()?.close() }
    }

    when {
        result == null -> Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        result.isFailure -> Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
            Text(result.exceptionOrNull()?.message ?: "This PDF could not be read.", color = ink)
        }
        else -> PdfPageContent(result.getOrThrow(), controller, leaf, background, ink)
    }
}

@Composable
private fun PdfPageContent(
    document: AndroidPdfDocument,
    controller: ReaderController,
    leaf: ReaderLeaf.BookPage,
    background: Color,
    ink: Color,
) {
    // The document can be closed (book left) before this runs; a closed one just means nothing to update.
    LaunchedEffect(document) { runCatching { document.pageCount }.getOrNull()?.let(controller::updatePageCount) }
    if (controller.flow == ReaderFlow.SCROLL) {
        PdfScrollPages(document, controller, background, ink)
        return
    }
    val pageColor = controller.pageColor

    // The letterbox around a page is the app background, not the page colour: a night-mode PDF is
    // near black, and the lighter page-colour card showed as grey space around it.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val containerMaxWidth = maxWidth
        val containerMaxHeight = maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { (containerMaxWidth * controller.zoom).roundToPx() }
        // The last page rendered here. While a new width renders (zoom, fullscreen) the same page
        // stays up, scaled; it is never shown in place of a different page.
        var lastRendered by remember(document) { mutableStateOf<RenderedPdfPage?>(null) }
        var slow by remember(leaf.number) { mutableStateOf(false) }
        LaunchedEffect(document, leaf.number, widthPx) {
            val cached = document.cached(leaf.number, widthPx)
            lastRendered = if (cached != null) RenderedPdfPage(leaf.number, cached) else {
                withContext(Dispatchers.IO) { runCatching { document.render(leaf.number, widthPx) } }
                    .fold({ RenderedPdfPage(leaf.number, it) }, { RenderedPdfPage(leaf.number, null, failed = true) })
            }
            // The pages either side are made ready while this one is read, so turning is instant.
            for (neighbour in listOf(leaf.number + 1, leaf.number - 1)) {
                if (neighbour in 1..document.pageCount && document.cached(neighbour, widthPx) == null) {
                    withContext(Dispatchers.IO) { runCatching { document.render(neighbour, widthPx) } }
                }
            }
        }
        // Only a render that is actually slow gets a spinner; a quick one would just flash it.
        LaunchedEffect(leaf.number) { delay(350); slow = true }
        val samePage = lastRendered?.takeIf { it.page == leaf.number }
        val shownBitmap = document.cached(leaf.number, widthPx) ?: samePage?.bitmap
        val horizontal = rememberScrollState()
        val vertical = rememberScrollState()
        Box(
            Modifier.fillMaxSize()
                .horizontalScroll(horizontal, enabled = !controller.highlightMode)
                .verticalScroll(vertical, enabled = !controller.highlightMode),
        ) {
          // Centered when smaller than the screen, scrollable when zoomed past it.
          Box(Modifier.widthIn(min = containerMaxWidth).heightIn(min = containerMaxHeight), contentAlignment = Alignment.Center) {
            val bitmap = shownBitmap
            when {
                bitmap == null && samePage?.failed == true -> Text("This PDF page could not be rendered.", color = ink)
                bitmap == null -> if (slow) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val tint = when (pageColor) {
                        ReaderPageColor.PAPER -> Color(0xFFFFFCF2).copy(alpha = .30f)
                        ReaderPageColor.SEPIA -> Color(0xFFF1E1BD).copy(alpha = .38f)
                        ReaderPageColor.WHITE, ReaderPageColor.NIGHT -> Color.Transparent
                    }
                    Box {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "PDF page ${leaf.number}",
                            // Whole page fits the screen at zoom 1 (no cut-off, no grey remainder).
                            modifier = Modifier.width(minOf(containerMaxWidth, containerMaxHeight * (bitmap.width.toFloat() / bitmap.height)) * controller.zoom),
                            contentScale = ContentScale.FillWidth,
                            colorFilter = if (pageColor == ReaderPageColor.NIGHT) NightPdfColorFilter else null,
                        )
                        if (tint != Color.Transparent) {
                            Box(Modifier.matchParentSize().background(tint))
                        }
                        PdfHighlightLayer(document, controller, leaf.key, leaf.number, Modifier.matchParentSize())
                    }
                }
            }
          }
        }
    }
}

/** Continuous scroll mode for PDFs: pages and loose sheets stacked vertically; pages render as they come into view. */
@Composable
private fun PdfScrollPages(document: AndroidPdfDocument, controller: ReaderController, background: Color, ink: Color) {
    val sequence = controller.sequence
    val latestSequence by rememberUpdatedState(sequence)
    val startIndex = remember { sequence.indexOfFirst { it.key == controller.currentKey }.coerceAtLeast(0) }
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
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val pageWidth = viewportWidth * controller.zoom
        val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
        // Zooming widens the pages; the list then pans sideways inside a horizontal scroller.
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = !controller.highlightMode), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.width(pageWidth).fillMaxHeight(), state = listState, userScrollEnabled = !controller.highlightMode) {
            itemsIndexed(sequence, key = { _, leaf -> leaf.key }) { index, leaf ->
                when (leaf) {
                    is ReaderLeaf.BookPage -> {
                        val aspect by produceState(1.414f, document, leaf.number) {
                            value = withContext(Dispatchers.IO) { runCatching { document.aspect(leaf.number) }.getOrDefault(1.414f) }
                        }
                        // Starts from the cache, so a page scrolled back into view is simply there. On a
                        // new zoom the old picture stays, stretched, until the sharper one is ready.
                        val bitmap by produceState(document.cached(leaf.number, widthPx), document, leaf.number, widthPx) {
                            val cached = document.cached(leaf.number, widthPx)
                            value = cached ?: withContext(Dispatchers.IO) { runCatching { document.render(leaf.number, widthPx) }.getOrNull() } ?: value
                        }
                        val shown = bitmap
                        // The slot has the page's real proportions from the start, so nothing jumps when it renders.
                        Box(Modifier.fillMaxWidth().aspectRatio(1f / aspect).padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                            if (shown == null) {
                                CircularProgressIndicator()
                            } else {
                                Image(
                                    bitmap = shown.asImageBitmap(),
                                    contentDescription = "PDF page ${leaf.number}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillWidth,
                                    colorFilter = if (controller.pageColor == ReaderPageColor.NIGHT) NightPdfColorFilter else null,
                                )
                            }
                            PdfHighlightLayer(document, controller, leaf.key, leaf.number, Modifier.fillMaxSize())
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
                        modifier = Modifier.width(viewportWidth).height(viewportHeight),
                    )
                }
            }
        }
        }
    }
}
