package com.camus.reader

import android.app.Activity
import androidx.core.view.WindowCompat
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * The Android app shell: the native [CamusReaderApp] UI (library, settings, catalog, and reader - the
 * same composables the desktop target uses) wired to on-device persistence via [ReadingStore],
 * a real system file picker, and Gutenberg search. This is what MainActivity now hosts instead
 * of a WebView pointed at the bundled website.
 */
@Composable
internal fun AndroidNativeApp(
    vault: LocalAccountVault,
    onLock: () -> Unit,
    onPickerActive: (Boolean) -> Unit,
    encryptionEnabled: Boolean = vault.encryptionEnabled(),
    onRequestEnableEncryption: () -> Unit = {},
    onRequestDisableEncryption: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(vault) { ReadingStore(vault) }
    val controller = remember {
        ReaderController().apply {
            appTheme = store.appTheme()
            pdfTextSelectionSupported = pdfTextSelectionAvailable()
            appFont = store.appFont()
            readingFonts = loadReadingFonts(context)
            readingFontName = store.readingFont()
            restoreParagraph(store.paragraphSettings())
        }
    }

    val covers = remember { CoverStore(context) }
    val libraryView = remember { store.libraryView() }
    var ptgEnabled by remember { mutableStateOf(store.ptgEnabled()) }
    var libraryEntries by remember { mutableStateOf(store.library()) }
    var selection by remember { mutableStateOf<BookSelection?>(null) }
    val gutenberg = remember { GutenbergIndex(context) }
    val ptgFiles = remember { PtgFiles(context) }
    var ptgAutoAdd by remember { mutableStateOf(store.ptgAutoAdd()) }
    var ptgDownloads by remember { mutableStateOf(emptyMap<Int, CatalogDownload>()) }
    var catalog by remember { mutableStateOf(CatalogUiState()) }
    val searchJob = remember { arrayOfNulls<Job>(1) }

    // Brings the on-device catalog up to date. Without a catalog yet this is a full first-time build
    // (shown as progress); with one, it runs quietly behind the searchable old copy.
    fun refreshCatalog(force: Boolean) {
        if (!ptgEnabled) return
        val current = catalog.index as? CatalogIndexState.Ready
        if (current?.updating == true || catalog.index is CatalogIndexState.Building) return
        catalog = catalog.copy(index = current?.copy(updating = true, updateError = null) ?: CatalogIndexState.Building("Connecting to Project Gutenberg…", null, true))
        scope.launch {
            val outcome = gutenberg.refresh(force) { building -> if (current == null) catalog = catalog.copy(index = building) }
            catalog = when (outcome) {
                is RefreshOutcome.Failed -> catalog.copy(index = current?.copy(updating = false, updateError = outcome.message) ?: CatalogIndexState.Failed(outcome.message))
                RefreshOutcome.Busy -> catalog
                else -> catalog.copy(index = gutenberg.ready() ?: CatalogIndexState.Failed("The catalog couldn't be opened. Try again."))
            }
        }
    }

    // The app-wide theme toggle is a single flag shared by the library/settings screens and the
    // reader (see CamusReaderApp's MaterialTheme), so it is persisted independently of any one book.
    LaunchedEffect(controller.appTheme) { store.setAppTheme(controller.appTheme) }
    LaunchedEffect(controller.appFont) { store.setAppFont(controller.appFont) }
    LaunchedEffect(controller.readingFontName) { store.setReadingFont(controller.readingFontName) }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPickerActive(false)
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) { importReadingFont(context, uri) }
            if (imported == null) {
                Toast.makeText(context, "That file isn't a font Camus Reader can use (.ttf or .otf).", Toast.LENGTH_LONG).show()
            } else {
                controller.readingFonts = loadReadingFonts(context)
                controller.readingFontName = imported
            }
        }
    }
    LaunchedEffect(controller.paragraph) { store.setParagraphSettings(controller.paragraph) }

    // Debounced by LocalAccountVault under the hood, so saving on every page turn is cheap.
    LaunchedEffect(
        controller.book?.location, controller.readyForPersistence, controller.currentKey,
        controller.sheets, controller.bookmarkMarks, controller.cursorAnchor, controller.pageColor, controller.darkMode,
        controller.zoom, controller.pageCount, controller.pdfHighlights, controller.textHighlights, controller.pageCountKnown,
    ) {
        val book = controller.book
        // Until the page count is known, position/total would be placeholders (page 1 of 1 = "100% read").
        if (book != null && controller.readyForPersistence && controller.pageCountKnown) {
            val saved = SavedNativeBook(
                selection = book,
                sheets = controller.sheets,
                bookmarks = controller.bookmarkMarks,
                cursor = controller.currentKey,
                color = controller.pageColor,
                darkMode = controller.darkMode,
                zoom = controller.zoom,
                position = controller.displayPageNumber,
                total = controller.sequence.size,
                pdfHighlights = controller.pdfHighlights,
                textHighlights = controller.textHighlights,
                cursorAnchor = controller.cursorAnchor,
            )
            store.save(saved)
            libraryEntries = libraryEntries.map {
                if (it.book.location == book.location) it.copy(position = saved.position, total = saved.total) else it
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPickerActive(false)
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Book"
        val kind = bookKindForName(name) ?: when (context.contentResolver.getType(uri)) {
            "application/pdf" -> BookKind.PDF
            "application/epub+zip" -> BookKind.EPUB
            else -> null
        }
        if (kind == null) return@rememberLauncherForActivityResult
        // Importing only adds the book to the library; the reader opens when it is tapped there.
        val book = BookSelection(name, uri.toString(), kind)
        val before = libraryEntries.size
        libraryEntries = store.importBook(book)
        Toast.makeText(
            context,
            if (libraryEntries.size > before) "Added ${book.displayName.substringBeforeLast('.')} to your library" else "Already in your library",
            Toast.LENGTH_SHORT,
        ).show()
    }

    CamusReaderApp(
        selection = selection,
        onOpenBook = {
            onPickerActive(true)
            picker.launch(arrayOf("application/pdf", "application/epub+zip"))
        },
        libraryEntries = libraryEntries,
        onSelectBook = { book ->
            selection = book
            libraryEntries = store.addOrOpenBook(book)
        },
        onRemoveBook = { book ->
            libraryEntries = store.removeBook(book)
            ptgFiles.delete(book.location)
            if (selection?.location == book.location) selection = null
        },
        onCloseBook = { selection = null },
        renderBookPage = { readerController, leaf, background, ink -> AndroidBookPage(readerController, leaf, background, ink) },
        readerController = controller,
        onRestoreBook = { readerController, book ->
            store.load(book)?.let { saved ->
                readerController.restore(saved.sheets, saved.cursor, saved.color, saved.darkMode, saved.zoom, saved.bookmarks, saved.cursorAnchor)
                readerController.restorePdfHighlights(saved.pdfHighlights)
                readerController.restoreTextHighlights(saved.textHighlights)
            }
        },
        catalog = catalog.copy(
            libraryIds = libraryEntries.mapNotNullTo(HashSet()) { ptgFiles.idOf(it.book.location) },
            downloads = ptgDownloads,
        ),
        onThemeChanged = { dark ->
            context.findActivity()?.window?.let { window ->
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        },
        libraryFilter = libraryView.first,
        libraryOrder = libraryView.second,
        onLibraryViewChange = { filter, order -> store.setLibraryView(filter, order) },
        coverFor = covers::cover,
        systemBack = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
        onLeaveApp = { context.findActivity()?.moveTaskToBack(true) },
        ptgEnabled = ptgEnabled,
        onImportFont = {
            onPickerActive(true)
            // Font MIME types are reported inconsistently by file providers, so octet-stream is allowed
            // too; the file is checked when it is loaded.
            fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/font-sfnt", "application/octet-stream"))
        },
        onRemoveFont = { name ->
            File(fontsDir(context), name).delete()
            if (controller.readingFontName == name) controller.readingFontName = null
            controller.readingFonts = loadReadingFonts(context)
        },
        onPtgEnabledChange = { ptgEnabled = it; store.setPtgEnabled(it) },
        ptgAutoAdd = ptgAutoAdd,
        onPtgAutoAddChange = { ptgAutoAdd = it; store.setPtgAutoAdd(it) },
        onCatalogAdd = { book ->
            val busy = ptgDownloads[book.id] is CatalogDownload.Running
            if (ptgEnabled && !busy && libraryEntries.none { ptgFiles.idOf(it.book.location) == book.id }) scope.launch {
                ptgDownloads = ptgDownloads + (book.id to CatalogDownload.Running(null))
                try {
                    val selection = ptgFiles.fetch(book.id, book.title) { fraction ->
                        ptgDownloads = ptgDownloads + (book.id to CatalogDownload.Running(fraction))
                    }
                    libraryEntries = store.importBook(selection, fromPtg = true)
                    ptgDownloads = ptgDownloads - book.id
                    Toast.makeText(context, "Added ${book.title.take(60)} to your library", Toast.LENGTH_SHORT).show()
                } catch (error: CancellationException) {
                    ptgDownloads = ptgDownloads - book.id
                    throw error
                } catch (error: Exception) {
                    ptgDownloads = ptgDownloads + (book.id to CatalogDownload.Failed(error.message ?: "The download failed."))
                }
            }
        },
        onCatalogOpen = {
            if (ptgEnabled) scope.launch {
                val ready = gutenberg.ready()
                if (ready == null) refreshCatalog(force = false)
                else {
                    catalog = catalog.copy(index = (catalog.index as? CatalogIndexState.Ready)?.takeIf { it.builtAt == ready.builtAt && it.updating } ?: ready)
                    if (ready.ageDays >= CATALOG_REFRESH_DAYS) refreshCatalog(force = false)
                }
            }
        },
        onCatalogSearch = { query ->
            if (ptgEnabled) {
                searchJob[0]?.cancel()
                searchJob[0] = scope.launch {
                    catalog = catalog.copy(searching = true)
                    try {
                        catalog = catalog.copy(result = gutenberg.search(query), searching = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        catalog = catalog.copy(searching = false, result = null, index = CatalogIndexState.Failed(error.message ?: "The catalog couldn't be searched."))
                    }
                }
            }
        },
        onCatalogRefresh = { refreshCatalog(force = catalog.index !is CatalogIndexState.Failed) },
        onDownloadCatalogBook = { id ->
            // Camus Reader doesn't host or download books itself: this opens the book's Gutenberg page
            // in an external browser so the person can pick a format, then import it via "Open".
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gutenberg.org/ebooks/$id"))) }
        },
        profileName = vault.profileName().ifBlank { "Local profile" },
        onLockProfile = onLock,
        encryptionEnabled = encryptionEnabled,
        onRequestEnableEncryption = onRequestEnableEncryption,
        onRequestDisableEncryption = onRequestDisableEncryption,
    )
}

/** How stale the on-device catalog may get before opening PTG quietly checks for a newer one. */
private const val CATALOG_REFRESH_DAYS = 7

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Reading text out of a PDF page needs Android 15's PdfRenderer additions (SDK extension 13 of Android 11). */
private fun pdfTextSelectionAvailable(): Boolean =
    android.os.Build.VERSION.SDK_INT >= 30 &&
        android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.S) >= 13

private fun fontsDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

private fun typefaceOrNull(file: File): Typeface? = runCatching {
    Typeface.createFromFile(file).takeIf { it != Typeface.DEFAULT }
}.getOrNull()

/** Every usable imported font, keyed by file name. Files that no longer load are skipped. */
private fun loadReadingFonts(context: Context): Map<String, FontFamily> =
    fontsDir(context).listFiles().orEmpty()
        .filter { it.isFile }
        .mapNotNull { file -> typefaceOrNull(file)?.let { file.name to FontFamily(it) } }
        .toMap()

/** Copies a picked font into the app's own storage; returns its file name, or null if it isn't a font. */
private fun importReadingFont(context: Context, uri: Uri): String? {
    val original = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: return null
    val extension = original.substringAfterLast('.', "").lowercase()
    if (extension != "ttf" && extension != "otf") return null
    val safe = original.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    val target = File(fontsDir(context), safe)
    val temp = File(fontsDir(context), ".$safe.part")
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return null
    }.onFailure { temp.delete(); return null }
    if (typefaceOrNull(temp) == null) {
        temp.delete()
        return null
    }
    target.delete()
    return if (temp.renameTo(target)) safe else { temp.delete(); null }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
