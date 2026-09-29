package com.camus.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val CamusReaderLight = lightColorScheme(
    primary = Color(0xFF6B5846),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F3F8),
    onSurface = Color(0xFF242529),
    onSurfaceVariant = Color(0xFF5F636B),
    outlineVariant = Color(0xFFD9D4CD),
)

private val CamusReaderGray = darkColorScheme(
    primary = Color(0xFFDEC6AC),
    onPrimary = Color(0xFF32281E),
    background = Color(0xFF2A2B2F),
    surface = Color(0xFF34353A),
    surfaceVariant = Color(0xFF3D3E44),
    onSurface = Color(0xFFEDEAE4),
    onSurfaceVariant = Color(0xFFBDBAB4),
    outlineVariant = Color(0xFF4A4B52),
)

private val CamusReaderAmoled = darkColorScheme(
    primary = Color(0xFFDEC6AC),
    onPrimary = Color(0xFF32281E),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF0E0E10),
    onSurface = Color(0xFFF2EEE8),
    onSurfaceVariant = Color(0xFFB0ADA8),
    outlineVariant = Color(0xFF26262A),
)

/** Font for the app's display titles: Camus Reader's serif with the System font, sans-serif with Sans. */
val LocalTitleFont = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }

private fun appTypography(font: AppFont): Typography {
    if (font == AppFont.SYSTEM) return Typography()
    val base = Typography()
    fun TextStyle.sans() = copy(fontFamily = FontFamily.SansSerif)
    return base.copy(
        displayLarge = base.displayLarge.sans(), displayMedium = base.displayMedium.sans(), displaySmall = base.displaySmall.sans(),
        headlineLarge = base.headlineLarge.sans(), headlineMedium = base.headlineMedium.sans(), headlineSmall = base.headlineSmall.sans(),
        titleLarge = base.titleLarge.sans(), titleMedium = base.titleMedium.sans(), titleSmall = base.titleSmall.sans(),
        bodyLarge = base.bodyLarge.sans(), bodyMedium = base.bodyMedium.sans(), bodySmall = base.bodySmall.sans(),
        labelLarge = base.labelLarge.sans(), labelMedium = base.labelMedium.sans(), labelSmall = base.labelSmall.sans(),
    )
}

private val CamusReaderDark = darkColorScheme(
    primary = Color(0xFFDEC6AC),
    onPrimary = Color(0xFF32281E),
    background = Color(0xFF0D0D0F),
    surface = Color(0xFF161618),
    surfaceVariant = Color(0xFF1E1E21),
    onSurface = Color(0xFFF2EEE8),
    onSurfaceVariant = Color(0xFFB9B6B1),
    outlineVariant = Color(0xFF2E2E33),
)

@Composable
fun CamusReaderApp(
    selection: BookSelection?,
    onOpenBook: () -> Unit,
    libraryEntries: List<LibraryEntry> = emptyList(),
    onSelectBook: (BookSelection) -> Unit = {},
    onRemoveBook: (BookSelection) -> Unit = {},
    onCloseBook: () -> Unit = {},
    renderBookPage: (@Composable (ReaderController, ReaderLeaf.BookPage, Color, Color) -> Unit)? = null,
    readerController: ReaderController? = null,
    onRestoreBook: ((ReaderController, BookSelection) -> Unit)? = null,
    catalog: CatalogUiState = CatalogUiState(),
    onCatalogOpen: () -> Unit = {},
    onCatalogSearch: (CatalogQuery) -> Unit = {},
    onCatalogRefresh: () -> Unit = {},
    onDownloadCatalogBook: (Int) -> Unit = {},
    onCatalogAdd: (GutenbergBook) -> Unit = {},
    profileName: String = "Local profile",
    onLockProfile: () -> Unit = {},
    encryptionEnabled: Boolean = false,
    ptgEnabled: Boolean = false,
    /** Last chosen library filter / sort (enum names) and where changes are reported to be remembered. */
    libraryFilter: String = "ALL",
    libraryOrder: String = "RECENT",
    onLibraryViewChange: (filter: String, order: String) -> Unit = { _, _ -> },
    /** Loads a book's cover image for the library; null when it has none. */
    coverFor: suspend (BookSelection) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    /** Called with whether the current theme is dark, so the platform can match status/navigation bar icons. */
    onThemeChanged: (dark: Boolean) -> Unit = {},
    /** Platform hook that runs [onBack] on the system back gesture/button while [enabled]. */
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    /** Sends the app to the background without closing the open book. */
    onLeaveApp: () -> Unit = {},
    onImportFont: () -> Unit = {},
    onRemoveFont: (String) -> Unit = {},
    onPtgEnabledChange: (Boolean) -> Unit = {},
    ptgAutoAdd: Boolean = false,
    onPtgAutoAddChange: (Boolean) -> Unit = {},
    onRequestEnableEncryption: () -> Unit = {},
    onRequestDisableEncryption: () -> Unit = {},
) {
    val controller = readerController ?: remember { ReaderController() }
    var destination by remember { mutableStateOf(LibraryDestination.SHELF) }
    val scope = rememberCoroutineScope()
    val libraryGridState = rememberLazyGridState()
    // Reader: back leaves the app but keeps the book and position exactly as they are. Library
    // pages: back goes up one level (Storage/Typography -> Settings -> library).
    systemBack(selection != null) { onLeaveApp() }
    // Registered after the one above, so it wins while a highlight's note is open.
    systemBack(controller.openNote != null) { controller.closeNote() }
    systemBack(selection == null && destination != LibraryDestination.SHELF) {
        destination = when (destination) {
            LibraryDestination.STORAGE, LibraryDestination.TYPOGRAPHY -> LibraryDestination.SETTINGS
            else -> LibraryDestination.SHELF
        }
    }
    LaunchedEffect(ptgEnabled) { if (!ptgEnabled && destination == LibraryDestination.GUTENBERG) destination = LibraryDestination.SHELF }
    LaunchedEffect(selection) {
        if (selection == null) controller.closeBook()
        else selection.let {
            controller.openBook(it)
            onRestoreBook?.invoke(controller, it)
            controller.markRestored()
        }
    }

    val colors = when (controller.appTheme) {
        AppTheme.LIGHT -> CamusReaderLight
        AppTheme.GRAY -> CamusReaderGray
        AppTheme.BLACK -> CamusReaderDark
        AppTheme.AMOLED -> CamusReaderAmoled
    }
    LaunchedEffect(controller.appTheme) { onThemeChanged(controller.appTheme.dark) }
    MaterialTheme(colorScheme = colors, typography = remember(controller.appFont) { appTypography(controller.appFont) }) {
    CompositionLocalProvider(LocalCoverLoader provides coverFor, LocalTitleFont provides if (controller.appFont == AppFont.SANS) FontFamily.SansSerif else FontFamily.Serif) {
        val snackbar = remember { SnackbarHostState() }
        // Explore is the only door to Pull the Guten (PTG); with internet access off it stays shut.
        val openExplore: () -> Unit = {
            if (ptgEnabled) destination = LibraryDestination.GUTENBERG
            else scope.launch { snackbar.showSnackbar("Please turn on internet access to access PTG") }
        }
        // Painted behind the system bars and keyboard area too, so nothing there shows the window's white.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The keyboard normally shrinks the whole app. While a highlight's note is being written the
        // reader must not shrink (that would repaginate the book under the note), so the note drawer
        // lifts itself above the keyboard instead. The keyboard stays excluded until it is gone again.
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        var keyboardOverReader by remember { mutableStateOf(false) }
        val noteOpen = controller.openNote != null
        LaunchedEffect(noteOpen, imeUp) { keyboardOverReader = noteOpen || (keyboardOverReader && imeUp) }
        val appInsets = if (noteOpen || keyboardOverReader) WindowInsets.safeDrawing.exclude(WindowInsets.ime) else WindowInsets.safeDrawing
        Surface(Modifier.fillMaxSize().windowInsetsPadding(appInsets)) {
            // Screens cross-fade into each other (library <-> settings <-> reader) instead of cutting.
            val screen: Any = if (selection == null) destination else "reader"
            Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { target ->
            if (target is LibraryDestination) {
                when (target) {
                    LibraryDestination.SHELF -> LibraryScreen(
                        books = libraryEntries,
                        darkMode = controller.darkMode,
                        onToggleTheme = { controller.darkMode = !controller.darkMode },
                        onImport = onOpenBook,
                        onRead = { destination = LibraryDestination.SHELF; onSelectBook(it) },
                        onRemove = onRemoveBook,
                        onSettings = { destination = LibraryDestination.SETTINGS },
                        onExplore = openExplore,
                        gridState = libraryGridState,
                        initialFilter = libraryFilter,
                        initialOrder = libraryOrder,
                        onViewChanged = onLibraryViewChange,
                    )
                    LibraryDestination.SETTINGS -> SettingsScreen(
                        onOpenStorage = { destination = LibraryDestination.STORAGE },
                        onOpenTypography = { destination = LibraryDestination.TYPOGRAPHY },
                        darkMode = controller.darkMode,
                        profileName = profileName,
                        onToggleTheme = { controller.darkMode = !controller.darkMode },
                        onBack = { destination = LibraryDestination.SHELF },
                        onLockProfile = onLockProfile,
                        ptgEnabled = ptgEnabled,
                        onPtgEnabledChange = onPtgEnabledChange,
                        ptgAutoAdd = ptgAutoAdd,
                        onPtgAutoAddChange = onPtgAutoAddChange,
                        encryptionEnabled = encryptionEnabled,
                        onRequestEnableEncryption = onRequestEnableEncryption,
                        onRequestDisableEncryption = onRequestDisableEncryption,
                    )
                    LibraryDestination.STORAGE -> StorageScreen(
                        books = libraryEntries,
                        onRemoveBooks = { list -> list.forEach(onRemoveBook) },
                        onBack = { destination = LibraryDestination.SETTINGS },
                    )
                    LibraryDestination.TYPOGRAPHY -> TypographyScreen(
                        appTheme = controller.appTheme,
                        onAppTheme = { controller.appTheme = it },
                        appFont = controller.appFont,
                        onAppFont = { controller.appFont = it },
                        readingFonts = controller.readingFonts,
                        readingFontName = controller.readingFontName,
                        onReadingFont = { controller.readingFontName = it },
                        onImportFont = onImportFont,
                        onRemoveFont = onRemoveFont,
                        onBack = { destination = LibraryDestination.SETTINGS },
                    )
                    LibraryDestination.GUTENBERG -> GutenbergCatalogScreen(
                        state = catalog,
                        onBack = { destination = LibraryDestination.SHELF },
                        onOpen = onCatalogOpen,
                        onSearch = onCatalogSearch,
                        onRefresh = onCatalogRefresh,
                        onDownload = onDownloadCatalogBook,
                        autoAdd = ptgAutoAdd,
                        onAdd = onCatalogAdd,
                    )
                }
            } else if (selection != null && controller.book?.location == selection.location) {
                ReaderWorkspace(controller, onOpenBook, onCloseBook, renderBookPage)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }
    }
}

// ---------------------------------------------------------------------------
// Reader workspace — redesigned to match the website's dark minimal aesthetic
// ---------------------------------------------------------------------------

@Composable
private fun ReaderWorkspace(
    controller: ReaderController,
    onOpenBook: () -> Unit,
    onCloseBook: () -> Unit,
    renderBookPage: (@Composable (ReaderController, ReaderLeaf.BookPage, Color, Color) -> Unit)?,
) {
    var sidebarOpen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }

    val pageBackground = when (controller.pageColor) {
        ReaderPageColor.PAPER -> Color(0xFFFFFCF2)
        ReaderPageColor.SEPIA -> Color(0xFFF1E1BD)
        ReaderPageColor.WHITE -> Color.White
        ReaderPageColor.NIGHT -> Color(0xFF202224)
    }
    val pageInk = if (controller.pageColor == ReaderPageColor.NIGHT) Color(0xFFF2EEE5) else Color(0xFF2C2924)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                ReaderTopBar(
                    controller = controller,
                    onBack = onCloseBook,
                    onSidebar = { sidebarOpen = !sidebarOpen },
                    onOpenBook = onOpenBook,
                    onFullscreen = { fullscreen = true },
                )
            } else {
                // The exit button gets a strip of its own, the colour of the page, so it never sits on the text.
                Row(Modifier.fillMaxWidth().height(40.dp).background(pageBackground), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { fullscreen = false }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            ReaderIcons.FullscreenExit,
                            contentDescription = "Exit fullscreen",
                            tint = pageInk.copy(alpha = .55f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                ReaderPane(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                    pageBackground = pageBackground,
                    pageInk = pageInk,
                    renderBookPage = renderBookPage,
                    fullscreen = fullscreen,
                    shrunk = scrubbing && !fullscreen,
                )
                // While quick-scrolling, a tap anywhere on the page closes the slider.
                if (scrubbing && !fullscreen) {
                    Box(
                        Modifier.fillMaxSize()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { scrubbing = false },
                    )
                }
                // The sidebar floats over the page instead of sitting beside it. Beside it, opening
                // the sidebar narrowed the page, which repaginated the whole book (moving the reader
                // to a different page) and left only a sliver of text on a phone.
                if (sidebarOpen && !fullscreen) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = .32f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sidebarOpen = false },
                    )
                    Surface(
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(.86f).widthIn(max = 340.dp),
                        shadowElevation = 8.dp,
                    ) {
                        ReaderSidebar(controller, Modifier.fillMaxSize(), onNavigate = { sidebarOpen = false })
                    }
                }
            }

            if (!fullscreen) {
                ReaderBottomBar(controller, scrubbing) { scrubbing = !scrubbing }
            }
        }
        HighlightNoteDrawer(controller)
    }
}

// ---------------------------------------------------------------------------
// Top bars
// ---------------------------------------------------------------------------

@Composable
private fun ReaderTopBar(
    controller: ReaderController,
    onBack: () -> Unit,
    onSidebar: () -> Unit,
    onOpenBook: () -> Unit,
    onFullscreen: () -> Unit,
) {
    var displayMenuOpen by remember { mutableStateOf(false) }
    var bookmarkMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var paragraphDialogOpen by remember { mutableStateOf(false) }
    val highlightActive = controller.highlightMode
    val bookmarked = controller.currentKey in controller.bookmarks

    // No title here: the file name lives in the sidebar. The icons share the full width evenly, so
    // there is no leftover gap where the name used to be. Each dropdown is declared inside the
    // same Box as the icon that opens it, which is what anchors it under that icon (declared at
    // the screen root it had no anchor and was placed at the corner of the screen).
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarSlot { BarIcon(ReaderIcons.Back, "Back to library", onBack) }
        TopBarSlot { BarIcon(ReaderIcons.Menu, "Contents and bookmarks", onSidebar) }
        TopBarSlot {
            IconButton(onClick = { displayMenuOpen = true }, modifier = Modifier.size(40.dp)) {
                Text("Aa", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (displayMenuOpen) {
                DisplayMenu(
                    controller = controller,
                    onDismiss = { displayMenuOpen = false },
                    onParagraph = { paragraphDialogOpen = true },
                )
            }
            if (paragraphDialogOpen) ParagraphDialog(controller, onDismiss = { paragraphDialogOpen = false })
        }
        TopBarSlot { BarIcon(ReaderIcons.Add, "Insert loose sheet") { controller.insertSheet() } }
        TopBarSlot {
            BarIcon(
                if (bookmarked) ReaderIcons.Star else ReaderIcons.StarOutline,
                "Bookmarks and highlights",
            ) { bookmarkMenuOpen = true }
            if (bookmarkMenuOpen) {
                BookmarkMenu(
                    controller = controller,
                    onDismiss = { bookmarkMenuOpen = false },
                    onOpenBook = { onOpenBook(); bookmarkMenuOpen = false },
                )
            }
        }
        TopBarSlot {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (highlightActive) MaterialTheme.colorScheme.primary.copy(alpha = .25f) else Color.Transparent)
                    .clickable { controller.highlightMode = !controller.highlightMode }
                    .semantics { contentDescription = if (highlightActive) "Turn highlighter off" else "Turn highlighter on" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ReaderIcons.Highlighter,
                    contentDescription = null,
                    tint = if (highlightActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        TopBarSlot {
            BarIcon(ReaderIcons.MoreVertical, "More options") { moreMenuOpen = true }
            if (moreMenuOpen) {
                MoreMenu(
                    controller = controller,
                    onDismiss = { moreMenuOpen = false },
                    onFullscreen = { moreMenuOpen = false; onFullscreen() },
                )
            }
        }
    }
}

@Composable
private fun RowScope.TopBarSlot(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------------------
// Bottom bar
// ---------------------------------------------------------------------------

@Composable
private fun ReaderBottomBar(controller: ReaderController, scrubbing: Boolean, onToggleScrub: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp, top = 4.dp)) {
        if (scrubbing) {
            val total = controller.sequence.size
            if (total > 1) {
                Slider(
                    value = controller.displayPageNumber.toFloat(),
                    onValueChange = { v ->
                        controller.sequence.getOrNull(v.roundToInt().coerceIn(1, total) - 1)?.let { controller.jumpTo(it.key) }
                    },
                    valueRange = 1f..total.toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Quick scroll" },
                )
            }
        } else {
            // Tapping the bar opens quick scroll.
            Box(Modifier.fillMaxWidth().height(20.dp).clickable(onClick = onToggleScrub), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(
                    progress = { (controller.displayPageNumber.toFloat() / controller.sequence.size.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Page arrows do nothing while scrolling continuously, so they are hidden then.
            val paging = controller.flow != ReaderFlow.SCROLL
            if (paging) {
            TextButton(onClick = { controller.move(-1) }, enabled = controller.displayPageNumber > 1, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Icon(ReaderIcons.ChevronLeft, contentDescription = "Previous page", modifier = Modifier.size(26.dp))
            }
            } else Spacer(Modifier.height(48.dp))
            Spacer(Modifier.weight(1f))
            Text(
                modifier = Modifier.clickable(onClick = onToggleScrub).padding(8.dp),
                text = "Page ${controller.displayPageNumber} · ${(controller.displayPageNumber.toFloat() / controller.sequence.size.coerceAtLeast(1) * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (paging) {
            TextButton(onClick = { controller.move(1) }, enabled = controller.displayPageNumber < controller.sequence.size, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Icon(ReaderIcons.ChevronRight, contentDescription = "Next page", modifier = Modifier.size(26.dp))
            }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------

@Composable
private fun ReaderSidebar(controller: ReaderController, modifier: Modifier = Modifier, onNavigate: () -> Unit = {}) {
    val currentPage = when (val leaf = controller.currentLeaf) {
        is ReaderLeaf.BookPage -> leaf.number
        is ReaderLeaf.TearPage -> leaf.sheet.afterPage
    }
    val currentChapter = controller.chapters.indexOfLast { it.second <= currentPage }

    // verticalScroll comes before padding so the padding scrolls with the content and the last
    // entry is never clipped at the bottom edge.
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            controller.book?.displayName ?: "Reader",
            fontFamily = LocalTitleFont.current,
            fontSize = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (controller.book?.kind == BookKind.EPUB) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Chapters", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (controller.chapters.isEmpty()) {
                Text("Loading chapters…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Titles wrap instead of being cut to one line, so the whole chapter name is readable.
            controller.chapters.forEachIndexed { index, (title, page) ->
                val isCurrent = index == currentChapter
                Text(
                    title,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { controller.jumpTo("page-$page"); onNavigate() }
                        .padding(vertical = 6.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Bookmarks", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (controller.bookmarks.isEmpty()) Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        controller.bookmarks.forEach { key ->
            val page = controller.sequence.indexOfFirst { it.key == key } + 1
            if (page > 0) Text(
                "Page $page",
                modifier = Modifier.fillMaxWidth().clickable { controller.jumpTo(key); onNavigate() }.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Reader pane — the page card
// ---------------------------------------------------------------------------

@Composable
private fun ReaderPane(
    controller: ReaderController,
    modifier: Modifier = Modifier,
    pageBackground: Color,
    pageInk: Color,
    renderBookPage: (@Composable (ReaderController, ReaderLeaf.BookPage, Color, Color) -> Unit)?,
    fullscreen: Boolean,
    shrunk: Boolean = false,
) {
    // Scaling (rather than resizing) keeps the page layout, so quick-scrubbing never repaginates.
    val scale by animateFloatAsState(if (shrunk) .86f else 1f)
    Box(
        modifier.fillMaxSize()
            .then(
                if (fullscreen) Modifier.pointerInput(Unit) {
                    // Watches on the Initial pass without consuming, so the page underneath (which may
                    // scroll or pan itself) can't hide the swipe. A PDF zoomed past 1x pans instead.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dx = 0f
                        var dy = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - change.previousPosition
                            dx += delta.x
                            dy += delta.y
                        }
                        val zoomedPdf = controller.book?.kind == BookKind.PDF && controller.zoom > 1.01f
                        if (!zoomedPdf && !controller.highlightMode && kotlin.math.abs(dx) > 120f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2f) {
                            controller.move(if (dx < 0) 1 else -1)
                        }
                    }
                } else Modifier,
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(if (fullscreen) 0.dp else 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // The book page stays composed underneath a loose sheet. Dropping it (as before) threw away
        // the paginated book / open PDF every time a sheet was shown, so returning to the book
        // meant a full re-layout. In scroll mode the book renders its own sheets inline.
        val current = controller.currentLeaf
        val bookLeaf = when (current) {
            is ReaderLeaf.BookPage -> current
            is ReaderLeaf.TearPage -> ReaderLeaf.BookPage(current.sheet.afterPage.coerceAtLeast(1))
        }
        if (renderBookPage != null) {
            renderBookPage(controller, bookLeaf, pageBackground, pageInk)
        } else {
            BookPagePreview(controller, bookLeaf, pageBackground, pageInk)
        }
        if (current is ReaderLeaf.TearPage && controller.flow != ReaderFlow.SCROLL) {
            TearPagePreview(
                sheet = current.sheet,
                pageNumber = controller.displayPageNumber,
                back = controller.sheetFace,
                onFlip = controller::flipSheet,
                onRemove = { controller.removeSheet(current.sheet) },
                onChange = controller::updateSheet,
                background = pageBackground,
                ink = pageInk,
                // Swallows touches so they don't reach the book page underneath.
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
        if (fullscreen) {
            val none = remember { MutableInteractionSource() }
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight(.6f).width(48.dp)
                    .clickable(interactionSource = none, indication = null) { controller.move(-1) },
            )
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(.6f).width(48.dp)
                    .clickable(interactionSource = none, indication = null) { controller.move(1) },
            )
        }
    }
}

@Composable
private fun BookPagePreview(controller: ReaderController, leaf: ReaderLeaf.BookPage, background: Color, ink: Color) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(controller.book?.displayName ?: "Book", color = ink, fontFamily = FontFamily.Serif, fontSize = 24.sp)
        Spacer(Modifier.height(16.dp))
        Text("Page ${controller.displayPageNumber}", color = ink.copy(alpha = .6f))
        Spacer(Modifier.weight(1f))
        Text(
            "PDF/EPUB rendering is the next migration stage.",
            color = ink.copy(alpha = .6f),
            modifier = Modifier.widthIn(max = 480.dp),
            lineHeight = 22.sp,
        )
        Spacer(Modifier.weight(1f))
    }
}

/** A loose sheet: front and back, each with a title and free text. Used as a page and inline in scroll mode. */
@Composable
internal fun TearPagePreview(
    sheet: TearSheet,
    pageNumber: Int,
    back: Boolean,
    onFlip: () -> Unit,
    onRemove: () -> Unit,
    onChange: (TearSheet) -> Unit,
    background: Color,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(background).padding(28.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$pageNumber · ${if (back) "back" else "front"}", color = ink.copy(alpha = .55f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onFlip) { Text(if (back) "Front" else "Back", color = ink) }
                TextButton(onClick = onRemove) { Text("Remove", color = ink) }
            }
            BasicTextField(
                value = if (back) sheet.backTitle else sheet.title,
                onValueChange = { onChange(if (back) sheet.copy(backTitle = it) else sheet.copy(title = it)) },
                textStyle = LocalTextStyle.current.copy(color = ink, fontFamily = FontFamily.Serif, fontSize = 26.sp),
                cursorBrush = SolidColor(ink),
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
            HorizontalDivider(color = ink.copy(alpha = .14f))
            // The body scrolls when the text outgrows the sheet, and stays tappable when it is empty.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val minHeight = maxHeight
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = if (back) sheet.backBody else sheet.body,
                        onValueChange = { onChange(if (back) sheet.copy(backBody = it) else sheet.copy(body = it)) },
                        textStyle = LocalTextStyle.current.copy(color = ink, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 26.sp),
                        cursorBrush = SolidColor(ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(top = 14.dp),
                        decorationBox = { field ->
                            Box {
                                if ((if (back) sheet.backBody else sheet.body).isEmpty()) {
                                    Text("Write on this loose sheet…", color = ink.copy(alpha = .32f), fontFamily = FontFamily.Serif, fontSize = 17.sp)
                                }
                                field()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dropdown menus
// ---------------------------------------------------------------------------

@Composable
private fun DisplayMenu(controller: ReaderController, onDismiss: () -> Unit, onParagraph: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (controller.darkMode) "Use light interface" else "Use dark interface") },
            onClick = { controller.darkMode = !controller.darkMode; onDismiss() },
        )
        HorizontalDivider()
        ReaderPageColor.entries.forEach { color ->
            DropdownMenuItem(
                text = { Text("${if (controller.pageColor == color) "✓ " else ""}${color.label} page") },
                onClick = { controller.pageColor = color; onDismiss() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Zoom out") },
            onClick = { controller.changeZoom(-.25f); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Zoom in") },
            onClick = { controller.changeZoom(.25f); onDismiss() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Paragraph…") },
            onClick = { onParagraph(); onDismiss() },
        )
    }
}

/** Section label inside a dropdown: small caps-style heading with room above it. */
@Composable
private fun MenuSection(title: String) {
    Text(
        title.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

private val MenuItemPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

@Composable
private fun BookmarkMenu(controller: ReaderController, onDismiss: () -> Unit, onOpenBook: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val isPdf = controller.book?.kind == BookKind.PDF
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 280.dp)) {
        DropdownMenuItem(
            text = { Text(if (controller.currentKey in controller.bookmarks) "Remove bookmark" else "Bookmark this page", fontSize = 16.sp) },
            leadingIcon = { Icon(ReaderIcons.BookmarkCheck, contentDescription = null, tint = muted, modifier = Modifier.size(22.dp)) },
            contentPadding = MenuItemPadding,
            onClick = { controller.toggleBookmark(); onDismiss() },
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuSection("Highlights")
        DropdownMenuItem(
            text = { Text("Erase highlights", fontSize = 16.sp) },
            leadingIcon = { Icon(ReaderIcons.Eraser, contentDescription = null, tint = muted, modifier = Modifier.size(22.dp)) },
            trailingIcon = {
                if (controller.eraseMode) Icon(ReaderIcons.Check, contentDescription = "On", tint = primary, modifier = Modifier.size(22.dp))
            },
            contentPadding = MenuItemPadding,
            onClick = { controller.eraseMode = !controller.eraseMode; onDismiss() },
        )
        if (controller.hasHighlightsOnPage()) {
            DropdownMenuItem(
                text = { Text("Clear this page", fontSize = 16.sp) },
                leadingIcon = { Icon(ReaderIcons.Delete, contentDescription = null, tint = muted, modifier = Modifier.size(22.dp)) },
                contentPadding = MenuItemPadding,
                onClick = { controller.erasePageHighlights(); onDismiss() },
            )
        }
        if (controller.hasAnyHighlights()) {
            DropdownMenuItem(
                text = { Text("Clear all highlights", fontSize = 16.sp) },
                leadingIcon = { Icon(ReaderIcons.Delete, contentDescription = null, tint = muted, modifier = Modifier.size(22.dp)) },
                contentPadding = MenuItemPadding,
                onClick = { controller.clearAllHighlights(); onDismiss() },
            )
        }

        if (isPdf) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuSection("PDF highlighting")
            PdfHighlightStyle.entries.forEach { style ->
                val available = style != PdfHighlightStyle.TEXT || controller.pdfTextSelectionSupported
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (style == PdfHighlightStyle.DRAW) "Draw a box" else "Select text", fontSize = 16.sp)
                            Text(
                                when {
                                    !available -> "Needs Android 15 or newer"
                                    style == PdfHighlightStyle.DRAW -> "Works on any PDF, including scans"
                                    else -> "Snaps to words; text PDFs only"
                                },
                                fontSize = 14.sp, lineHeight = 19.sp, color = muted,
                            )
                        }
                    },
                    enabled = available,
                    trailingIcon = {
                        if (controller.pdfHighlightStyle == style) Icon(ReaderIcons.Check, contentDescription = "Selected", tint = primary, modifier = Modifier.size(22.dp))
                    },
                    contentPadding = MenuItemPadding,
                    onClick = { controller.pdfHighlightStyle = style; onDismiss() },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuSection("Highlight color")
        HighlightColor.entries.forEach { color ->
            DropdownMenuItem(
                text = { Text(color.label, fontSize = 16.sp) },
                leadingIcon = {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(when (color) {
                        HighlightColor.AMBER -> Color(0xFFF6C84F)
                        HighlightColor.ROSE -> Color(0xFFF27D98)
                        HighlightColor.BLUE -> Color(0xFF6FB7FF)
                    }))
                },
                trailingIcon = {
                    if (controller.highlightColor == color) Icon(ReaderIcons.Check, contentDescription = "Selected", tint = primary, modifier = Modifier.size(22.dp))
                },
                contentPadding = MenuItemPadding,
                onClick = { controller.highlightColor = color; onDismiss() },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text("Open another book", fontSize = 16.sp) },
            leadingIcon = { Icon(ReaderIcons.Upload, contentDescription = null, tint = muted, modifier = Modifier.size(22.dp)) },
            contentPadding = MenuItemPadding,
            onClick = onOpenBook,
        )
    }
}

@Composable
private fun MoreMenu(controller: ReaderController, onDismiss: () -> Unit, onFullscreen: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Fullscreen") },
            onClick = onFullscreen,
        )
        DropdownMenuItem(
            text = { Text(if (controller.flow == ReaderFlow.PAGE) "Scroll mode" else "Page mode") },
            onClick = { controller.flow = if (controller.flow == ReaderFlow.PAGE) ReaderFlow.SCROLL else ReaderFlow.PAGE; onDismiss() },
        )
    }
}

// ---------------------------------------------------------------------------
// Paragraph settings
// ---------------------------------------------------------------------------

@Composable
private fun ParagraphDialog(controller: ReaderController, onDismiss: () -> Unit) {
    val p = controller.paragraph
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paragraph") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Applies to EPUB text. PDF pages keep their own layout.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepperRow(
                    label = "Line spacing",
                    value = formatOneDecimal(p.lineSpacing),
                    canDecrease = p.lineSpacing > ParagraphSettings.MIN_LINE_SPACING + .01f,
                    canIncrease = p.lineSpacing < ParagraphSettings.MAX_LINE_SPACING - .01f,
                    onDecrease = { controller.updateParagraph(p.copy(lineSpacing = p.lineSpacing - .1f)) },
                    onIncrease = { controller.updateParagraph(p.copy(lineSpacing = p.lineSpacing + .1f)) },
                )
                StepperRow(
                    label = "Space between paragraphs",
                    value = "${p.paragraphSpacing.roundToInt()} dp",
                    canDecrease = p.paragraphSpacing > 0.01f,
                    canIncrease = p.paragraphSpacing < ParagraphSettings.MAX_PARAGRAPH_SPACING - .01f,
                    onDecrease = { controller.updateParagraph(p.copy(paragraphSpacing = p.paragraphSpacing - 2f)) },
                    onIncrease = { controller.updateParagraph(p.copy(paragraphSpacing = p.paragraphSpacing + 2f)) },
                )
                StepperRow(
                    label = "First-line indent",
                    value = if (p.firstLineIndent <= 0f) "Off" else "${formatOneDecimal(p.firstLineIndent)} em",
                    canDecrease = p.firstLineIndent > 0.01f,
                    canIncrease = p.firstLineIndent < ParagraphSettings.MAX_FIRST_LINE_INDENT - .01f,
                    onDecrease = { controller.updateParagraph(p.copy(firstLineIndent = p.firstLineIndent - .5f)) },
                    onIncrease = { controller.updateParagraph(p.copy(firstLineIndent = p.firstLineIndent + .5f)) },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Justify text", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = p.justify, onCheckedChange = { controller.updateParagraph(p.copy(justify = it)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { controller.updateParagraph(ParagraphSettings()) }) { Text("Reset") } },
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDecrease, enabled = canDecrease) { Text("\u2212", fontSize = 20.sp) }
        TextButton(onClick = onIncrease, enabled = canIncrease) { Text("+", fontSize = 20.sp) }
    }
}

private fun formatOneDecimal(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
