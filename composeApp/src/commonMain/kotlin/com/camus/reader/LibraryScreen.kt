package com.camus.reader

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Loads a book's real cover image, if it has one. Provided by the platform; null means use the generated tile. */
val LocalCoverLoader = staticCompositionLocalOf<suspend (BookSelection) -> ImageBitmap?> { { null } }

private val Umber = Color(0xFF6B5846)
private val CoverPalettes = listOf(
    listOf(Color(0xFFDBD0BA), Color(0xFFF4ECDE)),
    listOf(Color(0xFFADA99D), Color(0xFFE4E0D3)),
    listOf(Color(0xFFB4C0B2), Color(0xFFE9EDDE)),
    listOf(Color(0xFFCAB7A7), Color(0xFFF3E7D8)),
    listOf(Color(0xFF9DAAAD), Color(0xFFDEE5E3)),
)

private enum class LibraryFilter(val title: String) { ALL("All"), PDF("PDF"), EPUB("EPUB") }
private enum class LibraryOrder(val title: String) { RECENT("Recent"), TITLE("Title"), ADDED("Date added") }

@Composable
fun LibraryScreen(
    books: List<LibraryEntry>,
    darkMode: Boolean,
    onToggleTheme: () -> Unit,
    onImport: () -> Unit,
    onRead: (BookSelection) -> Unit,
    onRemove: (BookSelection) -> Unit,
    onSettings: () -> Unit,
    onExplore: () -> Unit,
    /** Hoisted so the scroll position survives opening a book and coming back. */
    gridState: LazyGridState = rememberLazyGridState(),
    /** Last used filter and sort (enum names), and where to report changes so they can be remembered. */
    initialFilter: String = "ALL",
    initialOrder: String = "RECENT",
    onViewChanged: (filter: String, order: String) -> Unit = { _, _ -> },
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LibraryFilter.entries.firstOrNull { it.name == initialFilter } ?: LibraryFilter.ALL) }
    var order by remember { mutableStateOf(LibraryOrder.entries.firstOrNull { it.name == initialOrder } ?: LibraryOrder.RECENT) }
    LaunchedEffect(filter, order) { onViewChanged(filter.name, order.name) }
    var removalCandidate by remember { mutableStateOf<BookSelection?>(null) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<LibraryEntry?>(null) }
    // The format filter only earns its space once the library is big enough to need it.
    val filtersShown = books.size > 6
    val activeFilter = if (filtersShown) filter else LibraryFilter.ALL
    val visible = remember(books, query, activeFilter, order) {
        val matching = books.filter { entry ->
            entry.title.contains(query.trim(), ignoreCase = true) &&
                (activeFilter == LibraryFilter.ALL || entry.book.kind.name == activeFilter.name)
        }
        when (order) {
            LibraryOrder.RECENT -> matching.sortedWith(compareByDescending<LibraryEntry> { it.lastOpenedAt }.thenByDescending { it.addedAt })
            LibraryOrder.TITLE -> matching.sortedBy { it.title.lowercase() }
            LibraryOrder.ADDED -> matching.sortedByDescending { it.addedAt }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 900.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) LibraryRail(books.size, onSettings, onExplore)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LibraryHeader(
                    wide = wide, darkMode = darkMode, onToggleTheme = onToggleTheme, onSettings = onSettings, onExplore = onExplore,
                    count = books.size, searching = searching, query = query, onQuery = { query = it },
                    onToggleSearch = { searching = !searching; if (!searching) query = "" },
                )
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = when {
                        maxWidth < 350.dp -> 1
                        maxWidth < 650.dp -> 2
                        maxWidth < 1030.dp -> 3
                        else -> 4
                    }
                    val inset = if (maxWidth < 600.dp) 18.dp else 32.dp
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = inset, end = inset, top = 20.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp),
                    ) {
                        // Reading comes first: the book you were in is the first thing on the page.
                        if (query.isBlank() && activeFilter == LibraryFilter.ALL) {
                            val recent = books.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt }
                            if (recent != null) {
                                item(span = { GridItemSpan(maxLineSpan) }) { ContinueHero(recent, onRead) }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Baseline-aligned so the count sits on the heading's line, not centred beside it.
                                SectionHeading(if (query.isBlank()) "Your library" else "Search results", Modifier.alignByBaseline())
                                Spacer(Modifier.width(11.dp))
                                Text("${visible.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.alignByBaseline())
                                Spacer(Modifier.weight(1f))
                                if (books.size > 1) {
                                    IconButton(onClick = { sortSheetOpen = true }, modifier = Modifier.size(40.dp)) {
                                        Icon(ReaderIcons.Sort, contentDescription = "Sort: ${order.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                        if (filtersShown) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LibraryFilters(filter, onFilter = { filter = it }, books.size)
                            }
                        }
                        if (visible.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LibraryEmpty(books.isEmpty())
                            }
                        } else {
                            items(visible, key = { it.book.location }) { entry ->
                                LibraryBookCard(entry, onRead, onActions = { actionsFor = it }, modifier = Modifier.animateItem())
                            }
                        }
                    }
                    AddBookButton(gridState, onImport, Modifier.align(Alignment.BottomEnd).padding(20.dp))
                }
            }
        }
    }

    actionsFor?.let { entry ->
        QuickActionsSheet(
            entry = entry,
            onOpen = { actionsFor = null; onRead(entry.book) },
            onRemove = { actionsFor = null; removalCandidate = entry.book },
            onDismiss = { actionsFor = null },
        )
    }

    if (sortSheetOpen) {
        SortSheet(order, onPick = { order = it; sortSheetOpen = false }, onDismiss = { sortSheetOpen = false })
    }

    removalCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { removalCandidate = null },
            title = { Text("Remove from library?") },
            text = {
                val entry = books.firstOrNull { it.book.location == candidate.location }
                Text(
                    if (entry?.fromPtg == true) "${entry.title} will leave Camus Reader and its downloaded copy will be deleted. You can add it again from Pull the Guten."
                    else "${candidate.displayName} will leave Camus Reader. The original file stays on your device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onRemove(candidate); removalCandidate = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removalCandidate = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The round "+" grows into "+ Add book" when scrolling up or at the top, and shrinks when scrolling
 * down. It keeps its own state so that changing it recomposes only the button, not the whole shelf.
 */
@Composable
private fun AddBookButton(gridState: LazyGridState, onImport: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(gridState) {
        var prevIndex = 0
        var prevOffset = 0
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            val down = index > prevIndex || (index == prevIndex && offset > prevOffset + 6)
            val up = index < prevIndex || (index == prevIndex && offset < prevOffset - 6)
            when {
                index == 0 && offset < 8 -> expanded = true
                down -> expanded = false
                up -> expanded = true
            }
            prevIndex = index
            prevOffset = offset
        }
    }
    ExtendedFloatingActionButton(
        onClick = onImport,
        expanded = expanded,
        icon = { Icon(ReaderIcons.Add, contentDescription = "Add book") },
        text = { Text("Add book") },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    )
}

@Composable
private fun LibraryRail(count: Int, onSettings: () -> Unit, onExplore: () -> Unit) {
    Column(
        Modifier.width(224.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)).padding(20.dp),
    ) {
        Text("Camus Reader", fontFamily = LocalTitleFont.current, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
        Text("YOUR READING SPACE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(35.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Umber).padding(12.dp)) {
            Text("▣   Library", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        Spacer(Modifier.height(22.dp))
        Text("ON THIS DEVICE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        Text("$count ${if (count == 1) "book" else "books"}", fontFamily = LocalTitleFont.current, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onExplore, modifier = Modifier.fillMaxWidth()) { Text("Pull the Guten") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
    }
}

@Composable
private fun LibraryHeader(
    wide: Boolean,
    darkMode: Boolean,
    onToggleTheme: () -> Unit,
    onSettings: () -> Unit,
    onExplore: () -> Unit,
    count: Int,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)
            .padding(start = if (wide) 32.dp else 20.dp, end = if (wide) 24.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("Search your books") },
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Search library" },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            HeaderIcon(ReaderIcons.Close, "Close search", onToggleSearch)
        } else {
            // Baseline-aligned: the smaller count shares the title's baseline instead of floating above it.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text("Library", fontFamily = LocalTitleFont.current, fontSize = 21.sp, fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
                Text("  ·  $count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.alignByBaseline())
            }
            HeaderIcon(ReaderIcons.Search, "Search library", onToggleSearch)
            HeaderIcon(ReaderIcons.Gutenberg, "Pull the Guten", onExplore)
            HeaderIcon(ReaderIcons.Settings, "Settings", onSettings)
            HeaderIcon(if (darkMode) ReaderIcons.LightMode else ReaderIcons.DarkMode, if (darkMode) "Use light mode" else "Use dark mode", onToggleTheme)
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LibraryFilters(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit, count: Int) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        LibraryFilter.entries.forEach { choice ->
            val active = choice == filter
            Surface(
                onClick = { onFilter(choice) },
                shape = RoundedCornerShape(50),
                color = if (active) Umber else MaterialTheme.colorScheme.surface,
                contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(
                    if (choice == LibraryFilter.ALL) "All  $count" else choice.title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 13.sp,
                )
            }
        }
    }
}

/** Sort choices in a drawer that slides up from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(order: LibraryOrder, onPick: (LibraryOrder) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text("Sort by", fontFamily = LocalTitleFont.current, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LibraryOrder.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(choice) }.padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(choice.title, fontSize = 16.sp, modifier = Modifier.weight(1f), color = if (choice == order) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if (choice == order) Icon(ReaderIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier, fontFamily = LocalTitleFont.current, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium)
}

/** The book you were reading: big cover, where you are, one button to go back in. */
@Composable
private fun ContinueHero(entry: LibraryEntry, onRead: (BookSelection) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, label = "press")
    Surface(
        onClick = { onRead(entry.book) },
        interactionSource = press,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = "Continue ${entry.title}" },
        color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(entry, Modifier.width(118.dp).aspectRatio(.72f).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE READING", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(entry.title, fontFamily = LocalTitleFont.current, fontSize = 22.sp, lineHeight = 27.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                if (entry.total > 0) {
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Page ${entry.position} of ${entry.total}  ·  ${(entry.progress * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onRead(entry.book) }, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp)) {
                    Text("Resume", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Tap to open, press and hold for quick actions. Shrinks slightly under the finger. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryBookCard(
    entry: LibraryEntry,
    onRead: (BookSelection) -> Unit,
    onActions: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, label = "press")
    val haptics = LocalHapticFeedback.current
    // Tonal fill instead of an outline: the card is a step lighter than the page, with no border.
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClick = { onRead(entry.book) },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onActions(entry) },
                onLongClickLabel = "Quick actions",
            )
            .semantics { contentDescription = entry.title },
    ) {
        Column(Modifier.padding(10.dp)) {
            BookCover(entry, Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(10.dp))
            Text(entry.title, modifier = Modifier.fillMaxWidth(), fontFamily = LocalTitleFont.current, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.book.kind.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (entry.total > 0) Text("${(entry.progress * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.total > 0) {
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f),
                )
            }
        }
    }
}

/** Quick actions for a book, in a drawer from the bottom (opened by press and hold). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionsSheet(entry: LibraryEntry, onOpen: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(entry, Modifier.width(48.dp).aspectRatio(.72f).clip(RoundedCornerShape(6.dp)), compact = true)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(entry.title, fontFamily = LocalTitleFont.current, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.book.kind.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ReaderIcons.Gutenberg, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(18.dp))
                Text(if (entry.started) "Continue reading" else "Start reading", fontSize = 16.sp)
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ReaderIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(18.dp))
                Text("Remove from library", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Covers already loaded this session. A card that scrolls off and back on is composed afresh, and
 * asking the loader again is asynchronous - so without this it showed the placeholder tile for a
 * frame and then faded the cover in again, every time it came into view.
 */
private object CoverMemory {
    private const val LIMIT = 64
    private val covers = LinkedHashMap<String, ImageBitmap>()

    operator fun get(location: String): ImageBitmap? = covers[location]

    operator fun set(location: String, cover: ImageBitmap) {
        covers.remove(location)
        covers[location] = cover
        while (covers.size > LIMIT) covers.remove(covers.keys.first())
    }
}

/** The book's real cover when it has one (fading in the first time it loads), otherwise a generated tile. */
@Composable
private fun BookCover(entry: LibraryEntry, modifier: Modifier, compact: Boolean = false) {
    val loader = LocalCoverLoader.current
    val location = entry.book.location
    var cover by remember(location) { mutableStateOf(CoverMemory[location]) }
    LaunchedEffect(location) {
        if (cover == null) loader(entry.book)?.let { cover = it; CoverMemory[location] = it }
    }
    Crossfade(targetState = cover, modifier = modifier, label = "cover") { image ->
        if (image != null) {
            Image(
                bitmap = image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(entry, Modifier.fillMaxSize(), compact)
        }
    }
}

@Composable
private fun GeneratedCover(entry: LibraryEntry, modifier: Modifier, compact: Boolean = false) {
    val colors = CoverPalettes[(entry.book.location.hashCode() and Int.MAX_VALUE) % CoverPalettes.size]
    Box(
        modifier.background(Brush.verticalGradient(colors), RoundedCornerShape(5.dp))
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact) Text("F O L I O", fontSize = 8.sp, letterSpacing = 1.sp, color = Color(0xFF51493F))
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = Color(0xFF51493F).copy(alpha = .35f))
            Spacer(Modifier.height(if (compact) 5.dp else 10.dp))
            Text(entry.title, fontFamily = LocalTitleFont.current, fontWeight = FontWeight.Medium, color = Color(0xFF29251F), fontSize = if (compact) 11.sp else 15.sp, lineHeight = if (compact) 12.sp else 17.sp, maxLines = if (compact) 3 else 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(if (compact) 5.dp else 10.dp))
            HorizontalDivider(color = Color(0xFF51493F).copy(alpha = .35f))
            Spacer(Modifier.weight(1f))
            if (!compact) Text(entry.book.kind.name, fontSize = 9.sp, color = Color(0xFF51493F))
        }
    }
}

@Composable
private fun LibraryEmpty(noBooks: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 45.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("▤", fontSize = 27.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(17.dp))
        Text(if (noBooks) "A shelf, ready for its first book" else "No books found", fontFamily = LocalTitleFont.current, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(if (noBooks) "Add a PDF or EPUB to make this space your own." else "Try another title or format.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
